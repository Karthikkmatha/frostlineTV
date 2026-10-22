package tv.frostline.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.frostline.core.data.TokenVault
import tv.frostline.core.twitch.TokenManager
import tv.frostline.core.twitchinternal.ProbeResult
import tv.frostline.core.twitchinternal.WebSessionValidator

data class AccountUi(
    val login: String? = null,
    val webLinked: Boolean = false,
    val webExpired: Boolean = false,
)

class AccountViewModel(
    private val vault: TokenVault,
    private val tokenManager: TokenManager,
    private val validator: WebSessionValidator,
) : ViewModel() {
    val ui: StateFlow<AccountUi> = combine(vault.tokens, vault.webSession, vault.webSessionExpired) { t, w, e ->
        AccountUi(login = t?.login, webLinked = w != null, webExpired = w != null && e)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AccountUi())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        // Re-check a stored cookie when Settings opens so an expired one is flagged.
        viewModelScope.launch {
            val stored = vault.currentWebSession() ?: return@launch
            if (validator.validate(stored) == ProbeResult.Invalid) vault.setWebSessionExpired(true)
        }
    }

    fun link(input: String) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            when (val r = validator.validate(input)) {
                ProbeResult.Valid -> {
                    vault.saveWebSession(cleanToken(input))
                    _message.value = "Web session linked."
                }
                ProbeResult.Invalid -> _message.value = "Twitch rejected that token (401). Check you copied the auth-token cookie."
                is ProbeResult.Failed -> _message.value = "Could not verify the token (${r.reason}). Try again."
            }
            _busy.value = false
        }
    }

    fun unlink() {
        viewModelScope.launch {
            vault.clearWebSession()
            _message.value = "Web session removed."
        }
    }

    fun signOut() {
        viewModelScope.launch { tokenManager.signOut() }
    }

    private fun cleanToken(s: String) = s.trim().removePrefix("auth-token=").trim('"', '\'', ' ', ';')
}
