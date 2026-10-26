package tv.frostline.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import tv.frostline.feature.chat.ChatImages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FrostlineApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** One bounded Coil loader for the whole app; decodes animated emotes through the platform ImageDecoder. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ChatImages.loader(context)

    /** Home and Top thumbnails are decoded again from the disk cache when needed: let go of them when the app is hidden or memory runs low. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN || level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            runCatching { coil3.SingletonImageLoader.get(this).memoryCache?.clear() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        tv.frostline.core.twitch.NetworkRetry.start(this)
        container.twitch.tokenManager.startBackgroundRefresh(appScope)
        // After the TV wakes (or the app comes back) a long sleep may have let the token run out: look at once.
        appScope.launch { tv.frostline.core.ui.WakeSignal.events.collect { runCatching { container.twitch.tokenManager.checkNow() } } }
        appScope.launch { container.settings.liveMode.collect { tv.frostline.feature.player.PlayerProfile.liveMode = it } }
        // Baseline profile state for the debug overlay (after the first frame plus 2 s).
        appScope.launch {
            tv.frostline.core.ui.StartupGate.awaitIdle()
            runCatching {
                val r = androidx.profileinstaller.ProfileVerifier.getCompilationStatusAsync().get()
                val code = r.profileInstallResultCode
                val verifierCompiled = code == androidx.profileinstaller.ProfileVerifier.CompilationStatus.RESULT_CODE_COMPILED_WITH_PROFILE ||
                    code == androidx.profileinstaller.ProfileVerifier.CompilationStatus.RESULT_CODE_COMPILED_WITH_PROFILE_NON_MATCHING
                val enqueued = code == androidx.profileinstaller.ProfileVerifier.CompilationStatus.RESULT_CODE_PROFILE_ENQUEUED_FOR_COMPILATION
                // ProfileVerifier only knows installs done by the profile installer; the odex file knows everything (also `cmd package compile`).
                val filter = runCatching {
                    val odex = java.io.File(applicationInfo.sourceDir).parentFile?.resolve("oat")?.walkTopDown()?.firstOrNull { it.name == "base.odex" }
                    odex?.inputStream()?.use { ins ->
                        val buf = ByteArray(64 * 1024)
                        var n = 0
                        while (n < buf.size) { val k = ins.read(buf, n, buf.size - n); if (k < 0) break; n += k }
                        tv.frostline.core.ui.ProfileState.filterIn(buf.copyOf(n))
                    }
                }.getOrNull()
                tv.frostline.core.ui.ProfileStatus.set(tv.frostline.core.ui.ProfileState.text(verifierCompiled, enqueued, filter))
            }.onFailure { tv.frostline.core.ui.ProfileStatus.set("none") }
        }
        // With a linked web session, capture the integrity token and keep it fresh (it refreshes itself when it expires).
        // Not needed to show Following: it starts after the first frame plus 2 s.
        appScope.launch {
            tv.frostline.core.ui.StartupGate.awaitIdle()
            while (true) {
                if (container.vault.currentWebSession() != null) runCatching { container.integrity.current() }
                kotlinx.coroutines.delay(60 * 60_000L)
            }
        }
        // App open: download all global emote files and pre-decode the global animations in the background, after the first
        // frame plus 2 s (the engine itself exists at once; chat can ask it for emotes any time).
        val engine = tv.frostline.feature.chat.EmoteEngine.init(this)
        appScope.launch {
            tv.frostline.core.ui.StartupGate.awaitIdle()
            val third = runCatching { container.emoteRepository.forChannel(null) }.getOrNull()
            val twitch = runCatching { container.twitch.helix.globalEmotes() }.getOrDefault(emptyList())
                .map { tv.frostline.feature.chat.Emote(it.id, it.name, tv.frostline.feature.chat.TwitchEmotes.url(it.id), tv.frostline.feature.chat.EmoteProvider.Twitch) }
            engine.preloadGlobals(twitch + (third?.let { it.sevenTv + it.bttv + it.ffz } ?: emptyList()))
        }
    }
}
