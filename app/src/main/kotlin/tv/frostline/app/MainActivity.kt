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
import tv.frostline.core.ui.FrostlineTheme

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        DeepLinks.handle(intent)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as FrostlineApp).container
        // Test tags double as resource ids, so UiAutomator (baseline profile journeys, benchmarks) can find them.
        // The first frame is the start of "after startup": deferred work (integrity, global emote preload) waits for it plus 2 s.
        window.decorView.doOnPreDraw { it.post { tv.frostline.core.ui.StartupGate.onFirstFrame() } }
        DeepLinks.handle(intent)
        setContent { FrostlineTheme { Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) { AppNav(container) } } }
    }
}
