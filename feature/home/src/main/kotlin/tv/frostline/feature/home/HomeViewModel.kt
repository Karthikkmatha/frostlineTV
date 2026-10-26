package tv.frostline.feature.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.frostline.core.twitch.NetworkRetry
import tv.frostline.core.data.ResumeDao
import tv.frostline.core.data.ResumeEntity
import tv.frostline.core.data.SettingsStore
import tv.frostline.core.twitch.FollowedChannel
import tv.frostline.core.twitch.HelixGame
import tv.frostline.core.twitch.HelixRepository
import tv.frostline.core.twitch.HelixStream
import tv.frostline.core.twitch.SearchChannel
import tv.frostline.core.twitch.offlineChannels
import tv.frostline.core.ui.FocusMemory

enum class HomeSection(val id: String, val label: String) {
    Following("following", "Following"),
    Top("top", "Top"),
    Search("search", "Search"),
    Settings("settings", "Settings");

    companion object {
        fun byId(id: String) = entries.first { it.id == id }
    }
}

enum class TopTab { Streams, Categories }

data class FollowingState(
    val loading: Boolean = true,
    val error: String? = null,
    val live: List<HelixStream> = emptyList(),
    val offline: List<FollowedChannel> = emptyList(),
    val offlineExpanded: Boolean = false,
    /** Guest mode: no user token, so no follows. */
    val needsSignIn: Boolean = false,
    /** Profile image URL by broadcaster id. */
    val avatars: Map<String, String> = emptyMap(),
)

data class TopState(
    val tab: TopTab = TopTab.Streams,
    val loading: Boolean = false,
    val error: String? = null,
    val streams: List<HelixStream> = emptyList(),
    val categories: List<HelixGame> = emptyList(),
    val category: HelixGame? = null,
    val categoryStreams: List<HelixStream> = emptyList(),
    /** Profile image URL by broadcaster id (Top streams and category streams). */
    val avatars: Map<String, String> = emptyMap(),
)

data class SearchState(
    val query: String = "",
    val loading: Boolean = false,
    val searched: Boolean = false,
    val error: String? = null,
    val channels: List<SearchChannel> = emptyList(),
    val categories: List<HelixGame> = emptyList(),
    /** Profile image URL by channel id. */
    val avatars: Map<String, String> = emptyMap(),
)

/** Tells the visible grid to move focus into content; consumed once per request. */
class FocusSignal {
    var tick by mutableIntStateOf(1)
    var consumed = 0
    fun request() { tick++ }
}

class HomeViewModel(
    private val helix: HelixRepository,
    resumeDao: ResumeDao,
    /** Where the last searches are kept (null in tests). */
    private val settings: SettingsStore? = null,
    private val userId: suspend () -> String?,
) : ViewModel() {
    private val _section = MutableStateFlow(HomeSection.Following)
    val section: StateFlow<HomeSection> = _section.asStateFlow()

    private val _following = MutableStateFlow(FollowingState())
    val following: StateFlow<FollowingState> = _following.asStateFlow()
    private val _top = MutableStateFlow(TopState())
    val top: StateFlow<TopState> = _top.asStateFlow()
    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()

    val resume: StateFlow<List<ResumeEntity>> =
        resumeDao.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The last five searches, newest first. */
    val recentSearches: StateFlow<List<String>> =
        (settings?.recentSearches ?: kotlinx.coroutines.flow.flowOf(emptyList())).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun clearRecentSearches() { viewModelScope.launch { settings?.clearRecentSearches() } }

    /** Runs a stored search again, now. */
    fun searchRecent(q: String) {
        _search.update { it.copy(query = q) }
        searchNow()
    }

    val focusSignal = FocusSignal()
    private val memories = HashMap<String, FocusMemory>()
    fun memory(name: String): FocusMemory = memories.getOrPut(name) { FocusMemory() }

    private var followingLoadedAt = 0L
    private var searchJob: Job? = null

    /** Profile image URLs fetched so far (user id to url). Lists show them from here at once; only unknown ids hit Helix (100 per request). */
    private val avatarCache = HashMap<String, String>()

    private suspend fun avatarsFor(ids: Collection<String>): Map<String, String> {
        val wanted = ids.filter { it.isNotEmpty() }.distinct()
        val missing = synchronized(avatarCache) { wanted.filter { it !in avatarCache } }
        if (missing.isNotEmpty()) {
            val got = runCatching { helix.profileImages(missing) }.getOrNull()
            if (got != null) synchronized(avatarCache) {
                if (avatarCache.size > AVATAR_CACHE_MAX) avatarCache.clear()
                avatarCache.putAll(got)
            }
        }
        return synchronized(avatarCache) { wanted.mapNotNull { id -> avatarCache[id]?.let { id to it } }.toMap() }
    }

    private val attempts = HashMap<String, Int>()
    private val retryJobs = HashMap<String, Job>()

    init {
        loadFollowing()
    }

    /** Collected by the screen: when the network comes back, retry whatever failed, now. Never returns. */
    suspend fun watchNetwork() {
        NetworkRetry.available.collect {
            if (_following.value.error != null) loadFollowing()
            if (_top.value.error != null) loadTop()
            if (_search.value.error != null) searchNow()
        }
    }


    /** The message for a failed load; for a network error also schedules the next automatic retry (2 s, 5 s, 15 s, then 30 s). */
    private fun failed(name: String, e: Exception, fallback: String, again: () -> Unit): String {
        if (!NetworkRetry.isNetworkError(e)) return e.message ?: fallback
        val n = attempts[name] ?: 0
        attempts[name] = n + 1
        retryJobs[name]?.cancel()
        retryJobs[name] = viewModelScope.launch { delay(NetworkRetry.delayMs(n)); again() }
        return NetworkRetry.MESSAGE
    }

    private fun succeeded(name: String) { attempts.remove(name); retryJobs.remove(name)?.cancel() }

    fun select(section: HomeSection) {
        _section.value = section
        when (section) {
            HomeSection.Following -> if (System.currentTimeMillis() - followingLoadedAt > STALE_MS) loadFollowing()
            HomeSection.Top -> if (_top.value.streams.isEmpty() && !_top.value.loading) loadTop()
            else -> Unit
        }
        focusSignal.request()
    }

    /** Remember that we are about to leave for the player, so Back restores focus. */
    fun onLeaving() = focusSignal.request()

    // ---- Following ----
    fun loadFollowing() {
        viewModelScope.launch {
            _following.update { it.copy(loading = true, error = null, needsSignIn = false) }
            val uid = userId()
            if (uid == null) {
                _following.update { it.copy(loading = false, needsSignIn = true, live = emptyList(), offline = emptyList()) }
                return@launch
            }
            try {
                coroutineScope {
                    val liveD = async { helix.followedLive(uid) }
                    val allD = async { helix.followedChannels(uid) }
                    val live = liveD.await()
                    _following.update { it.copy(live = live) }
                    launch {
                        val avatars = avatarsFor(live.map { s -> s.userId })
                        if (avatars.isNotEmpty()) _following.update { it.copy(avatars = avatars) }
                    }
                    val all = allD.await()
                    _following.update { it.copy(loading = false, offline = offlineChannels(all, live)) }
                }
                followingLoadedAt = System.currentTimeMillis()
                succeeded("following")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _following.update { it.copy(loading = false, error = failed("following", e, "Could not load your follows", ::loadFollowing)) }
            }
        }
    }

    fun toggleOffline() = _following.update { it.copy(offlineExpanded = !it.offlineExpanded) }

    // ---- Top ----
    fun setTopTab(tab: TopTab) {
        _top.update { it.copy(tab = tab, category = null) }
        if (tab == TopTab.Categories && _top.value.categories.isEmpty()) loadTop()
    }

    fun loadTop() {
        viewModelScope.launch {
            _top.update { it.copy(loading = true, error = null) }
            try {
                coroutineScope {
                    val s = async { helix.topStreams() }
                    val c = async { helix.topCategories() }
                    val streams = s.await()
                    _top.update { it.copy(streams = streams, categories = c.await()) }
                    launch {
                        val avatars = avatarsFor(streams.map { x -> x.userId })
                        if (avatars.isNotEmpty()) _top.update { it.copy(avatars = it.avatars + avatars) }
                    }
                }
                _top.update { it.copy(loading = false) }
                succeeded("top")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _top.update { it.copy(loading = false, error = failed("top", e, "Could not load streams", ::loadTop)) }
            }
        }
    }

    fun openCategory(game: HelixGame) {
        // A category page always opens on its first card, not on the card that was open in the previous category.
        memory("top-cat").apply { key = null; scrollIndex = 0; scrollOffset = 0 }
        _top.update { it.copy(tab = TopTab.Categories, category = game, categoryStreams = emptyList(), loading = true, error = null) }
        _section.value = HomeSection.Top
        focusSignal.request()
        viewModelScope.launch {
            try {
                val s = helix.categoryStreams(game.id)
                _top.update { it.copy(categoryStreams = s, loading = false) }
                val avatars = avatarsFor(s.map { x -> x.userId })
                if (avatars.isNotEmpty()) _top.update { it.copy(avatars = it.avatars + avatars) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _top.update { it.copy(loading = false, error = e.message ?: "Could not load streams") }
            }
        }
        if (_top.value.categories.isEmpty()) viewModelScope.launch {
            runCatching { helix.topCategories() }.onSuccess { c -> _top.update { it.copy(categories = c) } }
        }
    }

    fun closeCategory() {
        _top.update { it.copy(category = null) }
        focusSignal.request()
    }

    // ---- Search ----
    fun setQuery(q: String) {
        _search.update { it.copy(query = q) }
        searchJob?.cancel()
        if (q.trim().length < 2) {
            _search.update { it.copy(channels = emptyList(), categories = emptyList(), searched = false, loading = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(450)
            runSearch(q.trim())
        }
    }

    fun searchNow() {
        val q = _search.value.query.trim()
        if (q.length < 2) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch { runSearch(q) }
    }

    private suspend fun runSearch(q: String) {
        _search.update { it.copy(loading = true, error = null) }
        try {
            coroutineScope {
                val ch = async { helix.searchChannels(q) }
                val cat = async { helix.searchCategories(q) }
                val channels = ch.await()
                val categories = cat.await()
                if (channels.isNotEmpty() || categories.isNotEmpty()) settings?.addRecentSearch(q)
                _search.update { it.copy(channels = channels, categories = categories, searched = true, loading = false) }
                launch {
                    val avatars = avatarsFor(channels.map { c -> c.id })
                    if (avatars.isNotEmpty()) _search.update { it.copy(avatars = avatars) }
                }
                succeeded("search")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _search.update { it.copy(loading = false, error = failed("search", e, "Search failed", ::searchNow)) }
        }
    }

    private companion object {
        const val STALE_MS = 60_000L
        const val AVATAR_CACHE_MAX = 600
    }
}
