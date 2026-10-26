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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.frostline.core.data.ResumeDao
import tv.frostline.core.data.ResumeEntity
import tv.frostline.core.data.SettingsStore
import tv.frostline.core.data.TokenVault
import tv.frostline.core.twitch.HelixRepository
import tv.frostline.core.twitch.Thumbnails
import tv.frostline.core.twitchinternal.ClipQuality
import tv.frostline.core.twitchinternal.StreamVariant
import tv.frostline.core.twitchinternal.VodPlaybackResolver
import tv.frostline.core.twitchinternal.VodUnavailableException
import tv.frostline.core.twitchinternal.WebSessionRejectedException
import tv.frostline.feature.chat.ReplayPosition

/** What the VOD player plays. */
sealed interface PlayTarget {
    data class Vod(val videoId: String) : PlayTarget
    data class Clip(val slug: String) : PlayTarget
}

data class VodUi(
    val target: PlayTarget,
    val channelLogin: String = "",
    val channelName: String = "",
    val title: String = "",
    val loading: Boolean = true,
    val error: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val variants: List<StreamVariant> = emptyList(),
    /** groupId of the pinned quality; null = Auto (VOD) or the best (clip). */
    val quality: String? = null,
    val notice: String? = null,
    /** Non-null while "Resume from ... / Start over" is waiting for a choice. */
    val resumePromptMs: Long? = null,
) {
    val isClip: Boolean get() = target is PlayTarget.Clip
}

/**
 * Plays one past broadcast or clip in the app-wide player slot. Same lifecycle as [PlayerViewModel]: the
 * ExoPlayer exists between [start] and [stop]. VODs resume (position saved every 10 s and on exit), clips do not.
 */
@OptIn(UnstableApi::class)
class VodPlayerViewModel(
    val target: PlayTarget,
    private val slot: ExclusiveSlot<ExoPlayer>,
    private val resolver: VodPlaybackResolver,
    private val helix: HelixRepository,
    private val vault: TokenVault,
    private val settings: SettingsStore,
    private val resumeDao: ResumeDao,
    /** Outlives the screen: the final position is written after the view model is gone. */
    private val ioScope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _ui = MutableStateFlow(VodUi(target))
    val ui: StateFlow<VodUi> = _ui.asStateFlow()

    val player: StateFlow<ExoPlayer?> get() = slot.current

    private val _aspect = MutableStateFlow(16f / 9f)
    val videoAspect: StateFlow<Float> = _aspect.asStateFlow()

    val metrics = PlaybackMetrics()
    val debugOverlay = settings.debugOverlay
    val showFps = settings.showFps

    /** The broadcaster's user id, once known (chat replay emotes and badges). */
    private val _channelId = MutableStateFlow<String?>(null)
    val channelId: StateFlow<String?> = _channelId.asStateFlow()

    val skip = SkipController(
        scope = viewModelScope,
        position = { player.value?.currentPosition ?: 0L },
        duration = { knownDuration() },
        seekTo = { pos -> player.value?.seekTo(pos); refreshPosition() },
        now = { android.os.SystemClock.uptimeMillis() },
    )

    private var loadJob: Job? = null
    private var tickJob: Job? = null
    private var netJob: Job? = null
    private var currentVariants: List<StreamVariant> = emptyList()
    private var clipQualities: List<ClipQuality> = emptyList()
    private var pendingQuality: String? = null
    private var resumeEntity: ResumeEntity? = null
    private var resumeDecided = false
    private var lastSavedAt = 0L
    private var thumbnail = ""
    private var helixDurationMs = 0L

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = _ui.update { it.copy(playing = isPlaying) }
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                _aspect.value = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
        }
        override fun onTracksChanged(tracks: Tracks) {
            pendingQuality?.let { id -> applyHlsQuality(id) }
        }
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) refreshPosition()
            if (state == Player.STATE_ENDED) {
                _ui.update { it.copy(playing = false) }
                if (target is PlayTarget.Vod) finishedWatching()
            }
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
            saveResume(p.currentPosition, p.duration.takeIf { it != C.TIME_UNSET } ?: 0L)
            p.removeListener(listener)
            p.removeAnalyticsListener(metrics)
            p.stop()
        },
    )

    /** For chat replay. Must be read on the main thread (the player's application thread). */
    fun replayPosition(): ReplayPosition? {
        val p = player.value ?: return null
        return ReplayPosition(p.currentPosition, p.isPlaying)
    }

    fun start() {
        if (lifecycle.isActive) return
        PlayerProfile.forLive = false // VODs always use the stable buffer profile
        lifecycle.start()
        startTicker()
        netJob?.cancel()
        // Playback failed and the network is back: rebuild the source at the same position.
        netJob = viewModelScope.launch {
            tv.frostline.core.twitch.NetworkRetry.available.collect { if (_ui.value.error != null) refresh() }
        }
        load(startAt = null)
    }

    fun stop() {
        loadJob?.cancel()
        netJob?.cancel()
        tickJob?.cancel()
        skip.cancel()
        lifecycle.stop()
    }

    /** Reload (same position). */
    fun refresh() = load(startAt = player.value?.currentPosition?.takeIf { it > 0 })

    fun togglePlay() {
        val p = player.value ?: return
        if (_ui.value.resumePromptMs != null) return
        if (p.isPlaying) p.pause() else {
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
            p.play()
        }
    }

    fun pressSkip(deltaMs: Long, repeat: Boolean) = skip.press(deltaMs, repeat)

    fun resumeFromSaved() {
        val prompt = _ui.value.resumePromptMs ?: return
        _ui.update { it.copy(resumePromptMs = null) }
        resumeDecided = true
        player.value?.let {
            it.seekTo(prompt)
            it.play()
        }
    }

    fun startOver() {
        if (_ui.value.resumePromptMs == null) return
        _ui.update { it.copy(resumePromptMs = null) }
        resumeDecided = true
        player.value?.let {
            it.seekTo(0)
            it.play()
        }
    }

    fun showNotice(text: String) {
        _ui.update { it.copy(notice = text) }
        viewModelScope.launch {
            delay(2_500)
            _ui.update { if (it.notice == text) it.copy(notice = null) else it }
        }
    }

    /** [variant] null = Auto (VOD) / the best quality (clip). */
    fun selectQuality(variant: StreamVariant?) {
        _ui.update { it.copy(quality = variant?.groupId) }
        if (target is PlayTarget.Clip) {
            val q = clipQualities.firstOrNull { qualityId(it) == variant?.groupId } ?: clipQualities.firstOrNull() ?: return
            playClip(q, player.value?.currentPosition ?: 0L, autoplay = player.value?.playWhenReady ?: true)
            return
        }
        pendingQuality = variant?.groupId ?: AUTO
        applyHlsQuality(pendingQuality!!)
    }

    private fun applyHlsQuality(id: String) {
        val p = player.value ?: return
        if (id == AUTO) {
            QualityTracks.clear(p)
            pendingQuality = null
            return
        }
        val v = currentVariants.firstOrNull { it.groupId == id } ?: return
        if (QualityTracks.pin(p, v)) pendingQuality = null
    }

    private fun load(startAt: Long?) {
        val p = player.value ?: return
        loadJob?.cancel()
        _ui.update { it.copy(loading = true, error = null) }
        metrics.onLoadStart()
        loadJob = viewModelScope.launch {
            try {
                val info = launch { loadInfo() }
                when (val t = target) {
                    is PlayTarget.Vod -> {
                        val stream = withSession { resolver.vod(t.videoId, it) }
                        currentVariants = stream.variants
                        _ui.update { it.copy(variants = stream.variants, loading = false) }
                        val saved = if (startAt == null) resumeDao.get(t.videoId) else null
                        resumeEntity = saved
                        val offer = saved?.takeIf { ResumePolicy.resumable(it.position, it.duration) }
                        p.setMediaItem(
                            MediaItem.Builder().setUri(stream.masterUrl).setMimeType(MimeTypes.APPLICATION_M3U8).build(),
                            startAt ?: 0L,
                        )
                        p.prepare()
                        if (offer != null && !resumeDecided) {
                            // Wait for the choice; the picture shows the first frame meanwhile.
                            _ui.update { it.copy(resumePromptMs = offer.position) }
                            p.playWhenReady = false
                        } else {
                            resumeDecided = true
                            p.playWhenReady = true
                        }
                    }
                    is PlayTarget.Clip -> {
                        val clip = withSession { resolver.clip(t.slug, it) }
                        clipQualities = clip.qualities
                        val variants = clip.qualities.mapIndexed { i, q ->
                            StreamVariant(q.label, qualityId(q), 0, q.quality.toIntOrNull() ?: 0, q.frameRate, (q.quality.toLongOrNull() ?: 0) * 1000, i == 0, false)
                        }
                        currentVariants = variants
                        _ui.update { it.copy(variants = variants, loading = false) }
                        val chosen = clip.qualities.firstOrNull { qualityId(it) == _ui.value.quality } ?: clip.qualities.first()
                        playClip(chosen, startAt ?: 0L, autoplay = true)
                    }
                }
                info.join()
            } catch (e: CancellationException) {
                throw e
            } catch (e: VodUnavailableException) {
                if (e.restriction == tv.frostline.core.twitchinternal.VodRestriction.SubscriberOnly) (target as? PlayTarget.Vod)?.let { tv.frostline.core.twitch.VodRestrictions.markSubscriberOnly(it.videoId) }
                _ui.update { it.copy(loading = false, error = e.message) }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = e.message ?: "Could not start playback") }
            }
        }
    }

    private fun playClip(q: ClipQuality, at: Long, autoplay: Boolean) {
        val p = player.value ?: return
        p.setMediaItem(MediaItem.fromUri(q.url), at)
        p.prepare()
        p.playWhenReady = autoplay
    }

    private fun qualityId(q: ClipQuality) = q.quality + "p" + q.frameRate.toInt()

    private suspend fun <T> withSession(block: suspend (String?) -> T): T = try {
        block(vault.currentWebSession())
    } catch (e: WebSessionRejectedException) {
        vault.setWebSessionExpired(true)
        block(null)
    }

    private suspend fun loadInfo() {
        runCatching {
            when (val t = target) {
                is PlayTarget.Vod -> helix.videoById(t.videoId)?.let { v ->
                    helixDurationMs = v.durationMs
                    thumbnail = Thumbnails.video(v.thumbnailUrl, 640, 360).orEmpty()
                    _channelId.value = v.userId.ifEmpty { null }
                    _ui.update { it.copy(channelLogin = v.userLogin, channelName = v.userName.ifBlank { v.userLogin }, title = v.title, durationMs = it.durationMs.coerceAtLeast(v.durationMs)) }
                }
                is PlayTarget.Clip -> helix.clipById(t.slug)?.let { c ->
                    thumbnail = c.thumbnailUrl
                    _channelId.value = c.broadcasterId.ifEmpty { null }
                    _ui.update { it.copy(channelName = c.broadcasterName, title = c.title, durationMs = (c.duration * 1000).toLong()) }
                    if (c.broadcasterId.isNotEmpty()) helix.userById(c.broadcasterId)?.let { u -> _ui.update { it.copy(channelLogin = u.login) } }
                }
            }
        }
    }

    private fun knownDuration(): Long {
        val d = player.value?.duration?.takeIf { it != C.TIME_UNSET && it > 0 }
        return d ?: _ui.value.durationMs.takeIf { it > 0 } ?: helixDurationMs
    }

    private fun refreshPosition() {
        val p = player.value ?: return
        _ui.update { it.copy(positionMs = p.currentPosition.coerceAtLeast(0), durationMs = knownDuration()) }
    }

    /** True while the seek bar (controls) or the debug overlay is on screen. */
    private val positionObserved = MutableStateFlow(false)

    /** The shown position refreshes at 1 Hz only while [on]; the resume save keeps its own slow poll either way. */
    fun setPositionObserved(on: Boolean) {
        positionObserved.value = on
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            positionObserved.collectLatest { on ->
                while (true) {
                    if (on) refreshPosition()
                    maybeSaveResume()
                    delay(if (on) 1_000 else 5_000)
                }
            }
        }
    }

    private fun maybeSaveResume() {
        if (target !is PlayTarget.Vod || !resumeDecided) return
        val p = player.value ?: return
        if (!p.isPlaying) return
        val now = clock()
        if (now - lastSavedAt < ResumePolicy.SAVE_EVERY_MS) return
        lastSavedAt = now
        saveResume(p.currentPosition, knownDuration())
    }

    private fun finishedWatching() {
        val id = (target as? PlayTarget.Vod)?.videoId ?: return
        ioScope.launch { resumeDao.delete(id) }
    }

    /** Writes (or removes) the resume entry for the current position. Safe to call after the view model is gone. */
    private fun saveResume(positionMs: Long, durationMs: Long) {
        val id = (target as? PlayTarget.Vod)?.videoId ?: return
        if (!resumeDecided) return // still waiting on "Resume / Start over": keep what is stored
        val ui = _ui.value
        val entity = ResumeEntity(id, positionMs, durationMs, ui.title, thumbnail, ui.channelLogin, clock())
        ioScope.launch {
            when (val a = ResumePolicy.decide(positionMs, durationMs)) {
                is ResumePolicy.Action.Save -> resumeDao.upsert(entity.copy(position = a.positionMs))
                ResumePolicy.Action.Remove -> resumeDao.delete(id)
                ResumePolicy.Action.Keep -> Unit
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
