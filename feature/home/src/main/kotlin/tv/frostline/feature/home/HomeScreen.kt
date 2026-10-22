package tv.frostline.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val RailItems = listOf(
    RailItem(HomeSection.Following.id, HomeSection.Following.label, R.drawable.ic_following),
    RailItem(HomeSection.Top.id, HomeSection.Top.label, R.drawable.ic_top),
    RailItem(HomeSection.Search.id, HomeSection.Search.label, R.drawable.ic_search),
    RailItem(HomeSection.Settings.id, HomeSection.Settings.label, R.drawable.ic_settings),
)

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenChannel: (String) -> Unit,
    settingsContent: @Composable () -> Unit,
) {
    val section by viewModel.section.collectAsStateWithLifecycle()
    var railFocused by remember { mutableStateOf(false) }
    val railRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    // Back from content goes to the rail first; Back on the rail leaves the app.
    BackHandler(enabled = !railFocused) { runCatching { railRequester.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(start = RailCollapsedWidth)) {
            when (section) {
                HomeSection.Following -> FollowingContent(viewModel, onOpenChannel)
                HomeSection.Top -> TopContent(viewModel, onOpenChannel)
                HomeSection.Search -> SearchContent(viewModel, onOpenChannel)
                HomeSection.Settings -> settingsContent()
            }
        }
        SideRail(
            items = RailItems,
            selectedId = section.id,
            onSelect = { id ->
                val target = HomeSection.byId(id)
                viewModel.select(target)
                if (target == HomeSection.Settings) {
                    scope.launch {
                        delay(120)
                        focusManager.moveFocus(FocusDirection.Right)
                    }
                }
            },
            modifier = Modifier.align(Alignment.CenterStart),
            groupRequester = railRequester,
            onFocusChange = { railFocused = it },
        )
    }
}
