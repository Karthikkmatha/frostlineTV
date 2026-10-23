package tv.frostline.core.twitchinternal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Why a video cannot be played, in words a viewer can act on. */
enum class VodRestriction(val message: String?) {
    SubscriberOnly("Subscriber-only video"),
    Region("This video is unavailable in your region"),
    Gone("Deleted or private"),
    /** Twitch gave no reason. */
    Unknown(null),
}

/** The video or clip cannot be played (deleted, subscriber-only, or Twitch refused a token). */
class VodUnavailableException(what: String, val restriction: VodRestriction = VodRestriction.Unknown) :
    IOException(restriction.message ?: "$what is not available")

/** Turns what Twitch said (GQL error text, or the playlist's status and body) into a [VodRestriction]. */
object VodReasons {
    /** [text] is the GQL `errors` messages or the playlist response body, [http] the playlist status if there was one. */
    fun classify(http: Int?, text: String): VodRestriction {
        val t = text.lowercase()
        return when {
            "subscri" in t || "sub only" in t || "sub_only" in t || "subonly" in t -> VodRestriction.SubscriberOnly
            "region" in t || "geo" in t || "country" in t || "your location" in t -> VodRestriction.Region
            "not found" in t || "deleted" in t || "private" in t || "unauthorized" in t && http == null -> VodRestriction.Gone
            http == 404 -> VodRestriction.Gone
            // A bare 403 from the VOD playlist is, in practice, a subscriber-only broadcast.
            http == 403 -> VodRestriction.SubscriberOnly
            http == null -> VodRestriction.Gone // no token and nothing else said: deleted or private
            else -> VodRestriction.Unknown
        }
    }
}

/** One progressive MP4 quality of a clip. [url] carries a signed token: never log it. */
class ClipQuality(val quality: String, val frameRate: Double, val url: String) {
    /** "720p" or "720p60". */
    val label: String get() = quality + "p" + (if (frameRate > 30.5) frameRate.toInt().toString() else "")
    override fun toString(): String = "ClipQuality($label)"
}

class ResolvedClip(val qualities: List<ClipQuality>) {
    override fun toString(): String = "ResolvedClip(qualities=${qualities.size})"
}

/** Playback of past broadcasts (usher VOD playlist) and clips. GQL only; degrades by throwing [VodUnavailableException]. */
interface VodPlaybackResolver {
    /** [webSession] is the `auth-token` value, sent as `Authorization: OAuth` to GQL only. */
    suspend fun vod(videoId: String, webSession: String?): ResolvedStream

    suspend fun clip(slug: String, webSession: String?): ResolvedClip
}

internal class TwitchVodResolver(
    private val gqlUrl: String = GqlWebSessionValidator.GQL_URL,
    private val usherBase: String = TwitchPlaybackResolver.USHER_BASE,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val random: () -> Int = { (Math.random() * 9_999_999).toInt() },
) : VodPlaybackResolver {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun vod(videoId: String, webSession: String?): ResolvedStream = withContext(Dispatchers.IO) {
        val id = videoId.trim()
        val root = gql(VOD_OPERATION, VOD_QUERY, buildJsonObject { put("vodID", id) }, webSession)
        val node = root.obj("data")?.obj("videoPlaybackAccessToken")
            ?: throw VodUnavailableException("This video", VodReasons.classify(null, errorText(root)))
        val value = node["value"]?.jsonPrimitive?.contentOrNull
        val sig = node["signature"]?.jsonPrimitive?.contentOrNull
        if (value.isNullOrEmpty() || sig.isNullOrEmpty()) throw VodUnavailableException("This video", VodReasons.classify(null, errorText(root)))
        val url = usherBase.toHttpUrl().newBuilder()
            .addPathSegments("vod/$id.m3u8")
            .addQueryParameter("sig", sig)
            .addQueryParameter("token", value)
            .addQueryParameter("allow_source", "true")
            .addQueryParameter("allow_audio_only", "true")
            .addQueryParameter("playlist_include_framerate", "true")
            .addQueryParameter("player_backend", "mediaplayer")
            .addQueryParameter("supported_codecs", "h264")
            .addQueryParameter("p", random().toString())
            .build()
        val text = client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            when {
                r.code == 403 || r.code == 404 ->
                    throw VodUnavailableException("This video", VodReasons.classify(r.code, runCatching { r.body.string().take(400) }.getOrDefault("")))
                r.code !in 200..299 -> throw IOException("Playlist request failed (HTTP ${r.code})")
                else -> r.body.string()
            }
        }
        ResolvedStream(url.toString(), MasterPlaylistParser.videoVariants(text))
    }

    override suspend fun clip(slug: String, webSession: String?): ResolvedClip = withContext(Dispatchers.IO) {
        val root = gql(CLIP_OPERATION, CLIP_QUERY, buildJsonObject { put("slug", slug.trim()) }, webSession)
        val clip = root.obj("data")?.obj("clip") ?: throw VodUnavailableException("This clip")
        val token = clip.obj("playbackAccessToken")
        val sig = token?.get("signature")?.jsonPrimitive?.contentOrNull
        val value = token?.get("value")?.jsonPrimitive?.contentOrNull
        if (sig.isNullOrEmpty() || value.isNullOrEmpty()) throw VodUnavailableException("This clip")
        val qualities = (clip["videoQualities"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val src = o["sourceURL"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val q = o["quality"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val fps = o["frameRate"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val sep = if (src.contains('?')) "&" else "?"
            ClipQuality(q, fps, src + sep + "sig=" + sig + "&token=" + URLEncoder.encode(value, "UTF-8"))
        }.sortedByDescending { it.quality.toIntOrNull() ?: 0 }
        if (qualities.isEmpty()) throw VodUnavailableException("This clip")
        ResolvedClip(qualities)
    }

    /** The GQL `errors[].message` texts joined (they carry no tokens), or empty. */
    private fun errorText(root: JsonObject): String =
        (root["errors"] as? JsonArray).orEmpty().joinToString(" ") { (it as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull.orEmpty() }

    private fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject

    private fun gql(operation: String, query: String, variables: JsonObject, webSession: String?): JsonObject {
        val body = buildJsonObject {
            put("operationName", operation)
            put("query", query)
            put("variables", variables)
        }.toString().toRequestBody("application/json".toMediaType())
        val b = Request.Builder().url(gqlUrl).header("Client-Id", GqlWebSessionValidator.WEB_CLIENT_ID).post(body)
        val token = webSession?.trim()?.removePrefix("auth-token=")?.trim('"', '\'', ' ', ';')?.takeIf { it.isNotEmpty() }
        if (token != null) b.header("Authorization", "OAuth $token")
        client.newCall(b.build()).execute().use { r ->
            if (r.code == 401 && token != null) throw WebSessionRejectedException()
            if (r.code !in 200..299) throw IOException("Access token request failed (HTTP ${r.code})")
            return runCatching { json.parseToJsonElement(r.body.string()).jsonObject }.getOrNull()
                ?: throw IOException("Unreadable access token response")
        }
    }

    companion object {
        const val VOD_OPERATION = "PlaybackAccessToken"
        const val VOD_QUERY =
            "query PlaybackAccessToken(\$vodID: ID!) { videoPlaybackAccessToken(id: \$vodID, " +
                "params: {platform: \"web\", playerBackend: \"mediaplayer\", playerType: \"site\"}) { value signature } }"
        const val CLIP_OPERATION = "VideoAccessToken_Clip"
        const val CLIP_QUERY =
            "query VideoAccessToken_Clip(\$slug: ID!) { clip(slug: \$slug) { " +
                "playbackAccessToken(params: {platform: \"web\", playerType: \"site\", playerBackend: \"mediaplayer\"}) { signature value } " +
                "videoQualities { frameRate quality sourceURL } } }"
    }
}
