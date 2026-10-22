package tv.frostline.core.twitchinternal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A resolved live stream: the master playlist URL (contains a signed token: never log it) and its qualities. */
class ResolvedStream(val masterUrl: String, val variants: List<StreamVariant>) {
    override fun toString(): String = "ResolvedStream(variants=${variants.size})"
}

/** The channel is not live (usher answered 404). */
class ChannelOfflineException(login: String) : IOException("$login is offline")

/** Twitch refused the linked web session (401). The caller should flag it expired and retry anonymously. */
class WebSessionRejectedException : IOException("Web session rejected")

interface PlaybackResolver {
    /** [webSession] is the `auth-token` value, sent as `Authorization: OAuth` to GQL only. */
    suspend fun resolve(login: String, webSession: String?): ResolvedStream
}

internal class TwitchPlaybackResolver(
    private val gqlUrl: String = GqlWebSessionValidator.GQL_URL,
    private val usherBase: String = USHER_BASE,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val random: () -> Int = { (Math.random() * 9_999_999).toInt() },
) : PlaybackResolver {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun resolve(login: String, webSession: String?): ResolvedStream = withContext(Dispatchers.IO) {
        val (value, sig) = accessToken(login.lowercase(), webSession)
        val url = usherBase.toHttpUrl().newBuilder()
            .addPathSegments("api/channel/hls/${login.lowercase()}.m3u8")
            .addQueryParameter("sig", sig)
            .addQueryParameter("token", value)
            .addQueryParameter("allow_source", "true")
            .addQueryParameter("allow_audio_only", "true")
            .addQueryParameter("fast_bread", "true")
            .addQueryParameter("playlist_include_framerate", "true")
            .addQueryParameter("player_backend", "mediaplayer")
            .addQueryParameter("supported_codecs", "h264")
            .addQueryParameter("p", random().toString())
            .build()
        val text = client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            when {
                r.code == 404 -> throw ChannelOfflineException(login)
                r.code !in 200..299 -> throw IOException("Playlist request failed (HTTP ${r.code})")
                else -> r.body.string()
            }
        }
        ResolvedStream(url.toString(), MasterPlaylistParser.videoVariants(text))
    }

    private fun accessToken(login: String, webSession: String?): Pair<String, String> {
        val body = buildJsonObject {
            put("operationName", "PlaybackAccessToken")
            put("query", GqlWebSessionValidator.QUERY)
            put("variables", buildJsonObject { put("login", login) })
        }.toString().toRequestBody("application/json".toMediaType())
        val b = Request.Builder().url(gqlUrl).header("Client-Id", GqlWebSessionValidator.WEB_CLIENT_ID).post(body)
        // The web session goes to GQL only; the Helix token never reaches this module.
        val token = webSession?.trim()?.removePrefix("auth-token=")?.trim('"', '\'', ' ', ';')?.takeIf { it.isNotEmpty() }
        if (token != null) b.header("Authorization", "OAuth $token")
        client.newCall(b.build()).execute().use { r ->
            if (r.code == 401 && token != null) throw WebSessionRejectedException()
            if (r.code !in 200..299) throw IOException("Access token request failed (HTTP ${r.code})")
            return parseAccessToken(r.body.string(), login)
        }
    }

    private fun parseAccessToken(text: String, login: String): Pair<String, String> {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw IOException("Unreadable access token response")
        val node = (root["data"] as? JsonObject)?.get("streamPlaybackAccessToken") as? JsonObject
            ?: throw ChannelOfflineException(login)
        val value = node["value"]?.jsonPrimitive?.contentOrNull
        val sig = node["signature"]?.jsonPrimitive?.contentOrNull
        if (value.isNullOrEmpty() || sig.isNullOrEmpty()) throw IOException("Incomplete access token")
        return value to sig
    }

    companion object {
        const val USHER_BASE = "https://usher.ttvnw.net/"
    }
}
