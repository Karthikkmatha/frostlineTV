package tv.frostline.core.twitchinternal

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class VodPlaybackTest {
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

    private fun resolver() = TwitchInternal.vodPlayback(gql.url("/gql").toString(), usher.url("/").toString())

    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="chunked",NAME="1080p60 (source)"
        #EXT-X-STREAM-INF:BANDWIDTH=8000000,RESOLUTION=1920x1080,VIDEO="chunked",FRAME-RATE=60.000
        https://edge/chunked.m3u8
        #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="480p30",NAME="480p"
        #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=852x480,VIDEO="480p30",FRAME-RATE=30.000
        https://edge/480.m3u8
    """.trimIndent()

    @Test fun vodUsesTheVideoTokenAndTheUsherVodPlaylist() = runBlocking {
        gql.enqueue(MockResponse().setBody("""{"data":{"videoPlaybackAccessToken":{"value":"{\"vod\":1}","signature":"sigV"}}}"""))
        usher.enqueue(MockResponse().setBody(master))
        val r = resolver().vod("2501", null)
        assertEquals(listOf("1080p60 (Source)", "480p"), r.variants.map { it.label })
        val g = gql.takeRequest()
        val body = g.body.readUtf8()
        assertTrue(body, body.contains("\"operationName\":\"PlaybackAccessToken\"") && body.contains("\"vodID\":\"2501\"") && body.contains("videoPlaybackAccessToken"))
        assertNull(g.getHeader("Authorization"))
        val u = usher.takeRequest()
        assertTrue(u.path!!, u.path!!.startsWith("/vod/2501.m3u8?"))
        assertEquals("sigV", u.requestUrl!!.queryParameter("sig"))
        assertEquals("{\"vod\":1}", u.requestUrl!!.queryParameter("token"))
    }

    @Test fun webSessionIsSentToGqlOnly() = runBlocking {
        gql.enqueue(MockResponse().setBody("""{"data":{"videoPlaybackAccessToken":{"value":"v","signature":"s"}}}"""))
        usher.enqueue(MockResponse().setBody(master))
        resolver().vod("1", "auth-token=SECRET;")
        assertEquals("OAuth SECRET", gql.takeRequest().getHeader("Authorization"))
        assertNull(usher.takeRequest().getHeader("Authorization"))
    }

    @Test fun missingTokenOrForbiddenPlaylistIsUnavailable() = runBlocking {
        gql.enqueue(MockResponse().setBody("""{"data":{"videoPlaybackAccessToken":null}}"""))
        try { resolver().vod("1", null); fail() } catch (e: VodUnavailableException) { }
        gql.enqueue(MockResponse().setBody("""{"data":{"videoPlaybackAccessToken":{"value":"v","signature":"s"}}}"""))
        usher.enqueue(MockResponse().setResponseCode(403))
        try { resolver().vod("1", null); fail() } catch (e: VodUnavailableException) { }
    }

    @Test fun rejectedWebSessionIsReported() = runBlocking {
        gql.enqueue(MockResponse().setResponseCode(401))
        try { resolver().vod("1", "tok"); fail() } catch (e: WebSessionRejectedException) { }
    }

    @Test fun clipQualitiesGetTheSignedUrlAndSortBestFirst() = runBlocking {
        gql.enqueue(
            MockResponse().setBody(
                """{"data":{"clip":{"playbackAccessToken":{"signature":"sg","value":"{\"a\":1}"},"videoQualities":[
                  {"frameRate":30,"quality":"480","sourceURL":"https://clips/480.mp4"},
                  {"frameRate":60,"quality":"1080","sourceURL":"https://clips/1080.mp4"},
                  {"frameRate":30,"quality":"720","sourceURL":""}]}}}""",
            ),
        )
        val c = resolver().clip("SlugName-abc", null)
        assertEquals(listOf("1080p60", "480p"), c.qualities.map { it.label })
        assertTrue(c.qualities[0].url.startsWith("https://clips/1080.mp4?sig=sg&token=%7B%22a%22%3A1%7D"))
        assertTrue(gql.takeRequest().body.readUtf8().contains("\"slug\":\"SlugName-abc\""))
        assertEquals("ClipQuality(1080p60)", c.qualities[0].toString())
    }

    @Test fun deletedClipIsUnavailable() = runBlocking {
        gql.enqueue(MockResponse().setBody("""{"data":{"clip":null}}"""))
        try { resolver().clip("x", null); fail() } catch (e: VodUnavailableException) { }
    }
}
