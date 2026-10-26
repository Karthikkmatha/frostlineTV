package tv.frostline.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.doOnPreDraw
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import tv.frostline.core.ui.FrostlineTheme

class MainActivity : ComponentActivity() {
    // Screen on / user present after the screensaver or sleep: sockets and timers may have gone stale meanwhile.
    private val wakeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            tv.frostline.core.ui.WakeSignal.fire(intent?.action.orEmpty())
        }
    }
    private var wakeRegistered = false

    override fun onStart() {
        super.onStart()
        if (!wakeRegistered) {
            val f = android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_SCREEN_ON)
                addAction(android.content.Intent.ACTION_USER_PRESENT)
            }
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(wakeReceiver, f, android.content.Context.RECEIVER_NOT_EXPORTED)
                else registerReceiver(wakeReceiver, f)
                wakeRegistered = true
            }
        }
        tv.frostline.core.ui.WakeSignal.fire("start")
    }

    override fun onStop() {
        if (wakeRegistered) { runCatching { unregisterReceiver(wakeReceiver) }; wakeRegistered = false }
        super.onStop()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        DeepLinks.handle(intent, lifecycleScope) { (application as FrostlineApp).container.settings.debugOverlay.first() }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as FrostlineApp).container
        // Test tags double as resource ids, so UiAutomator (baseline profile journeys, benchmarks) can find them.
        // The first frame is the start of "after startup": deferred work (integrity, global emote preload) waits for it plus 2 s.
        window.decorView.doOnPreDraw { it.post { tv.frostline.core.ui.StartupGate.onFirstFrame() } }
        DeepLinks.handle(intent, lifecycleScope) { container.settings.debugOverlay.first() }
        setContent { FrostlineTheme { Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) { AppNav(container) } } }
    }
}
