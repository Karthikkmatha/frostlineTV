package tv.frostline.core.twitch

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.frostline.core.data.TokenSet

class RefreshTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private var clock = 10_000_000L

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() = server.shutdown()

    private fun oauth(secret: String? = "s3cret") =
        TwitchOAuth(OkHttpClient(), Fixtures.config(server, secret), now = { clock })

    private val previous = TokenSet("old", "oldref", 0, listOf("chat:read"), userId = "7", login = "me")

    @Test fun refreshSendsSecretAndKeepsIdentity() = runBlocking {
        server.enqueue(Fixtures.json(Fixtures.tokenJson("new", "newref", 3600)))
        val t = oauth().refresh("oldref", previous)
        assertEquals("new", t.accessToken)
        assertEquals("newref", t.refreshToken)
        assertEquals(clock + 3_600_000L, t.expiresAtMs)
        assertEquals("7", t.userId)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("grant_type=refresh_token"))
        assertTrue(body.contains("client_secret=s3cret"))
    }

    @Test fun retriesOnceWithoutSecretOn400() = runBlocking {
        server.enqueue(Fixtures.json("""{"status":400,"message":"invalid client secret"}""", 400))
        server.enqueue(Fixtures.json(Fixtures.tokenJson("new2", "ref2")))
        val t = oauth().refresh("oldref", previous)
        assertEquals("new2", t.accessToken)
        assertEquals(2, server.requestCount)
        assertTrue(server.takeRequest().body.readUtf8().contains("client_secret"))
        assertFalse(server.takeRequest().body.readUtf8().contains("client_secret"))
    }

    @Test fun rejectedAfterRetryThrows() = runBlocking {
        server.enqueue(Fixtures.json("""{"status":400,"message":"Invalid refresh token"}""", 400))
        server.enqueue(Fixtures.json("""{"status":400,"message":"Invalid refresh token"}""", 400))
        try {
            oauth().refresh("oldref", previous)
            fail("expected rejection")
        } catch (_: RefreshRejectedException) {
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun noSecretMeansSingleAttempt() = runBlocking {
        server.enqueue(Fixtures.json("""{"status":400,"message":"nope"}""", 400))
        try {
            oauth(secret = null).refresh("oldref", previous)
            fail()
        } catch (_: RefreshRejectedException) {
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun tokenManagerRefreshesTenMinutesBeforeExpiry() = runBlocking {
        val vault = Fixtures.vault(tmp.root)
        vault.saveTokens(TokenSet("stale", "r", clock + 9 * 60_000L, userId = "7"))
        server.enqueue(Fixtures.json(Fixtures.tokenJson("fresh", "r2")))
        val mgr = TokenManager(vault, oauth(), now = { clock })
        assertEquals("fresh", mgr.accessToken())
        assertEquals("fresh", vault.currentTokens()?.accessToken)
        assertEquals("7", vault.currentTokens()?.userId)
    }

    @Test fun tokenManagerLeavesFreshTokenAlone() = runBlocking {
        val vault = Fixtures.vault(tmp.root)
        vault.saveTokens(TokenSet("good", "r", clock + 30 * 60_000L))
        val mgr = TokenManager(vault, oauth(), now = { clock })
        assertEquals("good", mgr.accessToken())
        assertEquals(0, server.requestCount)
    }

    @Test fun rejectedRefreshSignsOut() = runBlocking {
        val vault = Fixtures.vault(tmp.root)
        vault.saveTokens(TokenSet("x", "r", clock - 1))
        server.enqueue(Fixtures.json("""{"status":400,"message":"bad"}""", 400))
        server.enqueue(Fixtures.json("""{"status":400,"message":"bad"}""", 400))
        val mgr = TokenManager(vault, oauth(), now = { clock })
        assertNull(mgr.accessToken())
        assertNull(vault.currentTokens())
    }

    @Test fun refreshesOnceOn401AndRetries() = runBlocking {
        val vault = Fixtures.vault(tmp.root)
        vault.saveTokens(TokenSet("expired-on-server", "r", clock + 3_600_000L, userId = "7"))
        val mgr = TokenManager(vault, oauth(), now = { clock })
        val api = createHelixApi(Fixtures.config(server), mgr)
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"status":401,"message":"Invalid OAuth token"}"""))
        server.enqueue(Fixtures.json(Fixtures.tokenJson("renewed", "r2")))
        server.enqueue(Fixtures.json("""{"data":[{"id":"7","login":"me","display_name":"Me"}]}"""))
        val users = api.users()
        assertEquals("me", users.data.single().login)
        assertEquals("Bearer expired-on-server", server.takeRequest().getHeader("Authorization"))
        assertEquals("/oauth2/token", server.takeRequest().path)
        val retry = server.takeRequest()
        assertEquals("Bearer renewed", retry.getHeader("Authorization"))
        assertEquals("test-client", retry.getHeader("Client-Id"))
    }

    @Test fun refreshLoopWakesOftenAndBacksOffOnFailure() {
        val lead = 10 * 60_000L
        assertEquals(5 * 60_000L, TokenManager.nextWaitMs(clock + 3 * 3_600_000L, clock, lead, 0)) // never sleeps longer than 5 min
        assertEquals(5_000L, TokenManager.nextWaitMs(clock + 60_000L, clock, lead, 0)) // inside the lead window: soon
        assertEquals(5_000L, TokenManager.nextWaitMs(clock - 1, clock, lead, 0)) // expired while asleep
        assertEquals(5_000L, TokenManager.nextWaitMs(clock, clock, lead, 1))
        assertEquals(10_000L, TokenManager.nextWaitMs(clock, clock, lead, 2))
        assertEquals(5 * 60_000L, TokenManager.nextWaitMs(clock, clock, lead, 20))
    }

    @Test fun checkNowRefreshesAnExpiredTokenAfterSleep() = runBlocking {
        val vault = Fixtures.vault(tmp.root)
        vault.saveTokens(TokenSet("old", "r", clock - 60_000L, userId = "7"))
        server.enqueue(Fixtures.json(Fixtures.tokenJson("woke", "r2")))
        TokenManager(vault, oauth(), now = { clock }).checkNow()
        assertEquals("woke", vault.currentTokens()?.accessToken)
    }
}
