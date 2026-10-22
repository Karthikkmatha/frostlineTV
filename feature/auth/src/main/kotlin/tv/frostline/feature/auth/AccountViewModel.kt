package tv.frostline.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.frostline.core.data.ChatSettings
import tv.frostline.core.data.SettingsStore
import tv.frostline.core.data.TokenVault
import tv.frostline.core.twitch.TokenManager
import tv.frostline.core.twitchinternal.ProbeResult
import tv.frostline.core.twitchinternal.WebSessionValidator

data class AccountUi(
    val login: String? = null,
    val signedIn: Boolean = false,
    val guest: Boolean = false,
    val webLinked: Boolean = false,
    val webExpired: Boolean = false,
    val followRaids: Boolean = true,
    val debugOverlay: Boolean = false,
    val showFps: Boolean = false,
    val autoClaimPoints: Boolean = true,
    val chat: ChatSettings = ChatSettings(),
)

class AccountViewModel(
    private val vault: TokenVault,
    private val tokenManager: TokenManager,
    private val validator: WebSessionValidator,
    private val settings: SettingsStore,
) : ViewModel() {
    private val account = combine(vault.tokens, vault.webSession, vault.webSessionExpired, vault.guest) { t, w, e, g ->
        AccountUi(login = t?.login, signedIn = t != null, guest = g && t == null, webLinked = w != null, webExpired = w != null && e)
    }

    val ui: StateFlow<AccountUi> = combine(account, settings.followRaids, settings.debugOverlay, settings.chat) { a, raids, dbg, chat ->
        a.copy(followRaids = raids, debugOverlay = dbg, chat = chat)
    }.combine(settings.showFps) { ui, fps -> ui.copy(showFps = fps) }
        .combine(settings.autoClaimPoints) { ui, claim -> ui.copy(autoClaimPoints = claim) }.stateIn(viewModelScope, SharingStarted.Eagerly, AccountUi())

    init {
        // Re-check a stored web session when Settings opens so an expired one is flagged.
        viewModelScope.launch {
            val stored = vault.currentWebSession() ?: return@launch
            if (validator.validate(stored) == ProbeResult.Invalid) vault.setWebSessionExpired(true)
        }
    }

    fun unlinkWebSession() {
        viewModelScope.launch { vault.clearWebSession() }
    }

    /** The app watches the vault and navigates to sign-in once the session is gone. */
    fun signOut() {
        viewModelScope.launch { tokenManager.signOut() }
    }

    fun setFollowRaids(value: Boolean) {
        viewModelScope.launch { settings.setFollowRaids(value) }
    }

    fun updateChat(transform: (ChatSettings) -> ChatSettings) {
        viewModelScope.launch { settings.updateChat(transform) }
    }

    fun setAutoClaimPoints(value: Boolean) {
        viewModelScope.launch { settings.setAutoClaimPoints(value) }
    }

    fun setShowFps(value: Boolean) {
        viewModelScope.launch { settings.setShowFps(value) }
    }

    fun setDebugOverlay(value: Boolean) {
        viewModelScope.launch { settings.setDebugOverlay(value) }
    }
}
