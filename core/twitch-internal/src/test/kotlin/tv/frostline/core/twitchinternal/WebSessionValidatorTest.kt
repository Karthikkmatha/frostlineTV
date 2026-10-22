package tv.frostline.core.twitchinternal

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WebSessionValidatorTest {
    private lateinit var server: MockWebServer
    private lateinit var validator: WebSessionValidator

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        validator = TwitchInternal.webSessionValidator(server.url("/gql").toString())
    }

    @After fun tearDown() = server.shutdown()

    @Test fun okMeansValidAndSendsOAuthHeader() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":{"streamPlaybackAccessToken":{"value":"v","signature":"s"}}}"""))
        assertEquals(ProbeResult.Valid, validator.validate("  auth-token=abc123;  "))
        val req = server.takeRequest()
        assertEquals("OAuth abc123", req.getHeader("Authorization"))
        assertTrue(req.body.readUtf8().contains("PlaybackAccessToken"))
    }

    @Test fun unauthorizedMeansInvalid() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Unauthorized","status":401}"""))
        assertEquals(ProbeResult.Invalid, validator.validate("bad"))
    }

    @Test fun serverErrorIsFailedNotInvalid() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(validator.validate("whatever") is ProbeResult.Failed)
    }

    @Test fun blankIsInvalidWithoutNetwork() = runBlocking {
        assertEquals(ProbeResult.Invalid, validator.validate("   "))
        assertEquals(0, server.requestCount)
    }
}
