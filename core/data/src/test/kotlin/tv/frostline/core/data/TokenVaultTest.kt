package tv.frostline.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class TokenVaultTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = GcmKeyCipher { key }

    @Before fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("vault.preferences_pb") }
    }

    @After fun tearDown() = scope.cancel()

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
