package tv.frostline.core.twitch

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import tv.frostline.core.data.GcmKeyCipher
import tv.frostline.core.data.TokenVault
import java.io.File
import javax.crypto.KeyGenerator

object Fixtures {
    fun vault(dir: File, name: String = "v.preferences_pb"): TokenVault {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { File(dir, name) }
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return TokenVault(store, GcmKeyCipher { key })
    }

    fun config(server: MockWebServer, secret: String? = "test-secret") = TwitchConfig(
        clientId = "test-client",
        clientSecret = secret,
        idBaseUrl = server.url("/").toString(),
        helixBaseUrl = server.url("/helix/").toString(),
    )

    fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)

    fun tokenJson(access: String = "acc", refresh: String = "ref", expiresIn: Int = 14400) =
        """{"access_token":"$access","refresh_token":"$refresh","expires_in":$expiresIn,"scope":["chat:read"],"token_type":"bearer"}"""

    const val PENDING = """{"status":400,"message":"authorization_pending"}"""
}
