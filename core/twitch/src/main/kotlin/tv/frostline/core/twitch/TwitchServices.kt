package tv.frostline.core.twitch

import okhttp3.OkHttpClient
import tv.frostline.core.data.TokenVault
import java.util.concurrent.TimeUnit

/** Wires OAuth, the token manager and Helix together over one OkHttp client. */
class TwitchServices(config: TwitchConfig, vault: TokenVault) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    val oauth = TwitchOAuth(http, config)
    val tokenManager = TokenManager(vault, oauth)
    val helix = HelixRepository(createHelixApi(config, tokenManager, http))
}
