package tv.frostline.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.crypto.KeyGenerator

class TokenVaultTest {
    private lateinit var store: DataStore<Preferences>
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = GcmKeyCipher { key }

    @Before fun setUp() {
        store = InMemoryPreferencesStore()
    }

    private val sample = TokenSet(
        accessToken = "access-PLAINTEXT-123",
        refreshToken = "refresh-PLAINTEXT-456",
        expiresAtMs = 1_700_000_000_000,
        scopes = listOf("chat:read"),
        userId = "42",
        login = "someone",
    )

    @Test fun roundTripsTokens() = runBlocking {
        val vault = TokenVault(store, cipher)
        assertNull(vault.currentTokens())
        vault.saveTokens(sample)
        assertEquals(sample, vault.currentTokens())
    }

    @Test fun storesOnlyCiphertext() = runBlocking {
        val vault = TokenVault(store, cipher)
        vault.saveTokens(sample)
        vault.saveWebSession("cookie-PLAINTEXT-789")
        val prefs = store.data.first().asMap().values.joinToString("|")
        assertFalse(prefs.contains("PLAINTEXT"))
        assertFalse(prefs.contains("someone"))
    }

    @Test fun twoEncryptionsDiffer() {
        val a = cipher.encrypt("same".toByteArray())
        val b = cipher.encrypt("same".toByteArray())
        assertFalse(a.contentEquals(b))
    }

    @Test fun wrongKeyReadsAsAbsent() = runBlocking {
        TokenVault(store, cipher).saveTokens(sample)
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val other = TokenVault(store, GcmKeyCipher { otherKey })
        assertNull(other.currentTokens())
    }

    @Test fun webSessionLifecycle() = runBlocking {
        val vault = TokenVault(store, cipher)
        assertNull(vault.currentWebSession())
        vault.saveWebSession("abc")
        assertEquals("abc", vault.currentWebSession())
        vault.setWebSessionExpired(true)
        assertTrue(vault.webSessionExpired.first())
        vault.saveWebSession("def") // relinking clears the expired flag
        assertFalse(vault.webSessionExpired.first())
        vault.clearWebSession()
        assertNull(vault.currentWebSession())
    }

    @Test fun clearTokensKeepsWebSession() = runBlocking {
        val vault = TokenVault(store, cipher)
        vault.saveTokens(sample)
        vault.saveWebSession("abc")
        vault.clearTokens()
        assertNull(vault.currentTokens())
        assertNotNull(vault.currentWebSession())
    }

    @Test fun tokenToStringHidesSecrets() {
        assertFalse(sample.toString().contains("PLAINTEXT"))
    }
}

class GuestAndSettingsTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun guestFlagActivatesSessionAndSignInClearsIt() = runBlocking {
        val vault = TokenVault(InMemoryPreferencesStore(), GcmKeyCipher { key })
        assertFalse(vault.sessionActive.first())
        vault.setGuest(true)
        assertTrue(vault.sessionActive.first())
        vault.saveTokens(TokenSet("a", "r", 1L))
        assertFalse(vault.guest.first())
        assertTrue(vault.sessionActive.first())
        vault.clearAll()
        assertFalse(vault.sessionActive.first())
    }

    @Test fun settingsDefaultsAndToggles() = runBlocking {
        val s = SettingsStore(InMemoryPreferencesStore())
        assertTrue(s.followRaids.first())
        assertFalse(s.debugOverlay.first())
        s.setFollowRaids(false)
        s.setDebugOverlay(true)
        assertFalse(s.followRaids.first())
        assertTrue(s.debugOverlay.first())
    }
}
