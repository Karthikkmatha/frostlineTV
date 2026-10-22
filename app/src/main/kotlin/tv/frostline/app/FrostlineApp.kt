package tv.frostline.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import tv.frostline.feature.chat.ChatImages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
    }
}
