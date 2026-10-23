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

    val raid = RaidCountdown(viewModelScope) { target -> switchChannel(target) }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = _ui.update { it.copy(playing = isPlaying) }
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                _aspect.value = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
        }
        override fun onTracksChanged(tracks: Tracks) {
            pendingQuality?.let { id -> applyQuality(id) }
        }
        override fun onPlayerError(error: PlaybackException) {
            _ui.update { it.copy(loading = false, error = "Playback error (${error.errorCodeName})") }
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

    init {
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
        load(_ui.value.login)
    }

    /** Screen left or went to the background (ON_STOP / dispose). Releases the decoder. Idempotent. */
    fun stop() {
        loadJob?.cancel()
        tickJob?.cancel()
        lifecycle.stop()
    }

    fun refresh() = load(_ui.value.login)

    fun switchChannel(login: String) {
        raid.reset()
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
                _ui.update { it.copy(loading = false, offline = true, error = "${it.displayName} is offline") }
            } catch (e: tv.frostline.core.twitchinternal.StreamRestrictedException) {
                _ui.update { it.copy(loading = false, error = e.message, errorDetail = e.detail) }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = e.message ?: "Could not start playback") }
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

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (true) {
                val off = player.value?.currentLiveOffset
                _ui.update { it.copy(liveOffsetMs = off?.takeIf { v -> v != C.TIME_UNSET }) }
                delay(1_000)
            }
        }
    }

    override fun onCleared() {
        stop()
    }

    private companion object {
        const val AUTO = "\u0000auto"
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
