package tv.frostline.feature.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tv.frostline.core.data.TokenVault
import tv.frostline.core.twitch.DeviceCode
import tv.frostline.core.twitch.HelixUser
import tv.frostline.core.twitch.PollResult
import tv.frostline.core.twitch.TwitchOAuth
import tv.frostline.core.twitchinternal.ProbeResult
import tv.frostline.core.twitchinternal.WebSessionValidator

/** The slice of the platform WebView the sign-in logic needs, so the logic runs in plain unit tests. */
interface WebCookies {
    /** The `auth-token` cookie of twitch.tv if present. Never log it. */
    fun authToken(): String?

    /** Wipes WebView cookies and site data. */
    fun clearAll()
}

enum class Step { Landing, WebLogin, WebAuthorize, Done }

sealed interface CodeStatus {
    data object Loading : CodeStatus
    data object Ready : CodeStatus
    data object Expired : CodeStatus
    data object Denied : CodeStatus
    data class Error(val message: String) : CodeStatus
}

data class SignInUi(
    val step: Step = Step.Landing,
    val code: DeviceCode? = null,
    val codeStatus: CodeStatus = CodeStatus.Loading,
    val webSessionLinked: Boolean = false,
    /** URL the WebView should show for the current step (login page, then the activation page). */
    val webUrl: String? = null,
    val message: String? = null,
)

/**
 * Sign-in orchestration. Primary path: WebView login (reads the `auth-token` cookie), then the device-code
 * activation page in the same WebView. Fallback: the same device code shown as QR + text for a phone.
 * [webOnly] re-runs just the WebView login (Settings > Link web session). The password is never seen.
 */
class SignInFlow(
    private val oauth: TwitchOAuth,
    private val vault: TokenVault,
    private val me: suspend () -> HelixUser?,
    private val validator: WebSessionValidator,
    private val cookies: WebCookies,
    private val scope: CoroutineScope,
    val webOnly: Boolean = false,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val cookiePollMs: Long = 1_000L,
) {
    private val _ui = MutableStateFlow(SignInUi())
    val ui: StateFlow<SignInUi> = _ui.asStateFlow()
    private var pollJob: Job? = null
    private var cookieJob: Job? = null

    /** Requests a device code (also backs "Get a new code") and starts polling for approval. */
    fun startDeviceCode() {
        if (webOnly) return
        pollJob?.cancel()
        _ui.update { it.copy(code = null, codeStatus = CodeStatus.Loading) }
        pollJob = scope.launch {
            val code = try {
                oauth.requestDeviceCode()
            } catch (e: Exception) {
                _ui.update { it.copy(codeStatus = CodeStatus.Error(e.message ?: "Could not reach Twitch")) }
                return@launch
            }
            _ui.update { it.copy(code = code, codeStatus = CodeStatus.Ready) }
            when (val r = oauth.awaitAuthorization(code, sleep = sleep)) {
                is PollResult.Success -> finishWithTokens(r)
                PollResult.Expired -> _ui.update { it.copy(codeStatus = CodeStatus.Expired) }
                PollResult.Denied -> _ui.update { it.copy(codeStatus = CodeStatus.Denied) }
                is PollResult.Failed -> _ui.update { it.copy(codeStatus = CodeStatus.Error(r.message)) }
                else -> Unit
            }
        }
    }

    private suspend fun finishWithTokens(r: PollResult.Success) {
        vault.saveTokens(r.tokens)
        // Learn who signed in; Helix now reads the new token from the vault.
        val user = runCatching { me() }.getOrNull()
        if (user != null) vault.saveTokens(r.tokens.copy(userId = user.id, login = user.login))
        cookieJob?.cancel()
        cookies.clearAll()
        _ui.update { it.copy(step = Step.Done, webUrl = null) }
    }

    fun openWebLogin() {
        _ui.update { it.copy(step = Step.WebLogin, webUrl = LOGIN_URL, message = null) }
        cookieJob?.cancel()
        cookieJob = scope.launch {
            while (isActive && _ui.value.step == Step.WebLogin) {
                if (checkCookie()) break
                delay(cookiePollMs)
            }
        }
    }

    /**
     * Looks for the login cookie and, if it is a valid session, stores it in the vault and moves on.
     * Returns true when the WebView login step is finished.
     */
    suspend fun checkCookie(): Boolean {
        if (_ui.value.step != Step.WebLogin) return true
        val token = cookies.authToken()?.takeIf { it.isNotBlank() } ?: return false
        if (validator.validate(token) != ProbeResult.Valid) return false
        vault.saveWebSession(token)
        _ui.update { it.copy(webSessionLinked = true) }
        if (webOnly) {
            cookies.clearAll()
            _ui.update { it.copy(step = Step.Done, webUrl = null, message = "Web session linked.") }
        } else {
            beginAuthorize()
        }
        return true
    }

    /** Login failed or was skipped (captcha, blocked): go straight to the activation page. */
    fun skipToAuthorize() {
        if (webOnly) return
        cookieJob?.cancel()
        scope.launch { beginAuthorize() }
    }

    private suspend fun beginAuthorize() {
        var code = _ui.value.code
        if (code == null || code.expiresAtMs - now() < MIN_CODE_LIFE_MS || _ui.value.codeStatus != CodeStatus.Ready) {
            startDeviceCode()
            // Wait for the fresh code; the polling job publishes it.
            while (_ui.value.codeStatus == CodeStatus.Loading) delay(50)
            code = _ui.value.code
        }
        if (code == null) {
            _ui.update { it.copy(step = Step.Landing, webUrl = null, message = "Could not get a sign-in code.") }
            return
        }
        _ui.update { it.copy(step = Step.WebAuthorize, webUrl = code.verificationUri) }
    }

    /** Leaves the WebView (Back / Cancel) and wipes whatever it stored. */
    fun closeWeb() {
        cookieJob?.cancel()
        cookies.clearAll()
        if (_ui.value.step != Step.Done) _ui.update { it.copy(step = Step.Landing, webUrl = null) }
    }

    fun cancel() {
        pollJob?.cancel()
        cookieJob?.cancel()
    }

    companion object {
        const val LOGIN_URL = "https://www.twitch.tv/login"
        private const val MIN_CODE_LIFE_MS = 120_000L
    }
}
