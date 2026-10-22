package tv.frostline.app

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import tv.frostline.core.ui.FocusRingScope
import tv.frostline.feature.auth.AccountViewModel
import tv.frostline.feature.auth.AuthViewModel
import tv.frostline.feature.auth.SettingsScreen
import tv.frostline.feature.auth.SignInScreen
import tv.frostline.feature.home.HomeScreen
import tv.frostline.feature.home.HomeViewModel
import tv.frostline.feature.player.PlayerScreen

private const val SIGN_IN = "signin"
private const val HOME = "home"
private const val PLAYER = "player/{login}"

@Composable
fun AppNav(container: AppContainer) {
    val start by produceState<String?>(initialValue = null) {
        value = if (container.vault.currentTokens() != null) HOME else SIGN_IN
    }
    start?.let { AppNavHost(container, it) }
}

/** No focus ring while a screen is animating in or out. */
@Composable
private fun AnimatedContentScope.Settled(content: @Composable () -> Unit) {
    val idle = transition.currentState == transition.targetState
    FocusRingScope(idle, content)
}

@Composable
private fun AppNavHost(container: AppContainer, startRoute: String) {
    val nav = rememberNavController()
    val signedIn by container.twitch.tokenManager.signedIn.collectAsStateWithLifecycle(initialValue = startRoute == HOME)

    // Sign out (or a revoked refresh token) clears the vault; send the user back to sign-in.
    LaunchedEffect(signedIn) {
        val current = nav.currentDestination?.route
        if (!signedIn && current != null && current != SIGN_IN) {
            nav.navigate(SIGN_IN) { popUpTo(nav.graph.id) { inclusive = true } }
        }
    }

    NavHost(
        navController = nav,
        startDestination = startRoute,
        enterTransition = { fadeIn(tween(160)) },
        exitTransition = { fadeOut(tween(120)) },
        popEnterTransition = { fadeIn(tween(160)) },
        popExitTransition = { fadeOut(tween(120)) },
    ) {
        composable(SIGN_IN) {
            Settled {
                val vm: AuthViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { AuthViewModel(container.twitch.oauth, container.vault, container.twitch.helix) }
                    },
                )
                SignInScreen(vm) {
                    nav.navigate(HOME) { popUpTo(SIGN_IN) { inclusive = true } }
                }
            }
        }
        composable(HOME) {
            Settled {
                val vm: HomeViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            HomeViewModel(container.twitch.helix, container.database.resumeDao()) { container.userId() }
                        }
                    },
                )
                HomeScreen(
                    viewModel = vm,
                    onOpenChannel = { login -> nav.navigate("player/$login") },
                    settingsContent = {
                        val account: AccountViewModel = viewModel(
                            factory = viewModelFactory {
                                initializer {
                                    AccountViewModel(container.vault, container.twitch.tokenManager, container.webSessionValidator)
                                }
                            },
                        )
                        SettingsScreen(account, versionName = BuildConfig.VERSION_NAME)
                    },
                )
            }
        }
        composable(PLAYER, arguments = listOf(navArgument("login") { type = NavType.StringType })) { entry ->
            Settled {
                PlayerScreen(entry.arguments?.getString("login").orEmpty(), onBack = { nav.popBackStack() })
            }
        }
    }
}
