package tv.frostline.core.twitch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.frostline.core.data.TokenSet
import java.io.IOException

data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresAtMs: Long,
    val intervalSeconds: Int,
)

sealed interface PollResult {
    data class Success(val tokens: TokenSet) : PollResult
    data object Pending : PollResult
    data object SlowDown : PollResult
    data object Expired : PollResult
    data object Denied : PollResult
    data class Failed(val message: String) : PollResult
}

/** The refresh token was refused (revoked/expired): the user has to sign in again. */
class RefreshRejectedException(message: String) : IOException(message)

@Serializable
private data class DeviceResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String = "https://www.twitch.tv/activate",
    @SerialName("expires_in") val expiresIn: Int = 1800,
    val interval: Int = 5,
)

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 0,
    val scope: List<String> = emptyList(),
)

@Serializable
private data class ErrorResponse(val message: String = "", val error: String = "")

class TwitchOAuth(
    private val client: OkHttpClient,
    private val config: TwitchConfig,
    private val now: () -> Long = System::currentTimeMillis,
    private val json: Json = TwitchJson,
) {
    private data class Raw(val code: Int, val body: String)

    private suspend fun post(path: String, form: FormBody): Raw = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(config.idBaseUrl + path).post(form).build()
        client.newCall(request).execute().use { Raw(it.code, it.body.string()) }
    }

    suspend fun requestDeviceCode(scopes: List<String> = TwitchScopes.ALL): DeviceCode {
        val form = FormBody.Builder()
            .add("client_id", config.clientId)
            .add("scopes", scopes.joinToString(" "))
            .build()
        val raw = post("oauth2/device", form)
        if (raw.code !in 200..299) throw IOException("Device code request failed (HTTP ${raw.code})")
        val r = json.decodeFromString<DeviceResponse>(raw.body)
        return DeviceCode(r.deviceCode, r.userCode, r.verificationUri, now() + r.expiresIn * 1000L, r.interval.coerceAtLeast(1))
    }

    suspend fun pollOnce(deviceCode: String, scopes: List<String> = TwitchScopes.ALL): PollResult {
        val form = FormBody.Builder()
            .add("client_id", config.clientId)
            .add("scopes", scopes.joinToString(" "))
            .add("device_code", deviceCode)
            .add("grant_type", DEVICE_GRANT)
            .build()
        val raw = try {
            post("oauth2/token", form)
        } catch (e: IOException) {
            return PollResult.Failed(e.message ?: "network error")
        }
        if (raw.code in 200..299) {
            val r = runCatching { json.decodeFromString<TokenResponse>(raw.body) }.getOrNull()
                ?: return PollResult.Failed("Unreadable token response")
            return PollResult.Success(r.toTokenSet(previous = null))
        }
        val msg = runCatching { json.decodeFromString<ErrorResponse>(raw.body) }.getOrNull()
            ?.let { it.message.ifEmpty { it.error } }.orEmpty().lowercase()
        return when {
            "authorization_pending" in msg -> PollResult.Pending
            "slow_down" in msg -> PollResult.SlowDown
            "expired" in msg || "invalid device code" in msg -> PollResult.Expired
            "access_denied" in msg || "denied" in msg -> PollResult.Denied
            else -> PollResult.Failed(msg.ifEmpty { "HTTP ${raw.code}" })
        }
    }

    /**
     * Polls until the user approves, the code expires, or something fails. Honors `slow_down` by
     * widening the interval by five seconds. [sleep] is injectable for tests.
     */
    suspend fun awaitAuthorization(
        code: DeviceCode,
        scopes: List<String> = TwitchScopes.ALL,
        sleep: suspend (Long) -> Unit = { delay(it) },
    ): PollResult {
        var intervalMs = code.intervalSeconds * 1000L
        while (now() < code.expiresAtMs) {
            sleep(intervalMs)
            when (val r = pollOnce(code.deviceCode, scopes)) {
                PollResult.Pending -> Unit
                PollResult.SlowDown -> intervalMs += 5_000L
                else -> return r
            }
        }
        return PollResult.Expired
    }

    /**
     * Exchanges [refreshToken] for a new token set. Sends the client secret when configured and retries
     * once without it on a 400. Throws [RefreshRejectedException] when Twitch refuses the token and
     * [IOException] for transient failures.
     */
    suspend fun refresh(refreshToken: String, previous: TokenSet?): TokenSet {
        suspend fun attempt(withSecret: Boolean): Raw {
            val b = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", config.clientId)
            if (withSecret && !config.clientSecret.isNullOrEmpty()) b.add("client_secret", config.clientSecret)
            return post("oauth2/token", b.build())
        }

        val hasSecret = !config.clientSecret.isNullOrEmpty()
        var raw = attempt(withSecret = hasSecret)
        if (raw.code == 400 && hasSecret) raw = attempt(withSecret = false)
        return when {
            raw.code in 200..299 -> {
                val r = runCatching { json.decodeFromString<TokenResponse>(raw.body) }.getOrNull()
                    ?: throw IOException("Unreadable refresh response")
                r.toTokenSet(previous).copy(refreshToken = r.refreshToken ?: refreshToken)
            }
            raw.code == 400 || raw.code == 401 -> throw RefreshRejectedException("Refresh rejected (HTTP ${raw.code})")
            else -> throw IOException("Refresh failed (HTTP ${raw.code})")
        }
    }

    private fun TokenResponse.toTokenSet(previous: TokenSet?) = TokenSet(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAtMs = now() + expiresIn * 1000L,
        scopes = scope.ifEmpty { previous?.scopes.orEmpty() },
        userId = previous?.userId,
        login = previous?.login,
    )

    private companion object {
        const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
    }
}
