package tv.frostline.core.twitchinternal

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class IntegrityTest {
    private fun token(payload: String) = "v4.public." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())

    @Test fun expiryComesFromTheTokenPayload() {
        val now = 1_700_000_000_000L
        val exp = (now + 3_600_000L) / 1000
        assertEquals(exp * 1000, IntegrityTokens.expiryMs(token("""{"exp":$exp,"x":1}"""), now))
    }

    @Test fun unreadableOrDistantExpiryFallsBackToTwelveHours() {
        val now = 1_700_000_000_000L
        assertEquals(now + IntegrityTokens.DEFAULT_TTL_MS, IntegrityTokens.expiryMs("garbage", now))
        assertEquals(now + IntegrityTokens.DEFAULT_TTL_MS, IntegrityTokens.expiryMs(token("""{"exp":9999999999}"""), now))
    }

    @Test fun credsNeverShowTheirValues() {
        val s = IntegrityCreds("SECRET-TOKEN", "SECRET-DEVICE", "cid", 5).toString()
        assertFalse(s.contains("SECRET"))
    }

    @Test fun integrityErrorsAreRecognised() {
        assertTrue(IntegrityTokens.isIntegrityError(listOf("failed integrity check")))
        assertFalse(IntegrityTokens.isIntegrityError(listOf("service error")))
    }

    private class FakeIntegrity : IntegrityProvider {
        var refreshes = 0
        override suspend fun current(forceRefresh: Boolean): IntegrityCreds {
            if (forceRefresh) refreshes++
            return IntegrityCreds(if (forceRefresh) "tok2" else "tok1", "dev", "cid", Long.MAX_VALUE)
        }
    }

    @Test fun engagementSendsIntegrityHeadersAndRefreshesOnceOnAnIntegrityFailure() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"errors":[{"message":"failed integrity check"}]}"""))
        server.enqueue(MockResponse().setBody("""{"data":{"community":{"id":"9","channel":{"id":"9","self":{"communityPoints":{"balance":120}}}}}}"""))
        server.start()
        val fake = FakeIntegrity()
        val api = TwitchInternal.engagement(server.url("/gql").toString(), fake)
        val info = api.pointsContext("ch", "web-session")
        assertEquals(120L, info.balance)
        val first = server.takeRequest()
        assertEquals("tok1", first.getHeader("Client-Integrity"))
        assertEquals("dev", first.getHeader("X-Device-Id"))
        assertEquals("cid", first.getHeader("Client-Id"))
        assertEquals("OAuth web-session", first.getHeader("Authorization"))
        assertEquals("tok2", server.takeRequest().getHeader("Client-Integrity"))
        assertEquals(1, fake.refreshes)
        server.shutdown()
    }

    @Test fun hermesSubscribeResultIsReadable() {
        assertEquals("ok", HermesProtocol.subscribeResult("""{"type":"subscribeResponse","subscribeResponse":{"result":"ok"}}"""))
        assertTrue(HermesProtocol.subscribeResult("""{"type":"subscribeResponse","subscribeResponse":{"result":"error","error":"unauthorized"}}""").contains("unauthorized"))
    }

    @Test fun capturesAreRateLimitedAndTheFirstIsImmediate() {
        val h = 60 * 60_000L
        assertTrue(IntegrityPolicy.mayCapture(1_000, 0, false, false))
        assertTrue(IntegrityPolicy.mayCapture(1_000, 500, false, false)) // no creds yet: try
        assertFalse(IntegrityPolicy.mayCapture(h / 2, 1, true, false))
        assertTrue(IntegrityPolicy.mayCapture(h + 2, 1, true, false))
        assertFalse(IntegrityPolicy.mayCapture(5 * 60_000L, 1, true, true))
        assertTrue(IntegrityPolicy.mayCapture(11 * 60_000L, 1, true, true))
    }

    @Test fun streamStartAndChannelSwitchHoldTheCaptureForThirtySeconds() {
        IntegrityGate.streamStarting()
        assertFalse(IntegrityGate.mayRun(System.currentTimeMillis() + 5_000))
        IntegrityGate.streamStarted()
        val t = System.currentTimeMillis()
        assertFalse(IntegrityGate.mayRun(t + 29_000))
        assertTrue(IntegrityGate.mayRun(t + 31_000))
    }

    @Test fun cookieCleanupRemovesOnlyWhatTheCaptureAdded() {
        val before = "auth-token=SECRET; unique_id=abc; theme=dark"
        val after = "auth-token=SECRET; unique_id=abc; theme=dark; server_session_id=x; unique_id_durable=d; api_token=t"
        assertEquals(setOf("server_session_id", "api_token"), CookieHygiene.addedByCapture(before, after))
        assertEquals(setOf("auth-token", "unique_id", "theme"), CookieHygiene.names(before))
        assertEquals("SECRET", CookieHygiene.value(before, "auth-token"))
        assertEquals(null, CookieHygiene.value(null, "auth-token"))
        assertEquals(emptySet<String>(), CookieHygiene.addedByCapture(null, null))
    }
}
