package tv.frostline.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tv.frostline.core.data.TokenVault
import tv.frostline.core.twitch.DeviceCode
import tv.frostline.core.twitch.HelixRepository
import tv.frostline.core.twitch.PollResult
import tv.frostline.core.twitch.TwitchOAuth

sealed interface SignInState {
    data object Loading : SignInState
    data class ShowCode(val code: DeviceCode) : SignInState
    data object Expired : SignInState
    data object Denied : SignInState
    data class Error(val message: String) : SignInState
    data object Done : SignInState
}

class AuthViewModel(
    private val oauth: TwitchOAuth,
    private val vault: TokenVault,
    private val helix: HelixRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<SignInState>(SignInState.Loading)
    val state: StateFlow<SignInState> = _state.asStateFlow()
    private var job: Job? = null

    init { start() }

    /** Requests a fresh code and polls for approval. Also backs the "Get a new code" button. */
    fun start() {
        job?.cancel()
        _state.value = SignInState.Loading
        job = viewModelScope.launch {
            val code = try {
                oauth.requestDeviceCode()
            } catch (e: Exception) {
                _state.value = SignInState.Error(e.message ?: "Could not reach Twitch")
                return@launch
            }
            _state.value = SignInState.ShowCode(code)
            when (val r = oauth.awaitAuthorization(code)) {
                is PollResult.Success -> {
                    vault.saveTokens(r.tokens)
                    // Learn who signed in; the Helix client now picks the new token up from the vault.
                    val withUser = runCatching { helix.me() }.getOrNull()
                        ?.let { r.tokens.copy(userId = it.id, login = it.login) } ?: r.tokens
                    vault.saveTokens(withUser)
                    _state.value = SignInState.Done
                }
                PollResult.Expired -> _state.value = SignInState.Expired
                PollResult.Denied -> _state.value = SignInState.Denied
                is PollResult.Failed -> _state.value = SignInState.Error(r.message)
                else -> Unit
            }
        }
    }
}
