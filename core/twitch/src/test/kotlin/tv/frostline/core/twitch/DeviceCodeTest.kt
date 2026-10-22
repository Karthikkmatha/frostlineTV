package tv.frostline.core.twitch

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeviceCodeTest {
    private lateinit var server: MockWebServer
    private var clock = 1_000_000L
    private lateinit var oauth: TwitchOAuth

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        oauth = TwitchOAuth(OkHttpClient(), Fixtures.config(server), now = { clock })
    }

    @After fun tearDown() = server.shutdown()

    private val deviceJson =
        """{"device_code":"dev123","user_code":"ABCDEFGH","verification_uri":"https://www.twitch.tv/activate?device-code=ABCDEFGH","expires_in":1800,"interval":5}"""

    @Test fun requestsDeviceCodeWithAllScopes() = runBlocking {
        server.enqueue(Fixtures.json(deviceJson))
        val code = oauth.requestDeviceCode()
        assertEquals("ABCDEFGH", code.userCode)
        assertEquals(clock + 1_800_000L, code.expiresAtMs)
        val req = server.takeRequest()
        assertEquals("/oauth2/device", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("client_id=test-client"))
        assertTrue(body.contains("user%3Aread%3Afollows"))
        assertTrue(body.contains("channel%3Aread%3Aredemptions"))
    }

    @Test fun pollsThroughPendingAndSlowDownThenSucceeds() = runBlocking {
        server.enqueue(Fixtures.json(deviceJson))
        val code = oauth.requestDeviceCode()
        server.takeRequest()
        server.enqueue(Fixtures.json(Fixtures.PENDING, 400))
        server.enqueue(Fixtures.json("""{"status":400,"message":"slow_down"}""", 400))
        server.enqueue(Fixtures.json(Fixtures.PENDING, 400))
        server.enqueue(Fixtures.json(Fixtures.tokenJson("A1", "R1")))
        val sleeps = mutableListOf<Long>()
        val result = oauth.awaitAuthorization(code, sleep = { sleeps += it; clock += it })
        assertTrue(result is PollResult.Success)
        assertEquals("A1", (result as PollResult.Success).tokens.accessToken)
        assertEquals("R1", result.tokens.refreshToken)
        assertEquals(clock + 0, clock) // sanity
        // 5s, 5s, then widened by slow_down to 10s, 10s
        assertEquals(listOf(5_000L, 5_000L, 10_000L, 10_000L), sleeps)
        val poll = server.takeRequest().body.readUtf8()
        assertTrue(poll.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"))
        assertTrue(poll.contains("device_code=dev123"))
    }

    @Test fun reportsExpiryWhenTimeRunsOut() = runBlocking {
        server.enqueue(Fixtures.json(deviceJson.replace("1800", "12")))
        val code = oauth.requestDeviceCode()
        repeat(5) { server.enqueue(Fixtures.json(Fixtures.PENDING, 400)) }
        val result = oauth.awaitAuthorization(code, sleep = { clock += it })
        assertEquals(PollResult.Expired, result)
    }

    @Test fun denialStopsPolling() = runBlocking {
        server.enqueue(Fixtures.json(deviceJson))
        val code = oauth.requestDeviceCode()
        server.enqueue(Fixtures.json("""{"status":400,"message":"access_denied"}""", 400))
        assertEquals(PollResult.Denied, oauth.awaitAuthorization(code, sleep = { clock += it }))
    }
}
