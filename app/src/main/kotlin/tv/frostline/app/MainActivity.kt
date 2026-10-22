package tv.frostline.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import tv.frostline.core.ui.FrostlineTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as FrostlineApp).container
        setContent { FrostlineTheme { AppNav(container) } }
    }
}
