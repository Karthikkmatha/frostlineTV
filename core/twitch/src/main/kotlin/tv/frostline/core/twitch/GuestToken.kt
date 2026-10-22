package tv.frostline.core.twitch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

@Serializable
private data class AppTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long = 0,
)

/**
 * Helix app access token (client credentials) for guest mode: Top, Categories, Search and playback
 * lookups work without a user. Needs the compiled-in client secret; without one it yields no token.
 * The token is held in memory only and refreshed a minute before expiry or after a 401.
 */
class GuestTokenProvider(
    private val client: OkHttpClient,
    private val config: TwitchConfig,
    private val now: () -> Long = System::currentTimeMillis,
    private val json: Json = TwitchJson,
) {
    private val mutex = Mutex()
    private var token: String? = null
    private var expiresAtMs = 0L

    suspend fun token(): String? = mutex.withLock {
        val t = token
        if (t != null && now() < expiresAtMs - 60_000L) t else fetchLocked()
    }

    /** True when [candidate] is the token this provider handed out. */
    fun owns(candidate: String): Boolean = candidate == token

    suspend fun refreshAfter401(failed: String?): String? = mutex.withLock {
        val current = token
        if (current != null && failed != null && current != failed) current else fetchLocked()
    }

    private suspend fun fetchLocked(): String? {
        val secret = config.clientSecret
        if (secret.isNullOrEmpty()) return null
        val form = FormBody.Builder()
            .add("client_id", config.clientId)
            .add("client_secret", secret)
            .add("grant_type", "client_credentials")
            .build()
        val request = Request.Builder().url(config.idBaseUrl + "oauth2/token").post(form).build()
        return try {
            val (code, body) = withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { it.code to it.body.string() }
            }
            if (code !in 200..299) {
                token = null
                null
            } else {
                val r = json.decodeFromString<AppTokenResponse>(body)
                token = r.accessToken
                expiresAtMs = now() + r.expiresIn * 1000L
                r.accessToken
            }
        } catch (e: IOException) {
            null
        } catch (e: kotlinx.serialization.SerializationException) {
            null
        }
    }
}
