package tv.frostline.core.twitchinternal

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class PlaybackResolverTest {
    private lateinit var gql: MockWebServer
    private lateinit var usher: MockWebServer

    @Before fun setUp() {
        gql = MockWebServer().also { it.start() }
        usher = MockWebServer().also { it.start() }
    }

    @After fun tearDown() {
        gql.shutdown()
        usher.shutdown()
    }

    private fun resolver() = TwitchInternal.playback(gql.url("/gql").toString(), usher.url("/").toString())

    private fun tokenReply() = MockResponse().setBody(
        """{"data":{"streamPlaybackAccessToken":{"value":"{\"channel\":\"abc\"}","signature":"sig123"}}}""",
    )

    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="chunked",NAME="1080p60 (source)"
        #EXT-X-STREAM-INF:BANDWIDTH=8000000,RESOLUTION=1920x1080,VIDEO="chunked",FRAME-RATE=60.000
        https://edge/chunked.m3u8
    """.trimIndent()

    @Test fun buildsUsherUrlFromAccessToken() = runBlocking {
        gql.enqueue(tokenReply())
        usher.enqueue(MockResponse().setBody(master))
        val r = resolver().resolve("SomeChannel", null)
        assertEquals(1, r.variants.size)
        val g = gql.takeRequest()
        assertNull(g.getHeader("Authorization"))
        assertTrue(g.body.readUtf8().contains("\"login\":\"somechannel\""))
        val u = usher.takeRequest()
        assertTrue(u.path!!.startsWith("/api/channel/hls/somechannel.m3u8?"))
        assertEquals("sig123", u.requestUrl!!.queryParameter("sig"))
        assertEquals("{\"channel\":\"abc\"}", u.requestUrl!!.queryParameter("token"))
        assertEquals("true", u.requestUrl!!.queryParameter("allow_source"))
        assertTrue(r.masterUrl.contains("sig=sig123"))
    }

    @Test fun lowLatencyAsksForTheFastPlaylistAndStableDoesNot() = runBlocking {
        gql.enqueue(tokenReply()); gql.enqueue(tokenReply())
        usher.enqueue(MockResponse().setBody(master)); usher.enqueue(MockResponse().setBody(master))
        resolver().resolve("ch", null, lowLatency = true)
        assertEquals("true", usher.takeRequest().requestUrl!!.queryParameter("fast_bread"))
        resolver().resolve("ch", null, lowLatency = false)
        assertNull(usher.takeRequest().requestUrl!!.queryParameter("fast_bread"))
    }

    @Test fun stableFallsBackToTheFastPlaylistWhenUsherRefuses() = runBlocking {
        gql.enqueue(tokenReply())
        usher.enqueue(MockResponse().setResponseCode(403))
        usher.enqueue(MockResponse().setBody(master))
        resolver().resolve("ch", null, lowLatency = false)
        assertNull(usher.takeRequest().requestUrl!!.queryParameter("fast_bread"))
        assertEquals("true", usher.takeRequest().requestUrl!!.queryParameter("fast_bread"))
    }

    @Test fun failureNamesParamsButNotTokenOrSig() = runBlocking {
        gql.enqueue(tokenReply())
        usher.enqueue(MockResponse().setResponseCode(403)); usher.enqueue(MockResponse().setResponseCode(403))
        val ex = runCatching { resolver().resolve("ch", null, lowLatency = false) }.exceptionOrNull() as StreamRestrictedException
        assertTrue(ex.message!!.contains("outside Twitch's own apps"))
        assertTrue(ex.detail.contains("HTTP 403") && ex.detail.contains("allow_source"))
        assertFalse(ex.detail.contains("sig,") || ex.detail.contains("token,"))
        Unit
    }

    @Test fun restrictionReasonComesFromTheBodyWhenItSays() {
        assertEquals(StreamRestriction.SubscriberOnly, StreamRestriction.classify("""{"error":"Forbidden","message":"subscriber only"}"""))
        assertEquals(StreamRestriction.Region, StreamRestriction.classify("not available in your region"))
        assertEquals(StreamRestriction.AgeOrMature, StreamRestriction.classify("mature content"))
        assertEquals(StreamRestriction.Unknown, StreamRestriction.classify(""))
        assertTrue(StreamRestriction.SubscriberOnly.message.contains("subscribers only"))
    }

    @Test fun aSubscriberOnlyBodyBecomesTheSubscriberMessage() = runBlocking {
        gql.enqueue(tokenReply())
        usher.enqueue(MockResponse().setResponseCode(403).setBody("subscriber only")); usher.enqueue(MockResponse().setResponseCode(403).setBody("subscriber only"))
        val ex = runCatching { resolver().resolve("ch", null, lowLatency = false) }.exceptionOrNull() as StreamRestrictedException
        assertEquals(StreamRestriction.SubscriberOnly, ex.reason)
        assertFalse(ex.detail.contains("subscriber only"))
        Unit
    }

    @Test fun webSessionGoesToGqlOnlyAsOAuth() = runBlocking {
        gql.enqueue(tokenReply())
        usher.enqueue(MockResponse().setBody(master))
        resolver().resolve("ch", "auth-token=WEBTOKEN;")
        assertEquals("OAuth WEBTOKEN", gql.takeRequest().getHeader("Authorization"))
        assertNull(usher.takeRequest().getHeader("Authorization"))
    }

    @Test fun rejectedWebSessionIsReported() = runBlocking {
        gql.enqueue(MockResponse().setResponseCode(401))
        try {
            resolver().resolve("ch", "bad")
            fail()
        } catch (e: WebSessionRejectedException) {
            // expected
        }
    }

    @Test fun usher404MeansOffline() = runBlocking {
        gql.enqueue(tokenReply())
        usher.enqueue(MockResponse().setResponseCode(404))
        try {
            resolver().resolve("ch", null)
            fail()
        } catch (e: ChannelOfflineException) {
            // expected
        }
    }

    @Test fun nullAccessTokenMeansOffline() = runBlocking {
        gql.enqueue(MockResponse().setBody("""{"data":{"streamPlaybackAccessToken":null}}"""))
        try {
            resolver().resolve("ghost", null)
            fail()
        } catch (e: ChannelOfflineException) {
            // expected
        }
    }
}
