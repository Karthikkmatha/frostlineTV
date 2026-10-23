package tv.frostline.feature.channel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.frostline.core.twitch.NetworkRetry
import tv.frostline.core.twitch.ClipPeriod
import tv.frostline.core.twitch.HelixClip
import tv.frostline.core.twitch.HelixRepository
import tv.frostline.core.twitch.HelixStream
import tv.frostline.core.twitch.HelixUser
import tv.frostline.core.twitch.HelixVideo
import tv.frostline.core.twitch.PagedResult
import tv.frostline.core.twitch.VideoKind
import tv.frostline.core.ui.FocusMemory

/** What the channel page needs from Twitch. A seam for tests. */
interface ChannelSource {
    suspend fun user(login: String): HelixUser?
    /** The live stream, or null when offline (or when that lookup fails: the page degrades to "Offline"). */
    suspend fun live(login: String): HelixStream?
    suspend fun videos(userId: String, kind: VideoKind, after: String?): PagedResult<HelixVideo>
    suspend fun clips(userId: String, period: ClipPeriod, after: String?): PagedResult<HelixClip>
}

fun HelixRepository.asChannelSource(): ChannelSource = object : ChannelSource {
    override suspend fun user(login: String) = userByLogin(login)
    override suspend fun live(login: String) = streamByLogin(login)
    override suspend fun videos(userId: String, kind: VideoKind, after: String?) = this@asChannelSource.videos(userId, kind, after)
    override suspend fun clips(userId: String, period: ClipPeriod, after: String?) = this@asChannelSource.clips(userId, period, after)
}

enum class ChannelTab(val label: String) { Videos("Videos"), Clips("Clips"), About("About") }

/** One paged list. [cursor] null with [loaded] true means the end. */
data class PagedList<T>(
    val items: List<T> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val cursor: String? = null,
    val loaded: Boolean = false,
) {
    val canLoadMore: Boolean get() = !loading && error == null && (!loaded || cursor != null)
}

data class ChannelState(
    val loading: Boolean = true,
    val error: String? = null,
    val login: String,
    val user: HelixUser? = null,
    val live: HelixStream? = null,
    val tab: ChannelTab = ChannelTab.Videos,
    val videoKind: VideoKind = VideoKind.Broadcasts,
    val clipPeriod: ClipPeriod = ClipPeriod.Week,
    val videos: Map<VideoKind, PagedList<HelixVideo>> = emptyMap(),
    val clips: Map<ClipPeriod, PagedList<HelixClip>> = emptyMap(),
) {
    val displayName: String get() = user?.displayName?.ifBlank { null } ?: login
    val currentVideos: PagedList<HelixVideo> get() = videos[videoKind] ?: PagedList()
    val currentClips: PagedList<HelixClip> get() = clips[clipPeriod] ?: PagedList()
}

/**
 * The channel page: header (avatar, name, live status), then Videos / Clips / About. Lists are paged by the
 * Helix cursor and load the next page when the grid nears its end. Failures degrade per part: no live
 * lookup means "Offline", a failed list shows Retry, the rest of the page keeps working.
 */
class ChannelViewModel(
    login: String,
    private val source: ChannelSource,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope
    private val _state = MutableStateFlow(ChannelState(login = login.lowercase()))
    val state: StateFlow<ChannelState> = _state.asStateFlow()

    /** Where focus and scroll were when the user left for a video, so Back restores them. */
    val memory = FocusMemory()

    private var headerJob: Job? = null
    private val listJobs = HashMap<String, Job>()

    private var attempt = 0
    private var retryJob: kotlinx.coroutines.Job? = null

    /** Friendly text for a network error plus the next automatic retry (2 s, 5 s, 15 s, then 30 s). */
    private fun failure(e: Exception, fallback: String): String {
        if (!NetworkRetry.isNetworkError(e)) return e.message ?: fallback
        val n = attempt++
        retryJob?.cancel()
        retryJob = scope.launch { kotlinx.coroutines.delay(NetworkRetry.delayMs(n)); retry() }
        return NetworkRetry.MESSAGE
    }

    init {
        load()
    }

    /** Collected by the screen: when the network comes back, retry whatever failed. Never returns. */
    suspend fun watchNetwork() {
        NetworkRetry.available.collect {
            val s = _state.value
            if (s.error != null || s.currentVideos.error != null || s.currentClips.error != null) retry()
        }
    }

    fun load() {
        headerJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        headerJob = scope.launch {
            try {
                coroutineScope {
                    val liveD = async { runCatching { source.live(_state.value.login) }.getOrNull() }
                    val user = source.user(_state.value.login)
                    if (user == null) {
                        _state.update { it.copy(loading = false, error = "Channel not found") }
                        return@coroutineScope
                    }
                    _state.update { it.copy(user = user, loading = false) }
                    _state.update { it.copy(live = liveD.await()) }
                    loadMore()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = failure(e, "Could not load the channel")) }
            }
        }
    }

    fun selectTab(tab: ChannelTab) {
        if (_state.value.tab == tab) return
        _state.update { it.copy(tab = tab) }
        memory.key = null
        val s = _state.value
        val empty = if (tab == ChannelTab.Videos) s.currentVideos.items.isEmpty() else tab == ChannelTab.Clips && s.currentClips.items.isEmpty()
        if (empty) loadMore()
    }

    fun selectVideoKind(kind: VideoKind) {
        if (_state.value.videoKind == kind) return
        _state.update { it.copy(videoKind = kind) }
        memory.key = null
        if (_state.value.currentVideos.items.isEmpty()) loadMore()
    }

    fun selectClipPeriod(period: ClipPeriod) {
        if (_state.value.clipPeriod == period) return
        _state.update { it.copy(clipPeriod = period) }
        memory.key = null
        if (_state.value.currentClips.items.isEmpty()) loadMore()
    }

    /** Loads the next page of the list on screen (or the first). No-op while loading, at the end, or after an error. */
    fun loadMore() {
        val s = _state.value
        val user = s.user ?: return
        when (s.tab) {
            ChannelTab.Videos -> {
                val kind = s.videoKind
                val cur = s.currentVideos
                if (!cur.canLoadMore) return
                fetch("v:${kind.name}", { l -> _state.update { it.copy(videos = it.videos + (kind to l(it.videos[kind] ?: PagedList()))) } }) {
                    source.videos(user.id, kind, cur.cursor)
                }
            }
            ChannelTab.Clips -> {
                val period = s.clipPeriod
                val cur = s.currentClips
                if (!cur.canLoadMore) return
                fetch("c:${period.name}", { l -> _state.update { it.copy(clips = it.clips + (period to l(it.clips[period] ?: PagedList()))) } }) {
                    source.clips(user.id, period, cur.cursor)
                }
            }
            ChannelTab.About -> Unit
        }
    }

    /** Clears the error of the list on screen and tries again. */
    fun retry() {
        val s = _state.value
        when (s.tab) {
            ChannelTab.Videos -> _state.update { it.copy(videos = it.videos + (s.videoKind to s.currentVideos.copy(error = null))) }
            ChannelTab.Clips -> _state.update { it.copy(clips = it.clips + (s.clipPeriod to s.currentClips.copy(error = null))) }
            ChannelTab.About -> if (s.user == null) load()
        }
        if (s.error != null && s.user == null) { load(); return }
        loadMore()
    }

    private fun <T> fetch(key: String, update: ((PagedList<T>) -> PagedList<T>) -> Unit, request: suspend () -> PagedResult<T>) {
        update { it.copy(loading = true, error = null) }
        listJobs[key]?.cancel()
        listJobs[key] = scope.launch {
            try {
                val page = request()
                update { cur ->
                    // Helix can repeat an item across pages; keep each once.
                    val seen = HashSet<Any>()
                    val merged = (cur.items + page.items).filter { x -> seen.add(idOf(x)) }
                    cur.copy(items = merged, loading = false, cursor = page.cursor, loaded = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update { it.copy(loading = false, error = failure(e, "Could not load")) }
            }
        }
    }

    private fun idOf(x: Any?): Any = when (x) {
        is HelixVideo -> x.id
        is HelixClip -> x.id
        else -> x ?: ""
    }
}
