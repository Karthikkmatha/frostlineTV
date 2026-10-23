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
        container.twitch.tokenManager.startBackgroundRefresh(appScope)
        // App open: download all global emote files and pre-decode the global animations in the background.
        val engine = tv.frostline.feature.chat.EmoteEngine.init(this)
        appScope.launch {
            val third = runCatching { container.emoteRepository.forChannel(null) }.getOrNull()
            val twitch = runCatching { container.twitch.helix.globalEmotes() }.getOrDefault(emptyList())
                .map { tv.frostline.feature.chat.Emote(it.id, it.name, tv.frostline.feature.chat.TwitchEmotes.url(it.id), tv.frostline.feature.chat.EmoteProvider.Twitch) }
            engine.preloadGlobals(twitch + (third?.let { it.sevenTv + it.bttv + it.ffz } ?: emptyList()))
        }
    }
}
