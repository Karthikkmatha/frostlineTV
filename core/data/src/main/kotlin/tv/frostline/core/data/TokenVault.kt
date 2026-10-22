package tv.frostline.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

@Serializable
data class TokenSet(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtMs: Long,
    val scopes: List<String> = emptyList(),
    val userId: String? = null,
    val login: String? = null,
) {
    // Keep secrets out of accidental logs.
    override fun toString(): String = "TokenSet(userId=$userId, login=$login, expiresAtMs=$expiresAtMs)"
}

/**
 * Stores the Twitch OAuth tokens and the user's web-session cookie. Values are encrypted with [cipher]
 * (AES-GCM, Android Keystore key in production) and the ciphertext, base64 encoded, lives in [store].
 * Anything that fails to decrypt (lost key, tampering) reads as "absent".
 */
class TokenVault(
    private val store: DataStore<Preferences>,
    private val cipher: KeyCipher,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    val tokens: Flow<TokenSet?> = store.data.map { prefs ->
        open(prefs[TOKENS])?.let { runCatching { json.decodeFromString<TokenSet>(it) }.getOrNull() }
    }

    val webSession: Flow<String?> = store.data.map { open(it[WEB_SESSION]) }

    val webSessionExpired: Flow<Boolean> = store.data.map { it[WEB_EXPIRED] ?: false }

    suspend fun currentTokens(): TokenSet? = tokens.first()
    suspend fun currentWebSession(): String? = webSession.first()

    suspend fun saveTokens(tokens: TokenSet) {
        val sealed = seal(json.encodeToString(tokens))
        store.edit { it[TOKENS] = sealed }
    }

    suspend fun clearTokens() {
        store.edit { it.remove(TOKENS) }
    }

    suspend fun saveWebSession(authToken: String) {
        val sealed = seal(authToken)
        store.edit {
            it[WEB_SESSION] = sealed
            it[WEB_EXPIRED] = false
        }
    }

    suspend fun clearWebSession() {
        store.edit {
            it.remove(WEB_SESSION)
            it.remove(WEB_EXPIRED)
        }
    }

    suspend fun setWebSessionExpired(expired: Boolean) {
        store.edit { it[WEB_EXPIRED] = expired }
    }

    suspend fun clearAll() {
        store.edit {
            it.remove(TOKENS)
            it.remove(WEB_SESSION)
            it.remove(WEB_EXPIRED)
        }
    }

    private fun seal(plain: String): String =
        Base64.getEncoder().encodeToString(cipher.encrypt(plain.toByteArray(Charsets.UTF_8)))

    private fun open(encoded: String?): String? {
        if (encoded.isNullOrEmpty()) return null
        return runCatching {
            String(cipher.decrypt(Base64.getDecoder().decode(encoded)), Charsets.UTF_8)
        }.getOrNull()
    }

    companion object {
        internal val TOKENS = stringPreferencesKey("tokens_v1")
        internal val WEB_SESSION = stringPreferencesKey("web_session_v1")
        internal val WEB_EXPIRED = booleanPreferencesKey("web_session_expired")
    }
}
