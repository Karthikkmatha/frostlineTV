package tv.frostline.core.twitchinternal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

data class VodBadge(val set: String, val version: String)

/** A piece of a replayed message: plain text, or an emote ([emoteId] set; [text] is its code). */
data class VodFragment(val text: String, val emoteId: String?)

data class VodComment(
    val id: String,
    /** Seconds from the start of the video. */
    val offsetSeconds: Int,
    val userId: String,
    val login: String,
    val displayName: String,
    /** "#RRGGBB" or null. */
    val colorHex: String?,
    val badges: List<VodBadge>,
    val fragments: List<VodFragment>,
)

data class CommentsPage(
    val comments: List<VodComment>,
    /** Cursor of the last comment of this page, for the next request. */
    val nextCursor: String?,
    val hasNext: Boolean,
)

/** The replay endpoint refuses (persisted query unknown, video gone, chat deleted). The caller hides the replay. */
class ReplayUnavailableException(reason: String) : IOException(reason)

interface VideoCommentsApi {
    /** Comments from [offsetSeconds] on, oldest first. */
    suspend fun byOffset(videoId: String, offsetSeconds: Int): CommentsPage

    /** The page after [cursor]. */
    suspend fun byCursor(videoId: String, cursor: String): CommentsPage
}

object CommentsParser {
    private val json = Json { ignoreUnknownKeys = true }

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull

    fun parse(text: String): CommentsPage {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw IOException("Unreadable comments response")
        val errors = root["errors"] as? JsonArray
        if (errors != null && errors.isNotEmpty()) {
            val msg = errors.joinToString { it.obj()?.get("message").str().orEmpty() }
            if (msg.contains("PersistedQuery", ignoreCase = true)) throw ReplayUnavailableException("Chat replay is not supported right now")
            throw IOException("Comments request failed")
        }
        val comments = root["data"].obj()?.get("video").obj()?.get("comments").obj()
            ?: throw ReplayUnavailableException("Chat replay is not available for this video")
        val edges = comments["edges"] as? JsonArray ?: JsonArray(emptyList())
        var last: String? = null
        val out = ArrayList<VodComment>(edges.size)
        for (e in edges) {
            val edge = e.obj() ?: continue
            edge["cursor"].str()?.let { last = it }
            val node = edge["node"].obj() ?: continue
            val id = node["id"].str() ?: continue
            val offset = node["contentOffsetSeconds"].int() ?: continue
            val commenter = node["commenter"].obj()
            val message = node["message"].obj()
            val fragments = (message?.get("fragments") as? JsonArray).orEmpty().mapNotNull { f ->
                val fo = f.obj() ?: return@mapNotNull null
                val t = fo["text"].str() ?: return@mapNotNull null
                VodFragment(t, fo["emote"].obj()?.get("emoteID").str())
            }
            val badges = (message?.get("userBadges") as? JsonArray).orEmpty().mapNotNull { b ->
                val bo = b.obj() ?: return@mapNotNull null
                val set = bo["setID"].str() ?: return@mapNotNull null
                VodBadge(set, bo["version"].str().orEmpty())
            }
            val login = commenter?.get("login").str().orEmpty()
            out += VodComment(
                id = id,
                offsetSeconds = offset,
                userId = commenter?.get("id").str().orEmpty(),
                login = login,
                displayName = commenter?.get("displayName").str()?.ifEmpty { null } ?: login.ifEmpty { "?" },
                colorHex = message?.get("userColor").str()?.takeIf { it.length == 7 && it[0] == '#' },
                badges = badges,
                fragments = fragments,
            )
        }
        val hasNext = comments["pageInfo"].obj()?.get("hasNextPage").let { (it as? JsonPrimitive)?.contentOrNull == "true" }
        return CommentsPage(out, last, hasNext && last != null)
    }
}

internal class GqlVideoComments(
    private val gqlUrl: String = GqlWebSessionValidator.GQL_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) : VideoCommentsApi {
    override suspend fun byOffset(videoId: String, offsetSeconds: Int): CommentsPage =
        request(buildJsonObject { put("videoID", videoId); put("contentOffsetSeconds", offsetSeconds.coerceAtLeast(0)) })

    override suspend fun byCursor(videoId: String, cursor: String): CommentsPage =
        request(buildJsonObject { put("videoID", videoId); put("cursor", cursor) })

    private suspend fun request(variables: JsonObject): CommentsPage = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("operationName", OPERATION)
            put("variables", variables)
            put(
                "extensions",
                buildJsonObject {
                    put("persistedQuery", buildJsonObject { put("version", 1); put("sha256Hash", HASH) })
                },
            )
        }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(gqlUrl).header("Client-Id", GqlWebSessionValidator.WEB_CLIENT_ID).post(body).build()
        val text = client.newCall(req).execute().use { r ->
            if (r.code !in 200..299) throw IOException("Comments request failed (HTTP ${r.code})")
            r.body.string()
        }
        CommentsParser.parse(text)
    }

    companion object {
        const val OPERATION = "VideoCommentsByOffsetOrCursor"
        const val HASH = "b70a3591ff0f4e0313d126c6a1502d79a1c02baebb288227c582044aa76adf6a"
    }
}
