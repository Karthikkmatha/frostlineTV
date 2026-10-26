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

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        tv.frostline.core.twitch.NetworkRetry.start(this)
        container.twitch.tokenManager.startBackgroundRefresh(appScope)
        appScope.launch { container.settings.liveMode.collect { tv.frostline.feature.player.PlayerProfile.liveMode = it } }
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
