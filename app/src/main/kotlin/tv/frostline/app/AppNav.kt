package tv.frostline.app

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.flow.first
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
import tv.frostline.feature.auth.LinkWebSessionScreen
import tv.frostline.feature.auth.SignInScreen
import tv.frostline.feature.home.HomeScreen
import tv.frostline.feature.home.HomeViewModel
import tv.frostline.feature.chat.ChatViewModel
import tv.frostline.feature.chat.EngagementViewModel
import tv.frostline.feature.channel.ChannelScreen
import tv.frostline.feature.channel.ChannelViewModel
import tv.frostline.feature.channel.asChannelSource
import tv.frostline.feature.chat.ChatReplayViewModel
import tv.frostline.feature.player.PlayTarget
import tv.frostline.feature.player.PlayerScreen
import tv.frostline.feature.player.PlayerViewModel
import tv.frostline.feature.player.VodPlayerScreen
import tv.frostline.feature.player.VodPlayerViewModel

private const val SIGN_IN = "signin"
private const val HOME = "home"
private const val LINK_WEB = "linkweb"
private const val PLAYER = "player/{login}"
private const val CHANNEL = "channel/{login}"
private const val VOD = "vod/{id}"
private const val CLIP = "clip/{slug}"
private const val SHOWCASE = "debug_showcase"

@Composable
fun AppNav(container: AppContainer) {
    val start by produceState<String?>(initialValue = null) {
        value = if (container.vault.sessionActive.first()) HOME else SIGN_IN
    }
    // Live stream previews carry a five-minute cache key; this keeps the key current.
    tv.frostline.core.ui.PreviewClockEffect()
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
    // Signed in or guest. Sign out clears both.
    val signedIn by container.vault.sessionActive.collectAsStateWithLifecycle(initialValue = startRoute == HOME)

    // Sign out (or a revoked refresh token) clears the session; send the user back to sign-in.
    LaunchedEffect(signedIn) {
        val current = nav.currentDestination?.route
        if (!signedIn && current != null && current != SIGN_IN && current != LINK_WEB) {
            nav.navigate(SIGN_IN) { popUpTo(nav.graph.id) { inclusive = true } }
        }
    }

    // The channel page and the player can lead to each other. When the page we want is the one just below on
    // the stack, go back to it instead of stacking a second copy.
    fun openChannel(login: String) {
        val prev = nav.previousBackStackEntry
        if (prev?.destination?.route == CHANNEL && prev.arguments?.getString("login").equals(login, ignoreCase = true)) {
            nav.popBackStack()
        } else {
            nav.navigate("channel/$login")
        }
    }

    fun watchLive(login: String) {
        val prev = nav.previousBackStackEntry
        if (prev?.destination?.route == PLAYER && prev.arguments?.getString("login").equals(login, ignoreCase = true)) {
            nav.popBackStack()
        } else {
            nav.navigate("player/$login") { popUpTo(CHANNEL) { inclusive = true } }
        }
    }

    // Deep link (testing): replace whatever is open with that channel's player, signed in or as a guest.
    val deepLink by DeepLinks.pending.collectAsStateWithLifecycle()
    LaunchedEffect(deepLink, signedIn) {
        val login = deepLink ?: return@LaunchedEffect
        if (!signedIn) return@LaunchedEffect
        DeepLinks.pending.value = null
        nav.navigate("player/$login") { popUpTo(HOME) }
    }

    // Debug links (only reach here with Settings > Debug overlay on). Logged as: action, ok or err.
    LaunchedEffect(Unit) {
        DeepLinks.debugActions.collect { action ->
            val ok = try {
                when (action) {
                    "gc" -> { Runtime.getRuntime().gc(); System.runFinalization(); Runtime.getRuntime().gc(); true }
                    "reconnect" -> { val a = tv.frostline.core.ui.DebugHooks.run("reconnect-irc"); val b = tv.frostline.core.ui.DebugHooks.run("reconnect-hermes"); a || b }
                    "chat-toggle" -> tv.frostline.core.ui.DebugHooks.run("chat-toggle")
                    "refresh-token" -> {
                        val t = container.vault.currentTokens()
                        if (t != null) container.twitch.tokenManager.refreshAfter401(t.accessToken)
                        container.vault.currentWebSession()?.let { container.webSessionValidator.validate(it) }
                        true
                    }
                    "refresh-integrity" -> container.integrity.current(forceRefresh = true) != null
                    "open-vod" -> {
                        val login = nav.currentBackStackEntry?.arguments?.getString("login")
                        val id = login?.let { container.twitch.helix.userByLogin(it)?.id }
                        val vod = id?.let { container.twitch.helix.videos(it, tv.frostline.core.twitch.VideoKind.Broadcasts).items.firstOrNull() }
                        if (vod != null) { nav.navigate("vod/${vod.id}"); true } else false
                    }
                    "stats" -> { android.util.Log.i("FrostlineDebug", tv.frostline.core.ui.HealthStats.statsLogLine()); true }
                    "back" -> if (nav.currentDestination?.route == VOD) nav.popBackStack() else false
                    "showcase" -> { nav.navigate(SHOWCASE); true }
                    else -> false
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                false
            }
            android.util.Log.i("FrostlineDebug", "$action ${if (ok) "ok" else "err"}")
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
                        initializer { AuthViewModel(container.vault) { scope -> container.signInFlow(scope, webOnly = false) } }
                    },
                )
                val toHome = { nav.navigate(HOME) { popUpTo(nav.graph.id) { inclusive = true } }; Unit }
                SignInScreen(vm, onSignedIn = toHome, onGuest = toHome)
            }
        }
        composable(LINK_WEB) {
            Settled {
                val vm: AuthViewModel = viewModel(
                    key = "linkweb",
                    factory = viewModelFactory {
                        initializer { AuthViewModel(container.vault) { scope -> container.signInFlow(scope, webOnly = true) } }
                    },
                )
                LinkWebSessionScreen(vm, onDone = { nav.popBackStack() })
            }
        }
        composable(HOME) {
            Settled {
                val vm: HomeViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            HomeViewModel(container.twitch.helix, container.database.resumeDao(), container.settings) { container.userId() }
                        }
                    },
                )
                HomeScreen(
                    viewModel = vm,
                    onOpenChannel = { login -> nav.navigate("player/$login") },
                    onOpenChannelPage = { login -> nav.navigate("channel/$login") },
                    onOpenVod = { id -> nav.navigate("vod/$id") },
                    settingsContent = { firstFocus ->
                        val account: AccountViewModel = viewModel(
                            factory = viewModelFactory {
                                initializer {
                                    AccountViewModel(container.vault, container.twitch.tokenManager, container.webSessionValidator, container.settings, container.cacheControl)
                                }
                            },
                        )
                        SettingsScreen(
                            account,
                            versionName = BuildConfig.VERSION_NAME,
                            commit = BuildConfig.GIT_HASH,
                            onLinkWebSession = { nav.navigate(LINK_WEB) },
                            onSignIn = { nav.navigate(SIGN_IN) },
                            firstFocus = firstFocus,
                        )
                    },
                )
            }
        }
        composable(PLAYER, arguments = listOf(navArgument("login") { type = NavType.StringType })) { entry ->
            val login = entry.arguments?.getString("login").orEmpty()
            Settled {
                val vm: PlayerViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            PlayerViewModel(
                                login, container.playerSlot, container.playback, container.twitch.helix,
                                container.vault, container.settings, container.hermes,
                            )
                        }
                    },
                )
                val chatVm: ChatViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            ChatViewModel(
                                login, container::ircSession, container.emoteRepository,
                                container.twitch.helix, container.vault, container.settings,
                            )
                        }
                    },
                )
                val engagementVm: EngagementViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            EngagementViewModel(
                                login, container.engagement, container.hermes, container.vault,
                                container.twitch.helix, container.settings,
                            )
                        }
                    },
                )
                PlayerScreen(vm, chatVm, onBack = { nav.popBackStack() }, onOpenChannel = ::openChannel, onSignIn = { nav.navigate(SIGN_IN) }, engagement = engagementVm)
            }
        }
        composable(CHANNEL, arguments = listOf(navArgument("login") { type = NavType.StringType })) { entry ->
            val login = entry.arguments?.getString("login").orEmpty()
            Settled {
                val vm: ChannelViewModel = viewModel(
                    factory = viewModelFactory { initializer { ChannelViewModel(login, container.twitch.helix.asChannelSource()) } },
                )
                ChannelScreen(
                    vm,
                    onWatchLive = ::watchLive,
                    onOpenVod = { id -> nav.navigate("vod/$id") },
                    onOpenClip = { slug -> nav.navigate("clip/$slug") },
                )
            }
        }
        composable(VOD, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            Settled {
                val vm: VodPlayerViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            VodPlayerViewModel(
                                PlayTarget.Vod(id), container.playerSlot, container.vodPlayback, container.twitch.helix,
                                container.vault, container.settings, container.database.resumeDao(), container.ioScope,
                            )
                        }
                    },
                )
                val chat: ChatReplayViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            ChatReplayViewModel(
                                id, container.emoteRepository, container.twitch.helix, container.settings,
                                position = { vm.replayPosition() }, api = container.videoComments,
                            )
                        }
                    },
                )
                VodPlayerScreen(vm, chat, onBack = { nav.popBackStack() }, onOpenChannel = ::openChannel)
            }
        }
        composable(SHOWCASE) { Settled { tv.frostline.core.ui.ShowcaseScreen() } }
        composable(CLIP, arguments = listOf(navArgument("slug") { type = NavType.StringType })) { entry ->
            val slug = entry.arguments?.getString("slug").orEmpty()
            Settled {
                val vm: VodPlayerViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            VodPlayerViewModel(
                                PlayTarget.Clip(slug), container.playerSlot, container.vodPlayback, container.twitch.helix,
                                container.vault, container.settings, container.database.resumeDao(), container.ioScope,
                            )
                        }
                    },
                )
                VodPlayerScreen(vm, chat = null, onBack = { nav.popBackStack() }, onOpenChannel = ::openChannel)
            }
        }
    }
}
