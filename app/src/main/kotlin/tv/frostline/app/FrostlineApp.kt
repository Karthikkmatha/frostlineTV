package tv.frostline.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class FrostlineApp : Application() {
    lateinit var container: AppContainer
        private set
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.twitch.tokenManager.startBackgroundRefresh(appScope)
    }
}
