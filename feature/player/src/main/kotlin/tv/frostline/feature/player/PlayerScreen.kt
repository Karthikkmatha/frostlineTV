package tv.frostline.feature.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.frostline.core.ui.FrostColors

/** Stage 1 stub: stage 2 replaces this with the Media3 HLS player. */
@Composable
fun PlayerScreen(channel: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(channel, fontSize = 40.sp, color = FrostColors.OnSurface)
        Text("Player arrives in stage 2. Press Back to return.", fontSize = 14.sp, color = FrostColors.Muted)
    }
}
