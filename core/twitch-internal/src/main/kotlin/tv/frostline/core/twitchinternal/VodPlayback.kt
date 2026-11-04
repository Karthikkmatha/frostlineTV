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

    /**
     * The seek-preview storyboard of a past broadcast (GQL `video(id:) { seekPreviewsURL }`, then the JSON it points at), or null when
     * the field, the document or its shape is missing: no thumbnails, never an error. [durationSec] fills a missing frame interval.
     */
    suspend fun storyboard(videoId: String, durationSec: Long, webSession: String?): StoryboardSheetSet? = null

    /** The bytes of one storyboard sheet (https only, capped at 6 MB), or null on any failure. */
    suspend fun sheetBytes(url: String): ByteArray? = null

    /** What the last [storyboard] call saw, for the debug overlay (no URLs, tokens or ids). */
    fun storyboardDiag(): StoryboardDiag = StoryboardDiag.NONE
}

/** `seekpv: url ok|missing, sheets N, fmt <field names seen>, frame i, err <class>`: [urlOk] null = never asked. */
data class StoryboardDiag(val urlOk: Boolean?, val sheets: Int, val fmt: String, val err: String) {
    fun line(frame: Int?): String =
        "seekpv: url ${when (urlOk) { true -> "ok"; false -> "missing"; null -> "-" }}, sheets $sheets, fmt ${fmt.ifEmpty { "-" }}, frame ${frame ?: "-"}, err ${err.ifEmpty { "-" }}"

    companion object {
        val NONE = StoryboardDiag(null, 0, "", "")

        /** Only the class of a failure, or the HTTP code in "(HTTP 403)": never a message that may carry a URL. */
        fun errorClass(e: Throwable): String {
            val code = Regex("HTTP (\\d{3})").find(e.message.orEmpty())?.groupValues?.get(1)
            return if (code != null) "http$code" else e.javaClass.simpleName
        }
    }
}

internal class TwitchVodResolver(
    private val gqlUrl: String = GqlWebSessionValidator.GQL_URL,
    private val usherBase: String = TwitchPlaybackResolver.USHER_BASE,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val random: () -> Int = { (Math.random() * 9_999_999).toInt() },
    private val decoders: DecoderCapability = DeviceDecoders,
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
            .addQueryParameter("supported_codecs", CodecPolicy.supportedCodecsParam(decoders))
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
        ResolvedStream(url.toString(), MasterPlaylistParser.videoVariants(text, decoders))
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

    @Volatile private var diag = StoryboardDiag.NONE

    override fun storyboardDiag(): StoryboardDiag = diag

    override suspend fun storyboard(videoId: String, durationSec: Long, webSession: String?): StoryboardSheetSet? = withContext(Dispatchers.IO) {
        var err = ""
        var urlOk: Boolean? = null
        var fmt = ""
        var sheets = 0
        try {
            val id = videoId.trim()
            fun urlOf(root: JsonObject?): String? {
                if (root == null) return null
                if ((root["errors"] as? JsonArray)?.isNotEmpty() == true && err.isEmpty()) err = "gqlerror"
                return root.obj("data")?.obj("video")?.get("seekPreviewsURL")?.jsonPrimitive?.contentOrNull?.takeIf { fetchable(it) }
            }
            // The web player's own persisted query first; a raw equivalent when Twitch does not know the hash or answers without the field.
            val url = urlOf(runCatching { gql(SEEK_PERSISTED_OPERATION, "", buildJsonObject { put("includePrivate", false); put("videoID", id) }, webSession, SEEK_HASH) }
                .onFailure { err = StoryboardDiag.errorClass(it) }.getOrNull())
                ?: urlOf(runCatching { gql(SEEK_OPERATION, SEEK_QUERY, buildJsonObject { put("id", id) }, webSession) }
                    .onFailure { err = StoryboardDiag.errorClass(it) }.getOrNull())
            urlOk = url != null
            if (url == null) return@withContext null
            val body = client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (r.code !in 200..299) { err = "http${r.code}"; return@use null }
                val src = r.body.source()
                src.request(MAX_INFO_BYTES + 1)
                if (src.buffer.size > MAX_INFO_BYTES) { err = "toolarge"; return@use null }
                src.readUtf8()
            } ?: return@withContext null
            fmt = Storyboards.fieldNames(body)
            val set = Storyboards.parse(body, url, durationSec)
            sheets = set?.images?.size ?: 0
            if (set == null && err.isEmpty()) err = "noparse"
            set
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            err = StoryboardDiag.errorClass(e)
            null
        } finally {
            diag = StoryboardDiag(urlOk, sheets, fmt, err)
        }
    }

    override suspend fun sheetBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        if (!fetchable(url)) return@withContext null
        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (r.code !in 200..299) { diag = diag.copy(err = "sheet" + r.code); return@use null }
                val src = r.body.source()
                src.request(MAX_SHEET_BYTES + 1)
                if (src.buffer.size > MAX_SHEET_BYTES) null else src.readByteArray()
            }
        }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else { diag = diag.copy(err = StoryboardDiag.errorClass(it)); null } }
    }

    /** https only (plus loopback http for tests). */
    private fun fetchable(url: String) = url.startsWith("https://") || url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")

    /** The GQL `errors[].message` texts joined (they carry no tokens), or empty. */
    private fun errorText(root: JsonObject): String =
        (root["errors"] as? JsonArray).orEmpty().joinToString(" ") { (it as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull.orEmpty() }

    private fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject

    private fun gql(operation: String, query: String, variables: JsonObject, webSession: String?, persistedHash: String? = null): JsonObject {
        val body = buildJsonObject {
            put("operationName", operation)
            if (persistedHash != null) {
                put("extensions", buildJsonObject { put("persistedQuery", buildJsonObject { put("version", 1); put("sha256Hash", persistedHash) }) })
            } else {
                put("query", query)
            }
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
        const val SEEK_PERSISTED_OPERATION = "VideoPlayer_VODSeekbarPreviewVideo"
        const val SEEK_HASH = "07e99e4d56c5a7c67117a154777b0baf85a5ffefa393b213f4bc712ccaf85dd6"
        const val SEEK_OPERATION = "FrostlineSeekPreviews"
        const val SEEK_QUERY = "query FrostlineSeekPreviews(\$id: ID!) { video(id: \$id) { seekPreviewsURL } }"
        private const val MAX_INFO_BYTES = 1_000_000L
        private const val MAX_SHEET_BYTES = 6_000_000L
        const val CLIP_OPERATION = "VideoAccessToken_Clip"
        const val CLIP_QUERY =
            "query VideoAccessToken_Clip(\$slug: ID!) { clip(slug: \$slug) { " +
                "playbackAccessToken(params: {platform: \"web\", playerType: \"site\", playerBackend: \"mediaplayer\"}) { signature value } " +
                "videoQualities { frameRate quality sourceURL } } }"
    }
}
