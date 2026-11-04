package tv.frostline.core.twitchinternal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tv.frostline.core.twitchinternal.J.arr
import tv.frostline.core.twitchinternal.J.bool
import tv.frostline.core.twitchinternal.J.instantMs
import tv.frostline.core.twitchinternal.J.int
import tv.frostline.core.twitchinternal.J.obj
import tv.frostline.core.twitchinternal.J.str
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One past broadcast the viewer started. [positionSec] and [lengthSec] are seconds, [updatedAtMs] is epoch milliseconds
 * (0 = Twitch did not say). The rest is what a card needs.
 */
data class HistoryItem(
    val videoId: String,
    val positionSec: Int,
    val updatedAtMs: Long,
    val title: String = "",
    val lengthSec: Int = 0,
    val thumbnailUrl: String = "",
    val channelLogin: String = "",
    val channelName: String = "",
    val game: String = "",
) {
    /** Past the first 30 s and not (nearly) finished: worth offering to resume. */
    val resumable: Boolean get() = positionSec > 30 && lengthSec > 0 && positionSec < lengthSec * 0.95
}

/** No web session, or Twitch refused or failed: callers fall back to the local history. */
class HistoryUnavailableException(message: String) : IOException(message)

/**
 * The viewer's Twitch watch history ("Continue watching" and each VOD's resume position). Needs the linked web session;
 * without one, or on any failure, the methods throw [HistoryUnavailableException] (or return false for [report]).
 */
interface TwitchHistory {
    /** The Continue watching shelf, newest first. */
    suspend fun continueWatching(): List<HistoryItem>

    /** Where the viewer stopped in [videoId], in seconds; null when never watched. */
    suspend fun position(videoId: String): Int?

    /** Tells Twitch the viewer is at [positionSec] of [videoId] (VODs only). True when it was accepted. Optional. */
    suspend fun report(videoId: String, positionSec: Int): Boolean = false
}

/** A [TwitchHistory] held in memory: the fake for tests (and what the app uses when nothing better exists). */
class InMemoryTwitchHistory(
    items: List<HistoryItem> = emptyList(),
    var available: Boolean = true,
) : TwitchHistory {
    private val store = LinkedHashMap<String, HistoryItem>().apply { items.forEach { put(it.videoId, it) } }
    val reports = mutableListOf<Pair<String, Int>>()
    var listCalls = 0
        private set

    override suspend fun continueWatching(): List<HistoryItem> {
        listCalls++
        if (!available) throw HistoryUnavailableException("no session")
        return store.values.sortedByDescending { it.updatedAtMs }
    }

    override suspend fun position(videoId: String): Int? {
        if (!available) throw HistoryUnavailableException("no session")
        return store[videoId]?.positionSec
    }

    override suspend fun report(videoId: String, positionSec: Int): Boolean {
        if (!available) return false
        reports += videoId to positionSec
        store[videoId] = (store[videoId] ?: HistoryItem(videoId, positionSec, 0)).copy(positionSec = positionSec, updatedAtMs = System.currentTimeMillis())
        return true
    }
}

/** Keeps the last list for [ttlMs] so Home, the resume prompt and the reporter share one request; a successful report drops it. */
class CachingTwitchHistory(
    private val inner: TwitchHistory,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 30_000L,
) : TwitchHistory {
    private var list: List<HistoryItem>? = null
    private var at = 0L

    fun invalidate() { synchronized(this) { list = null } }

    override suspend fun continueWatching(): List<HistoryItem> {
        synchronized(this) { list?.takeIf { clock() - at < ttlMs }?.let { return it } }
        val fresh = inner.continueWatching()
        synchronized(this) { list = fresh; at = clock() }
        return fresh
    }

    override suspend fun position(videoId: String): Int? {
        synchronized(this) { list?.takeIf { clock() - at < ttlMs }?.firstOrNull { it.videoId == videoId }?.let { return it.positionSec } }
        return inner.position(videoId)
    }

    override suspend fun report(videoId: String, positionSec: Int): Boolean {
        val ok = inner.report(videoId, positionSec)
        if (ok) invalidate()
        return ok
    }
}

/** Local history against Twitch's: for each video the newest `updatedAt` wins; a failed Twitch call leaves the local one alone. */
object HistoryMerge {
    /**
     * [remote] null = Twitch could not be asked (no session, or the call failed): [local] stands. A winner that is finished (or has
     * not passed 30 s) is dropped, so a video finished on the phone leaves the shelf. Newest first, at most [limit].
     */
    fun merge(local: List<HistoryItem>, remote: List<HistoryItem>?, limit: Int = 30): List<HistoryItem> {
        if (remote == null) return local
        val byId = LinkedHashMap<String, HistoryItem>()
        for (l in local) byId[l.videoId] = l
        for (r in remote) {
            val l = byId[r.videoId]
            byId[r.videoId] = if (l == null) r else newer(l, r)
        }
        return byId.values.filter { it.resumable }.sortedByDescending { it.updatedAtMs }.take(limit)
    }

    /**
     * The point to offer for one video. Newest `updatedAt` wins. A Twitch position that came without a time (`position(videoId)`)
     * only counts when there is no local entry.
     */
    fun pick(local: HistoryItem?, remote: HistoryItem?): HistoryItem? = when {
        remote == null -> local
        local == null -> remote
        remote.updatedAtMs <= 0L -> local
        else -> newer(local, remote)
    }

    private fun newer(local: HistoryItem, remote: HistoryItem): HistoryItem {
        val win = if (remote.updatedAtMs > local.updatedAtMs) remote else local
        val lose = if (win === remote) local else remote
        return win.copy(
            title = win.title.ifBlank { lose.title },
            lengthSec = if (win.lengthSec > 0) win.lengthSec else lose.lengthSec,
            thumbnailUrl = if (!HistoryParser.isMissingThumb(win.thumbnailUrl)) win.thumbnailUrl else lose.thumbnailUrl.takeIf { !HistoryParser.isMissingThumb(it) } ?: win.thumbnailUrl,
            channelLogin = win.channelLogin.ifBlank { lose.channelLogin },
            channelName = win.channelName.ifBlank { lose.channelName },
            game = win.game.ifBlank { lose.game },
        )
    }
}

/**
 * Writes the position back to Twitch without ever blocking playback: [tick] is called as often as convenient while playing
 * and sends at most once per [minIntervalMs] (45 s); [flush] (pause, seek commit, leaving) sends now unless that position
 * was just sent. Sends run on [scope] one at a time and their failures are ignored.
 */
class HistoryReporter(
    private val history: TwitchHistory?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val minIntervalMs: Long = 45_000L,
) {
    private val gate = Mutex()
    private var lastAt = Long.MIN_VALUE
    private var lastPos = -1

    fun tick(videoId: String, positionMs: Long) {
        val now = clock()
        if (history == null || lastAt != Long.MIN_VALUE && now - lastAt < minIntervalMs) return
        send(videoId, positionMs, now)
    }

    fun flush(videoId: String, positionMs: Long) {
        if (history == null) return
        val now = clock()
        if ((positionMs / 1000).toInt() == lastPos && now - lastAt < 5_000L) return
        send(videoId, positionMs, now)
    }

    private fun send(videoId: String, positionMs: Long, now: Long) {
        val sec = (positionMs / 1000).toInt()
        if (sec <= 0) return
        lastAt = now
        lastPos = sec
        scope.launch {
            gate.withLock { runCatching { history!!.report(videoId, sec) } }
        }
    }
}

/** Twitch's web GQL answers for the history operations. Every part is read defensively: a surprise gives null or an empty list. */
object HistoryParser {
    fun list(root: JsonObject): List<HistoryItem> {
        val edges = root["data"].obj()?.get("currentUser").obj()?.get("viewedVideos").obj()?.get("edges").arr().orEmpty()
        return edges.mapNotNull { e ->
            val edge = e.obj() ?: return@mapNotNull null
            val node = edge["node"].obj() ?: return@mapNotNull null
            val id = node["id"].str()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val self = node["self"].obj()
            if (self?.get("isRestricted").bool() == true) return@mapNotNull null
            val hist = edge["history"].obj() ?: self?.get("viewingHistory").obj()
            val pos = hist?.get("position").int() ?: return@mapNotNull null
            val owner = node["owner"].obj()
            val game = node["game"].obj()
            HistoryItem(
                videoId = id,
                positionSec = pos,
                updatedAtMs = instantMs(hist?.get("updatedAt").str()) ?: instantMs(self?.get("viewingHistory").obj()?.get("updatedAt").str()) ?: 0L,
                title = node["title"].str().orEmpty(),
                lengthSec = node["lengthSeconds"].int() ?: 0,
                thumbnailUrl = thumbnail(node["previewThumbnailURL"].str().orEmpty(), owner?.get("offlineImageURL").str(), owner?.get("profileImageURL").str()),
                channelLogin = owner?.get("login").str().orEmpty(),
                channelName = owner?.get("displayName").str().orEmpty(),
                game = game?.get("displayName").str() ?: game?.get("name").str().orEmpty(),
            )
        }
    }

    /** `data.video.self.viewingHistory.position`, null when never watched or absent. */
    fun position(root: JsonObject): Int? =
        root["data"].obj()?.get("video").obj()?.get("self").obj()?.get("viewingHistory").obj()?.get("position").int()

    /** Thumbnail templates (`%{width}x%{height}` or `{width}x{height}`) filled for a 320 x 180 card. */
    /**
     * The card image: the video's preview (template filled for 320 x 180), or when that is empty or Twitch's `404_processing`
     * placeholder (a 24/7 rerun, a live archive), the channel's offline banner, then its avatar, else empty.
     */
    fun thumbnail(previewUrl: String, offlineBanner: String?, avatar: String?): String {
        val sized = sized(previewUrl)
        if (!isMissingThumb(sized)) return sized
        return listOf(offlineBanner, avatar).firstOrNull { !it.isNullOrBlank() }.orEmpty()
    }

    /** Blank, or one of Twitch's "no thumbnail yet" placeholders. */
    fun isMissingThumb(url: String?): Boolean = url.isNullOrBlank() || url.contains("_404") || url.contains("404_processing")

    fun sized(url: String): String = url
        .replace("%{width}", "320").replace("%{height}", "180")
        .replace("{width}", "320").replace("{height}", "180")
}

/** The real thing: persisted GQL queries with the web session, a raw-query fallback when Twitch does not know the hash. */
internal class GqlTwitchHistory(
    private val session: suspend () -> String?,
    private val integrity: IntegrityProvider = IntegrityProvider.None,
    private val gqlUrl: String = GqlWebSessionValidator.GQL_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) : TwitchHistory {
    @Volatile private var viewerId: String? = null

    override suspend fun continueWatching(): List<HistoryItem> {
        val root = call(LIST_OP, LIST_HASH, LIST_RAW, buildJsonObject { put("limit", 20); put("includePreviewBlur", false) })
        return HistoryParser.list(root)
    }

    override suspend fun position(videoId: String): Int? {
        val root = call(POSITION_OP, POSITION_HASH, POSITION_RAW, buildJsonObject { put("videoId", videoId) })
        return HistoryParser.position(root)
    }

    override suspend fun report(videoId: String, positionSec: Int): Boolean {
        if (positionSec <= 0 || videoId.isBlank() || !videoId.all { it.isDigit() }) return false
        return try {
            val uid = viewerId ?: currentUserId()?.also { viewerId = it } ?: return false
            val vars = buildJsonObject {
                putJsonObject("input") {
                    put("userID", uid); put("position", positionSec); put("videoID", videoId); put("videoType", "VOD")
                }
            }
            val root = call(WRITE_OP, WRITE_HASH, WRITE_RAW, vars)
            root["data"].obj()?.containsKey("updateUserViewedVideo") == true
        } catch (e: HistoryUnavailableException) {
            false
        }
    }

    private suspend fun currentUserId(): String? =
        runCatching { call("CurrentUser", null, "query CurrentUser { currentUser { id } }", buildJsonObject { }) }.getOrNull()
            ?.get("data").obj()?.get("currentUser").obj()?.get("id").str()

    /** Persisted first; PersistedQueryNotFound means the raw [raw] query; an integrity error refreshes the credentials once. */
    private suspend fun call(op: String, hash: String?, raw: String, vars: JsonObject): JsonObject {
        val token = clean(session()) ?: throw HistoryUnavailableException("no web session")
        var refresh = false
        var usePersisted = hash != null
        repeat(3) {
            val root = post(op, if (usePersisted) hash else null, raw, vars, token, refresh)
            val errors = root["errors"].arr().orEmpty().mapNotNull { it.obj()?.get("message").str() }
            when {
                usePersisted && errors.any { it.contains("PersistedQueryNotFound", ignoreCase = true) } -> usePersisted = false
                IntegrityTokens.isIntegrityError(errors) && !refresh -> refresh = true
                errors.isNotEmpty() && root["data"].obj()?.values?.any { it !is kotlinx.serialization.json.JsonNull } != true ->
                    throw HistoryUnavailableException("GQL error")
                else -> return root
            }
        }
        throw HistoryUnavailableException("GQL gave no answer")
    }

    private suspend fun post(op: String, hash: String?, raw: String, vars: JsonObject, token: String, refreshIntegrity: Boolean): JsonObject = withContext(Dispatchers.IO) {
        val creds = runCatching { integrity.current(refreshIntegrity) }.getOrNull()
        val body = buildJsonObject {
            put("operationName", op)
            if (hash != null) {
                putJsonObject("extensions") { putJsonObject("persistedQuery") { put("version", 1); put("sha256Hash", hash) } }
            } else {
                put("query", raw)
            }
            put("variables", vars)
        }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(gqlUrl)
            .header("Client-Id", creds?.clientId ?: GqlWebSessionValidator.WEB_CLIENT_ID)
            .header("Authorization", "OAuth $token")
            .apply { if (creds != null) { header("Client-Integrity", creds.token); header("X-Device-Id", creds.deviceId) } }
            .post(body).build()
        val text = try {
            client.newCall(req).execute().use { r ->
                if (r.code !in 200..299) throw HistoryUnavailableException("HTTP ${r.code}")
                r.body.string()
            }
        } catch (e: HistoryUnavailableException) {
            throw e
        } catch (e: IOException) {
            throw HistoryUnavailableException(e.javaClass.simpleName) // a network exception's text can carry a URL
        }
        J.parse(text) ?: throw HistoryUnavailableException("unreadable answer")
    }

    private fun clean(s: String?): String? = s?.trim()?.removePrefix("auth-token=")?.trim('"', '\'', ' ', ';')?.takeIf { it.isNotEmpty() }

    companion object {
        const val LIST_OP = "FollowedStreamsContinueWatching"
        const val LIST_HASH = "f072a74b5a454d1971b4650adfdfb5d42b69d31af7fac58f64b5a09494f7ad53"
        const val LIST_RAW = "query FollowedStreamsContinueWatching(\$limit: Int!) { currentUser { viewedVideos(first: \$limit) { edges { history { position updatedAt } " +
            "node { id title lengthSeconds previewThumbnailURL(width: 320, height: 180) publishedAt owner { id login displayName profileImageURL(width: 70) offlineImageURL } game { name } } } } } }"
        const val POSITION_OP = "queryUserViewedVideo"
        const val POSITION_HASH = "86bf57f2a04e2f4705ce456d082bcffd80a372b6641a9c9120c409b10959a7a5"
        const val POSITION_RAW = "query queryUserViewedVideo(\$videoId: ID!) { video(id: \$videoId) { id self { viewingHistory { position } } } }"
        const val WRITE_OP = "updateUserViewedVideo"
        const val WRITE_HASH = "bb58b1bd08a4ca0c61f2b8d323381a5f4cd39d763da8698f680ef1dfaea89ca1"
        const val WRITE_RAW = "mutation updateUserViewedVideo(\$input: UpdateUserViewedVideoInput!) { updateUserViewedVideo(input: \$input) { video { id } } }"
    }
}
