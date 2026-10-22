package tv.frostline.app

import android.content.Context
import tv.frostline.core.data.createDatabase
import tv.frostline.core.data.createSettings
import tv.frostline.feature.auth.AndroidWebCookies
import tv.frostline.feature.player.createPlayerSlot
import tv.frostline.core.data.createVault
import tv.frostline.core.twitch.TwitchConfig
import tv.frostline.core.twitch.TwitchServices
import tv.frostline.core.twitchinternal.TwitchInternal

/** Hand-wired dependency graph; small enough that a DI framework would be noise. */
class AppContainer(context: Context) {
    val vault = createVault(context)
    val database = createDatabase(context)
    val twitch = TwitchServices(
        TwitchConfig(clientId = BuildConfig.TWITCH_CLIENT_ID, clientSecret = BuildConfig.TWITCH_CLIENT_SECRET),
        vault,
    )
    val webSessionValidator = TwitchInternal.webSessionValidator()
    val settings = createSettings(context)
    val webCookies = AndroidWebCookies()
    val playback = TwitchInternal.playback()
    val hermes = TwitchInternal.hermes()

    /** The only place an ExoPlayer is ever created, so two can never be alive at once. */
    val playerSlot = createPlayerSlot(context)

    /** The signed-in user's id, looked up (and cached in the vault) if sign-in could not fetch it. */
    suspend fun userId(): String? {
        val t = vault.currentTokens() ?: return null
        t.userId?.let { return it }
        val me = runCatching { twitch.helix.me() }.getOrNull() ?: return null
        vault.saveTokens(t.copy(userId = me.id, login = me.login))
        return me.id
    }

    fun signInFlow(scope: kotlinx.coroutines.CoroutineScope, webOnly: Boolean) = tv.frostline.feature.auth.SignInFlow(
        oauth = twitch.oauth,
        vault = vault,
        me = { runCatching { twitch.helix.me() }.getOrNull() },
        validator = webSessionValidator,
        cookies = webCookies,
        scope = scope,
        webOnly = webOnly,
    )
}
