package tv.frostline.app

import android.content.Context
import tv.frostline.core.data.createDatabase
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

    /** The signed-in user's id, looked up (and cached in the vault) if sign-in could not fetch it. */
    suspend fun userId(): String? {
        val t = vault.currentTokens() ?: return null
        t.userId?.let { return it }
        val me = runCatching { twitch.helix.me() }.getOrNull() ?: return null
        vault.saveTokens(t.copy(userId = me.id, login = me.login))
        return me.id
    }
}
