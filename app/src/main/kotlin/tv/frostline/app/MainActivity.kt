package tv.frostline.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import tv.frostline.core.ui.FrostlineTheme

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as FrostlineApp).container
        // Test tags double as resource ids, so UiAutomator (baseline profile journeys, benchmarks) can find them.
        setContent { FrostlineTheme { Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) { AppNav(container) } } }
    }
}
