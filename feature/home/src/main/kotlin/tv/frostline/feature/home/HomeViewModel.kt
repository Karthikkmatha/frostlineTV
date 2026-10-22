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
import tv.frostline.core.data.ResumeDao
import tv.frostline.core.data.ResumeEntity
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
)

data class SearchState(
    val query: String = "",
    val loading: Boolean = false,
    val searched: Boolean = false,
    val error: String? = null,
    val channels: List<SearchChannel> = emptyList(),
    val categories: List<HelixGame> = emptyList(),
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

    val focusSignal = FocusSignal()
    private val memories = HashMap<String, FocusMemory>()
    fun memory(name: String): FocusMemory = memories.getOrPut(name) { FocusMemory() }

    private var followingLoadedAt = 0L
    private var searchJob: Job? = null

    init {
        loadFollowing()
    }

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
                        val avatars = runCatching { helix.profileImages(live.map { s -> s.userId }) }.getOrNull()
                        if (avatars != null) _following.update { it.copy(avatars = avatars) }
                    }
                    val all = allD.await()
                    _following.update { it.copy(loading = false, offline = offlineChannels(all, live)) }
                }
                followingLoadedAt = System.currentTimeMillis()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _following.update { it.copy(loading = false, error = e.message ?: "Could not load your follows") }
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
                val tab = _top.value.tab
                if (tab == TopTab.Streams) {
                    val s = helix.topStreams()
                    _top.update { it.copy(streams = s) }
                } else {
                    val c = helix.topCategories()
                    _top.update { it.copy(categories = c) }
                }
                _top.update { it.copy(loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _top.update { it.copy(loading = false, error = e.message ?: "Could not load streams") }
            }
        }
    }

    fun openCategory(game: HelixGame) {
        _top.update { it.copy(tab = TopTab.Categories, category = game, categoryStreams = emptyList(), loading = true, error = null) }
        _section.value = HomeSection.Top
        focusSignal.request()
        viewModelScope.launch {
            try {
                val s = helix.categoryStreams(game.id)
                _top.update { it.copy(categoryStreams = s, loading = false) }
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
                _search.update { it.copy(channels = ch.await(), categories = cat.await(), searched = true, loading = false) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _search.update { it.copy(loading = false, error = e.message ?: "Search failed") }
        }
    }

    private companion object {
        const val STALE_MS = 60_000L
    }
}
