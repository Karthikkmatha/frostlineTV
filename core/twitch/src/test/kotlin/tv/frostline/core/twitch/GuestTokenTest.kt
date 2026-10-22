package tv.frostline.core.twitch

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GuestTokenTest {
    private lateinit var server: MockWebServer
    private var clock = 1_000_000L

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() = server.shutdown()

    private fun provider(secret: String? = "test-secret") =
        GuestTokenProvider(OkHttpClient(), Fixtures.config(server, secret), now = { clock })

    private fun appToken(t: String, expires: Int = 3600) =
        """{"access_token":"$t","expires_in":$expires,"token_type":"bearer"}"""

    @Test fun fetchesClientCredentialsAndCaches() = runBlocking {
        server.enqueue(Fixtures.json(appToken("APP1")))
        val p = provider()
        assertEquals("APP1", p.token())
        assertEquals("APP1", p.token())
        assertEquals(1, server.requestCount)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("grant_type=client_credentials"))
        assertTrue(body.contains("client_id=test-client"))
        assertTrue(body.contains("client_secret=test-secret"))
    }

    @Test fun refetchesNearExpiry() = runBlocking {
        server.enqueue(Fixtures.json(appToken("APP1", 3600)))
        server.enqueue(Fixtures.json(appToken("APP2", 3600)))
        val p = provider()
        assertEquals("APP1", p.token())
        clock += 3_600_000L - 30_000L
        assertEquals("APP2", p.token())
    }

    @Test fun noSecretMeansNoToken() = runBlocking {
        assertNull(provider(secret = null).token())
        assertEquals(0, server.requestCount)
    }

    @Test fun refreshAfter401SkipsNewerToken() = runBlocking {
        server.enqueue(Fixtures.json(appToken("APP1")))
        server.enqueue(Fixtures.json(appToken("APP2")))
        val p = provider()
        assertEquals("APP1", p.token())
        assertTrue(p.owns("APP1"))
        assertEquals("APP2", p.refreshAfter401("APP1"))
        assertEquals("APP2", p.refreshAfter401("APP1")) // someone already refreshed
        assertEquals(2, server.requestCount)
        assertFalse(p.owns("APP1"))
    }

    @Test fun failureYieldsNull() = runBlocking {
        server.enqueue(Fixtures.json("""{"status":400,"message":"invalid client secret"}""", 400))
        assertNull(provider().token())
    }

    @Test fun helixUsesAppTokenWhenSignedOutAndSendsNoUserToken() = runBlocking {
        val vault = Fixtures.vault(java.io.File("."))
        val cfg = Fixtures.config(server)
        val http = OkHttpClient()
        val oauth = TwitchOAuth(http, cfg)
        val tm = TokenManager(vault, oauth)
        val api = createHelixApi(cfg, tm, http, GuestTokenProvider(http, cfg))
        server.enqueue(Fixtures.json(appToken("APPX")))
        server.enqueue(Fixtures.json("""{"data":[{"id":"1","game_id":"2","name":"G"}]}"""))
        val games = api.topGames()
        assertEquals(1, games.data.size)
        server.takeRequest() // token request
        assertEquals("Bearer APPX", server.takeRequest().getHeader("Authorization"))
    }
}
