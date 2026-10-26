package tv.frostline.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.frostline.core.data.LiveMode
import tv.frostline.core.data.SettingsStore
import tv.frostline.core.data.TokenVault
import tv.frostline.core.twitch.HelixRepository
import tv.frostline.core.twitchinternal.ChannelOfflineException
import tv.frostline.core.twitchinternal.HermesClient
import tv.frostline.core.twitchinternal.PlaybackResolver
import tv.frostline.core.twitchinternal.RaidEvents
import tv.frostline.core.twitchinternal.StreamVariant
import tv.frostline.core.twitchinternal.WebSessionRejectedException

data class PlayerUi(
    val login: String,
    val displayName: String,
    val title: String = "",
    val loading: Boolean = true,
    val error: String? = null,
    /** Debug overlay only: status and parameter names of a refused playlist request (never a token). */
    val errorDetail: String? = null,
    val offline: Boolean = false,
    val playing: Boolean = false,
    val variants: List<StreamVariant> = emptyList(),
    /** groupId of the pinned quality; null = Auto. */
    val quality: String? = null,
    val liveOffsetMs: Long? = null,
    /** The Live mode this stream was opened with (shown in the debug overlay). */
    val liveMode: LiveMode = LiveMode.Stable,
    val notice: String? = null,
)

/** The player's live offset (null when unknown) and whether it is playing right now (not paused, not buffering). */
data class LiveSync(val latencyMs: Long? = null, val playing: Boolean = false)

/**
 * Owns playback for one channel at a time. The ExoPlayer lives in the app-wide [ExclusiveSlot] and only
 * between [start] and [stop], so it is released on leaving the screen and in onStop.
 */
@OptIn(UnstableApi::class)
class PlayerViewModel(
    initialLogin: String,
    private val slot: ExclusiveSlot<ExoPlayer>,
    private val playback: PlaybackResolver,
    private val helix: HelixRepository,
    private val vault: TokenVault,
    private val settings: SettingsStore,
    private val hermes: HermesClient,
) : ViewModel() {
    private val _ui = MutableStateFlow(PlayerUi(initialLogin, initialLogin))
    val ui: StateFlow<PlayerUi> = _ui.asStateFlow()

    /** The live player while the screen is started, else null. */
    val player: StateFlow<ExoPlayer?> get() = slot.current

    private val _aspect = MutableStateFlow(16f / 9f)
    val videoAspect: StateFlow<Float> = _aspect.asStateFlow()

    val metrics = PlaybackMetrics()
    val debugOverlay = settings.debugOverlay
    val showFps = settings.showFps

    private val channelId = MutableStateFlow<String?>(null)
    private var loadJob: Job? = null
    private var tickJob: Job? = null
    private var raidJob: Job? = null
    private var pendingQuality: String? = null
    private var currentVariants: List<StreamVariant> = emptyList()
    private var currentUrl: String? = null
    private var modeAtStart: LiveMode = LiveMode.Stable
    private val recovery = PlaybackRecovery()
    private var recoverJob: Job? = null
    private var watchJob: Job? = null
    private var bufferingSinceMs = 0L
    private var playingSinceMs = 0L

    val raid = RaidCountdown(viewModelScope) { target -> switchChannel(target) }

    private var lastErrorName = ""

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) tv.frostline.core.twitchinternal.IntegrityGate.streamStarted()
            playingSinceMs = if (isPlaying) System.currentTimeMillis() else 0L
            _ui.update { it.copy(playing = isPlaying, notice = if (isPlaying && it.notice == RECONNECTING) null else it.notice) }
        }
        override fun onPlaybackStateChanged(state: Int) {
            bufferingSinceMs = if (state == Player.STATE_BUFFERING) System.currentTimeMillis() else 0L
            // A live stream that ends (the streamer restarted, or it really ended): look for it again.
            if (state == Player.STATE_ENDED) recoverFrom(PlaybackRecovery.Action.Reload)
        }
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                _aspect.value = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
        }
        override fun onTracksChanged(tracks: Tracks) {
            pendingQuality?.let { id -> applyQuality(id) }
        }
        override fun onPlayerError(error: PlaybackException) {
            lastErrorName = error.errorCodeName
            recoverFrom(recovery.classify(error.errorCode))
        }
    }

    private val lifecycle = PlayerLifecycle(
        slot,
        onAcquired = { p ->
            p.addListener(listener)
            p.addAnalyticsListener(metrics)
        },
        onReleasing = { p ->
            p.removeListener(listener)
            p.removeAnalyticsListener(metrics)
            p.stop()
        },
    )

    /** Debug overlay: the raid topic's own Hermes state. */
    val raidLine: kotlinx.coroutines.flow.Flow<String> = combine(channelId, settings.followRaids, hermes.topicStatus) { id, on, st ->
        when {
            !on -> "off (setting)"
            id == null -> "idle (no channel id)"
            else -> st[RaidEvents.topic(id)] ?: "connecting"
        }
    }

    init {
        viewModelScope.launch { tv.frostline.core.ui.WakeSignal.events.collect { onWake() } }
        // Playback failed (or never started) and the network is back: rebuild the source.
        viewModelScope.launch {
            tv.frostline.core.twitch.NetworkRetry.available.collect {
                val u = _ui.value
                if (u.error != null && !u.offline) refresh()
            }
        }
        viewModelScope.launch {
            // Raids: only while a channel id is known and the setting is on.
            combine(channelId, settings.followRaids) { id, on -> if (on) id else null }.collectLatest { id ->
                raid.reset()
                if (id != null) {
                    hermes.events(listOf(RaidEvents.topic(id))).collect { ev ->
                        RaidEvents.parse(ev.payload)?.let(raid::onEvent)
                    }
                }
            }
        }
    }

    /** Screen became visible (ON_START). Idempotent. */
    fun start() {
        if (lifecycle.isActive) return
        // The player is built for the Live mode in force right now; the setting applies to the next stream opened.
        modeAtStart = PlayerProfile.liveMode
        PlayerProfile.forLive = true
        _ui.update { it.copy(liveMode = modeAtStart) }
        lifecycle.start()
        startTicker()
        startWatchdog()
        recovery.reset()
        load(_ui.value.login)
    }

    /** Screen left or went to the background (ON_STOP / dispose). Releases the decoder. Idempotent. */
    fun stop() {
        loadJob?.cancel()
        tickJob?.cancel()
        watchJob?.cancel()
        recoverJob?.cancel()
        lifecycle.stop()
    }

    fun refresh() { recovery.reset(); load(_ui.value.login) }

    fun switchChannel(login: String) {
        raid.reset()
        recovery.reset()
        recoverJob?.cancel()
        channelId.value = null
        _ui.update { PlayerUi(login = login, displayName = login, quality = it.quality, liveMode = modeAtStart) }
        pendingQuality = _ui.value.quality
        if (lifecycle.isActive) load(login)
    }

    fun togglePlay() {
        val p = player.value ?: return
        if (p.isPlaying) p.pause() else {
            // Resuming a live stream: jump to the live edge instead of replaying old buffer.
            p.seekToDefaultPosition()
            p.play()
        }
    }

    fun showNotice(text: String) {
        _ui.update { it.copy(notice = text) }
        viewModelScope.launch {
            delay(2_500)
            _ui.update { if (it.notice == text) it.copy(notice = null) else it }
        }
    }

    // ---- auto-recovery ----

    /** Plays again after a failure or stall: instant seek, a fresh playlist (new token), or a new player, with backoff. */
    private fun recoverFrom(first: PlaybackRecovery.Action) {
        if (!lifecycle.isActive) return
        val action = recovery.onFailure(first)
        if (action == PlaybackRecovery.Action.GiveUp) {
            recovery.reset()
            _ui.update { it.copy(loading = false, notice = null, error = "Playback error (${lastErrorName.ifEmpty { "stream ended" }})") }
            return
        }
        scheduleRecovery(action)
    }

    private fun scheduleRecovery(action: PlaybackRecovery.Action) {
        val wait = recovery.delayMs(action)
        _ui.update { it.copy(loading = false, error = null, offline = false, notice = RECONNECTING) }
        recoverJob?.cancel()
        recoverJob = viewModelScope.launch {
            delay(wait)
            when (action) {
                PlaybackRecovery.Action.SeekToLive -> player.value?.let { it.seekToDefaultPosition(); it.prepare() }
                PlaybackRecovery.Action.RebuildPlayer -> {
                    lifecycle.stop()
                    lifecycle.start()
                    load(_ui.value.login)
                }
                else -> load(_ui.value.login)
            }
        }
    }

    /** Checks, while the screen is up, that playback has not silently stalled (a stuck buffer, a stale playlist). */
    private fun startWatchdog() {
        watchJob?.cancel()
        watchJob = viewModelScope.launch {
            while (true) {
                delay(5_000)
                val now = System.currentTimeMillis()
                val p = player.value ?: continue
                if (bufferingSinceMs != 0L && now - bufferingSinceMs > PlaybackRecovery.STALL_MS && p.playWhenReady) {
                    bufferingSinceMs = now
                    lastErrorName = "stalled"
                    recoverFrom(PlaybackRecovery.Action.Reload)
                }
                if (playingSinceMs != 0L && now - playingSinceMs > PlaybackRecovery.STEADY_MS && recovery.recovering) recovery.onSteady()
            }
        }
    }

    /** The TV woke up (screen on / user present): playback that died or fell far behind while it slept is brought back. */
    fun onWake() {
        val p = player.value ?: return
        if (!lifecycle.isActive || _ui.value.loading) return
        val u = _ui.value
        when {
            u.error != null && !u.offline -> refresh()
            p.playbackState == Player.STATE_ENDED || p.playerError != null -> { lastErrorName = "woke"; recoverFrom(PlaybackRecovery.Action.Reload) }
            p.playWhenReady && p.currentLiveOffset.let { it != C.TIME_UNSET && it > WAKE_MAX_BEHIND_MS } -> p.seekToDefaultPosition()
        }
    }

    /** [variant] null = Auto (adaptive). */
    fun selectQuality(variant: StreamVariant?) {
        _ui.update { it.copy(quality = variant?.groupId) }
        pendingQuality = variant?.groupId ?: AUTO
        applyQuality(pendingQuality!!)
    }

    private fun applyQuality(id: String) {
        val p = player.value ?: return
        if (id == AUTO) {
            QualityTracks.clear(p)
            pendingQuality = null
            return
        }
        val v = currentVariants.firstOrNull { it.groupId == id } ?: return
        if (QualityTracks.pin(p, v)) pendingQuality = null
    }

    private fun load(login: String) {
        val p = player.value ?: return
        loadJob?.cancel()
        tv.frostline.core.twitchinternal.IntegrityGate.streamStarting() // the hidden integrity WebView waits for playback + 10 s
        _ui.update { it.copy(loading = true, error = null, offline = false) }
        metrics.onLoadStart()
        loadJob = viewModelScope.launch {
            try {
                val info = launch {
                    val s = runCatching { helix.streamByLogin(login) }.getOrNull()
                    if (s != null) {
                        _ui.update { it.copy(displayName = s.userName.ifBlank { login }, title = s.title) }
                        channelId.value = s.userId
                    }
                }
                val stream = resolve(login)
                tv.frostline.core.ui.RestrictedStreams.clear(login)
                currentVariants = stream.variants
                currentUrl = stream.masterUrl
                _ui.update { it.copy(variants = stream.variants, loading = false) }
                val item = MediaItem.Builder()
                    .setUri(stream.masterUrl)
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .setLiveConfiguration(LiveOffsets.configFor(modeAtStart))
                    .build()
                p.setMediaItem(item)
                p.prepare()
                p.playWhenReady = true
                info.join()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChannelOfflineException) {
                // Mid-recovery an offline answer is often a streamer restart: keep looking for a while before saying so.
                if (recovery.recovering && recovery.onFailure(PlaybackRecovery.Action.Reload) != PlaybackRecovery.Action.GiveUp) scheduleRecovery(PlaybackRecovery.Action.Reload)
                else { recovery.reset(); _ui.update { it.copy(loading = false, notice = null, offline = true, error = "${it.displayName} is offline") } }
            } catch (e: tv.frostline.core.twitchinternal.StreamRestrictedException) {
                tv.frostline.core.ui.RestrictedStreams.mark(login)
                _ui.update { it.copy(loading = false, error = e.message, errorDetail = e.detail) }
            } catch (e: Exception) {
                if (recovery.recovering && recovery.onFailure(PlaybackRecovery.Action.Reload) != PlaybackRecovery.Action.GiveUp) { scheduleRecovery(PlaybackRecovery.Action.Reload); return@launch }
                recovery.reset()
                _ui.update { it.copy(loading = false, notice = null, error = tv.frostline.core.twitch.NetworkRetry.userMessage(e, "Could not start playback"), errorDetail = if (tv.frostline.core.twitch.NetworkRetry.isNetworkError(e)) e.javaClass.simpleName else null) }
            }
        }
    }

    private suspend fun resolve(login: String) = try {
        playback.resolve(login, vault.currentWebSession(), lowLatency = modeAtStart == LiveMode.LowLatency)
    } catch (e: WebSessionRejectedException) {
        // The linked session is dead: flag it in Settings and carry on anonymously.
        vault.setWebSessionExpired(true)
        playback.resolve(login, null, lowLatency = modeAtStart == LiveMode.LowLatency)
    }

    /** True while something on screen shows the live offset (the controls or the debug overlay). */
    private val offsetObserved = MutableStateFlow(false)

    /**
     * What chat needs to line up with the picture: how far behind live it is, and whether it is playing. Updated once a second
     * while the screen is up, separately from [ui], so reading it never recomposes the screen.
     */
    private val _sync = MutableStateFlow(LiveSync())
    val sync: StateFlow<LiveSync> = _sync.asStateFlow()

    /** The live offset shown in [ui] is refreshed once a second only while [on]; the [sync] value for chat always is. */
    fun setOffsetObserved(on: Boolean) {
        offsetObserved.value = on
        if (on) refreshOffset()
    }

    private fun refreshOffset() {
        val p = player.value
        val off = p?.currentLiveOffset?.takeIf { v -> v != C.TIME_UNSET }
        _sync.value = LiveSync(off, p?.isPlaying == true)
        if (offsetObserved.value) _ui.update { it.copy(liveOffsetMs = off) }
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (true) {
                refreshOffset()
                delay(1_000)
            }
        }
    }

    override fun onCleared() {
        stop()
    }

    private companion object {
        const val AUTO = "\u0000auto"
        const val RECONNECTING = "Reconnecting..."
        const val WAKE_MAX_BEHIND_MS = 40_000L
    }
}

/** Where playback sits behind live, per Live mode. */
@OptIn(UnstableApi::class)
object LiveOffsets {
    fun configFor(mode: LiveMode): MediaItem.LiveConfiguration = when (mode) {
        // About 12-14 s behind live; barely any speed change, so the offset only drifts back slowly.
        LiveMode.Stable -> MediaItem.LiveConfiguration.Builder()
            .setTargetOffsetMs(13_000)
            .setMinOffsetMs(12_000)
            .setMaxOffsetMs(14_000)
            .setMinPlaybackSpeed(0.97f)
            .setMaxPlaybackSpeed(1.03f)
            .build()
        // About 4-8 s behind live (a 5 s target leaves room for a jittery link), catching up with up to 8 % speed-up.
        LiveMode.LowLatency -> MediaItem.LiveConfiguration.Builder()
            .setTargetOffsetMs(5_000)
            .setMinOffsetMs(4_000)
            .setMaxOffsetMs(8_000)
            .setMinPlaybackSpeed(0.95f)
            .setMaxPlaybackSpeed(1.08f)
            .build()
    }

    fun targetSeconds(mode: LiveMode): Int = if (mode == LiveMode.Stable) 13 else 5
}
