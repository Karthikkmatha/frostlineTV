package tv.frostline.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import tv.frostline.core.data.TokenVault

/** Hosts a [SignInFlow] for the sign-in screen (full flow) or the Settings "Link web session" screen (web only). */
class AuthViewModel(
    private val vault: TokenVault,
    flowFactory: (CoroutineScope) -> SignInFlow,
) : ViewModel() {
    val flow: SignInFlow = flowFactory(viewModelScope)
    val ui: StateFlow<SignInUi> = flow.ui

    init { flow.startDeviceCode() }

    fun webSignIn() = flow.openWebLogin()
    fun newCode() = flow.startDeviceCode()
    fun skipLogin() = flow.skipToAuthorize()
    fun closeWeb() = flow.closeWeb()

    /** "Skip - watch as guest": no sign-in, Helix app token only. */
    fun skipAsGuest(then: () -> Unit) {
        viewModelScope.launch {
            vault.setGuest(true)
            flow.cancel()
            then()
        }
    }

    override fun onCleared() = flow.cancel()
}
