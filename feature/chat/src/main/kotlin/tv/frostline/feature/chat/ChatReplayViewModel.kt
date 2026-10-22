package tv.frostline.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.frostline.core.data.ChatSettings
import tv.frostline.core.data.SettingsStore
import tv.frostline.core.twitch.Durations
import tv.frostline.core.twitch.HelixRepository
import tv.frostline.core.twitchinternal.VideoCommentsApi

/** Where the video is right now. */
class ReplayPosition(val positionMs: Long, val playing: Boolean)

/**
 * Chat of a past broadcast, replayed against the playhead. Same lines, emotes and badges as live chat, same
 * 200 ms batching and 200-line backlog; read-only. [position] returns null while no player exists.
 */
class ChatReplayViewModel(
    videoId: String,
    private val emotes: EmoteRepository,
    private val helix: HelixRepository,
    private val settingsStore: SettingsStore,
    private val position: () -> ReplayPosition?,
    api: VideoCommentsApi,
    private val pager: ChatReplayPager = ChatReplayPager(api, videoId),
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val flushMs: Long = 200L,
) : ViewModel() {
    private val buffer = ChatBuffer(ChatViewModel.MAX_LINES)

    private val _lines = MutableStateFlow<List<ChatLine>>(emptyList())
    /** Newest first. */
    val lines: StateFlow<List<ChatLine>> = _lines.asStateFlow()

    private val _status = MutableStateFlow("")
    /** Empty, or why the replay is not showing messages. */
    val status: StateFlow<String> = _status.asStateFlow()

    private val _playing = MutableStateFlow(true)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    val settings: StateFlow<ChatSettings> = settingsStore.chat.stateIn(viewModelScope, SharingStarted.Eagerly, ChatSettings())

    @Volatile private var ctx = ResolveContext(emptyMap(), emptyMap())
    private var sets: EmoteSets = EmoteSets.Empty
    private var badgeUrls: Map<String, String> = emptyMap()
    private var channelKey: String? = null
    private var loop: Job? = null
    private var assets: Job? = null
    private var started = false

    /** Which channel's emotes and badges to resolve. Call again if it changes. */
    fun setChannel(login: String, channelId: String?) {
        val key = "$login/$channelId"
        if (key == channelKey) return
        channelKey = key
        assets?.cancel()
        assets = viewModelScope.launch(worker) {
            val id = channelId ?: runCatching { helix.userByLogin(login)?.id }.getOrNull()
            launch {
                sets = emotes.forChannel(id)
                rebuildContext()
            }
            launch {
                val g = runCatching { helix.globalBadges() }.getOrDefault(emptyList())
                val c = if (id != null) runCatching { helix.channelBadges(id) }.getOrDefault(emptyList()) else emptyList()
                val m = HashMap<String, String>()
                for (set in g + c) for (v in set.versions) {
                    val u = v.url2x.ifEmpty { v.url1x }
                    if (u.isNotEmpty()) m["${set.setId}/${v.id}"] = u
                }
                badgeUrls = m
                rebuildContext()
            }
        }
    }

    private fun rebuildContext() {
        val s = settings.value
        ctx = ResolveContext(sets.index(s.show7tv, s.showBttv, s.showFfz), badgeUrls)
    }

    /** Screen visible. Idempotent. */
    fun start() {
        if (started) return
        started = true
        // The loop runs on the main dispatcher because the player may only be asked for its position there;
        // the pager request and message building move to [worker].
        loop = viewModelScope.launch {
            while (isActive) {
                delay(flushMs)
                if (!settings.value.enabled) continue // hidden panel: no polling, no fetching
                val pos = position() ?: continue
                withContext(worker) { process(pos) }
            }
        }
    }

    fun stop() {
        started = false
        loop?.cancel()
        loop = null
    }

    /** One 200 ms step: ask the pager what became due and publish the result once. Internal for tests. */
    internal suspend fun process(pos: ReplayPosition) {
        _playing.value = pos.playing
        rebuildContext()
        val batch = pager.advance(pos.positionMs)
        var changed = false
        if (batch.reset) {
            buffer.clear()
            changed = true
        }
        // A seek can hand over a lot at once; only the newest lines can ever be shown.
        val msgs = if (batch.messages.size > ChatViewModel.MAX_LINES) batch.messages.takeLast(ChatViewModel.MAX_LINES) else batch.messages
        for (c in msgs) {
            buffer.addLocal(MessageBuilder.build(c.toRaw(), ctx, timeLabel = Durations.clock(c.offsetSeconds * 1000L)))
            changed = true
        }
        _status.value = when {
            pager.unavailable -> "Chat replay is not available for this video"
            pager.failing -> "Chat replay: retrying..."
            else -> ""
        }
        if (changed) _lines.value = buffer.snapshot()
    }

    fun update(transform: (ChatSettings) -> ChatSettings) {
        viewModelScope.launch { settingsStore.updateChat(transform) }
    }

    fun setEnabled(on: Boolean) = update { it.copy(enabled = on) }

    override fun onCleared() {
        stop()
    }
}
