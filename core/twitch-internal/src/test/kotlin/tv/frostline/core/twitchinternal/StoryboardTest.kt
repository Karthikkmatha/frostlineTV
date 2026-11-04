package tv.frostline.core.twitchinternal

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StoryboardTest {
    private val info = "https://cdn.example.net/abc_chan_123/storyboards/2501-info.json"

    private val sample = """
        [
          {"count":100,"width":320,"height":180,"quality":"high","rows":10,"cols":10,"interval":5,"images":["2501-high-0.jpg","2501-high-1.jpg"]},
          {"count":250,"width":160,"height":90,"quality":"low","rows":10,"cols":10,"interval":"10","images":["2501-low-0.jpg","2501-low-1.jpg","2501-low-2.jpg"]}
        ]
    """.trimIndent()

    @Test fun picksTheLowQualitySheetsAndResolvesTheNamesNextToTheInfoFile() {
        val s = Storyboards.parse(sample, info)!!
        assertEquals("low", s.quality)
        assertEquals(160, s.frameWidth)
        assertEquals(90, s.frameHeight)
        assertEquals(10.0, s.intervalSec, 0.0)
        assertEquals("https://cdn.example.net/abc_chan_123/storyboards/2501-low-1.jpg", s.images[1])
        assertEquals(250, s.totalFrames)
    }

    @Test fun frameIndexIsOneFramePerIntervalAndClamps() {
        val s = Storyboards.parse(sample, info)!!
        assertEquals(0, s.frameIndex(0))
        assertEquals(0, s.frameIndex(9_999))
        assertEquals(1, s.frameIndex(10_000))
        assertEquals(99, s.frameIndex(999_000))
        assertEquals(100, s.frameIndex(1_000_000))
        assertEquals(249, s.frameIndex(50_000_000)) // past the end: the last frame
        assertEquals(0, s.frameIndex(-5))
    }

    @Test fun sheetAndCellOfAFrame() {
        val s = Storyboards.parse(sample, info)!!
        assertEquals(0, s.sheetOf(99)); assertEquals(1, s.sheetOf(100)); assertEquals(2, s.sheetOf(249))
        assertEquals(0 to 0, s.cellOf(0))
        assertEquals(9 to 0, s.cellOf(9))
        assertEquals(0 to 1, s.cellOf(10))
        assertEquals(9 to 9, s.cellOf(99))
        assertEquals(0 to 0, s.cellOf(100))
        assertEquals(9 to 4, s.cellOf(249))
    }

    @Test fun withoutALowEntryTheSmallestFramesWin() {
        val s = Storyboards.parse("""[{"width":320,"height":180,"rows":2,"cols":2,"interval":4,"count":4,"images":["a.jpg"]},{"width":100,"height":56,"rows":2,"cols":2,"interval":4,"count":4,"images":["b.jpg"]}]""", info)!!
        assertTrue(s.images[0].endsWith("/b.jpg"))
    }

    @Test fun aSingleShortSheetOnlyHasItsCountedFrames() {
        val s = Storyboards.parse("""[{"width":100,"height":56,"rows":10,"cols":10,"interval":2,"count":37,"images":["only.jpg"]}]""", info)!!
        assertEquals(37, s.totalFrames)
        assertEquals(36, s.frameIndex(900_000))
    }

    @Test fun aMissingIntervalComesFromTheDuration() {
        val s = Storyboards.parse("""[{"width":100,"height":56,"rows":10,"cols":10,"count":100,"images":["a.jpg"]}]""", info, durationSec = 1_000)!!
        assertEquals(10.0, s.intervalSec, 0.0)
        assertNull(Storyboards.parse("""[{"width":100,"height":56,"rows":10,"cols":10,"count":100,"images":["a.jpg"]}]""", info))
    }

    @Test fun absoluteAndRootRelativeImagesAreKept() {
        assertEquals("https://x.y/z.jpg", Storyboards.absolute(info, "https://x.y/z.jpg"))
        assertEquals("https://cdn.example.net/root/z.jpg", Storyboards.absolute(info, "/root/z.jpg"))
        assertEquals("https://cdn.example.net/abc_chan_123/storyboards/z.jpg", Storyboards.absolute("$info?x=1", "z.jpg"))
    }

    @Test fun garbageNeverThrowsAndGivesNoStoryboard() {
        for (bad in listOf("", "null", "{}", "[]", "[1,2]", "not json", """[{"images":[]}]""", """[{"images":["a"],"cols":0,"rows":1,"width":1,"height":1}]""", """{"a":"b"}""", """[{"images":"a"}]""")) {
            assertNull(bad, Storyboards.parse(bad, info))
        }
    }

    @Test fun theRealTwitchShapeWithRelativeNamesResolvesAgainstTheJsonsDirectory() {
        val url = "https://d2vjef5jvl6bfs.cloudfront.net/abc123_streamer_4444/storyboards/2501-info.json"
        val real = """[{"count":100,"width":320,"rows":10,"images":["2501-high-0.jpg","2501-high-1.jpg"],"interval":10,"quality":"high","sprite_size":[3200,1800],"cols":10,"height":180},
            {"count":100,"width":160,"rows":10,"images":["2501-low-0.jpg","2501-low-1.jpg","2501-low-2.jpg"],"interval":10,"quality":"low","sprite_size":[1600,900],"cols":10,"height":90}]"""
        val s = Storyboards.parse(real, url)!!
        assertEquals("low", s.quality)
        assertEquals("https://d2vjef5jvl6bfs.cloudfront.net/abc123_streamer_4444/storyboards/2501-low-2.jpg", s.images[2])
        assertEquals(3, s.images.size)
        assertEquals("count,width,rows,images,interval,quality,sprite_size,cols,height", Storyboards.fieldNames(real))
    }

    @Test fun fieldNamesNeverThrow() {
        assertEquals("notjson", Storyboards.fieldNames("""{"a":"""))
        assertEquals("noobject", Storyboards.fieldNames("[]"))
        assertEquals("a,b", Storyboards.fieldNames("""{"a":1,"b":2}"""))
    }

    @Test fun theDebugLineNamesTheStateWithoutAnyUrl() {
        assertEquals("seekpv: url ok, sheets 3, fmt count,width, frame 7, err -", StoryboardDiag(true, 3, "count,width", "").line(7))
        assertEquals("seekpv: url missing, sheets 0, fmt -, frame -, err http401", StoryboardDiag(false, 0, "", "http401").line(null))
        assertEquals("http403", StoryboardDiag.errorClass(java.io.IOException("Access token request failed (HTTP 403)")))
        assertEquals("SocketTimeoutException", StoryboardDiag.errorClass(java.net.SocketTimeoutException("https://secret.example/x")))
    }

    @Test fun anObjectWrappingTheArrayIsAccepted() {
        assertNotNull(Storyboards.parse("""{"storyboards":[{"width":100,"height":56,"rows":1,"cols":1,"interval":1,"count":1,"images":["a.jpg"]}]}""", info))
    }
}

class StoryboardResolverTest {
    private lateinit var gql: MockWebServer
    private lateinit var cdn: MockWebServer

    @Before fun setUp() { gql = MockWebServer().also { it.start() }; cdn = MockWebServer().also { it.start() } }
    @After fun tearDown() { gql.shutdown(); cdn.shutdown() }

    private fun resolver() = TwitchInternal.vodPlayback(gql.url("/gql").toString(), "http://127.0.0.1:1/")

    @Test fun theWebPlayersPersistedQueryIsTriedFirst() = runBlocking {
        val infoUrl = cdn.url("/b/storyboards/9-info.json").toString()
        gql.enqueue(MockResponse().setBody("""{"data":{"video":{"id":"9","seekPreviewsURL":"$infoUrl"}}}"""))
        cdn.enqueue(MockResponse().setBody("""[{"width":100,"height":56,"rows":2,"cols":2,"interval":3,"count":4,"images":["9-low-0.jpg"]}]"""))
        assertNotNull(resolver().storyboard("9", 12, null))
        val body = gql.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"operationName\":\"VideoPlayer_VODSeekbarPreviewVideo\"") && body.contains("07e99e4d56c5a7c67117a154777b0baf85a5ffefa393b213f4bc712ccaf85dd6") && body.contains("\"videoID\":\"9\"") && body.contains("\"includePrivate\":false"))
        assertTrue(!body.contains("\"query\""))
    }

    @Test fun anUnknownHashFallsBackToTheRawQuery() = runBlocking {
        val infoUrl = cdn.url("/b/storyboards/9-info.json").toString()
        gql.enqueue(MockResponse().setBody("""{"errors":[{"message":"PersistedQueryNotFound"}]}"""))
        gql.enqueue(MockResponse().setBody("""{"data":{"video":{"seekPreviewsURL":"$infoUrl"}}}"""))
        cdn.enqueue(MockResponse().setBody("""[{"width":100,"height":56,"rows":2,"cols":2,"interval":3,"count":4,"images":["9-low-0.jpg"]}]"""))
        assertNotNull(resolver().storyboard("9", 12, null))
        gql.takeRequest()
        assertTrue(gql.takeRequest().body.readUtf8().contains("seekPreviewsURL"))
    }

    @Test fun noFieldNoJsonOrAnErrorMeansNoThumbnails() = runBlocking {
        // each call asks twice (persisted, then raw)
        repeat(2) { gql.enqueue(MockResponse().setBody("""{"data":{"video":{"seekPreviewsURL":null}}}""")) }
        assertNull(resolver().storyboard("9", 12, null))
        repeat(2) { gql.enqueue(MockResponse().setBody("""{"data":{"video":null}}""")) }
        assertNull(resolver().storyboard("9", 12, null))
        repeat(2) { gql.enqueue(MockResponse().setResponseCode(500)) }
        assertNull(resolver().storyboard("9", 12, null))
        gql.enqueue(MockResponse().setBody("""{"data":{"video":{"seekPreviewsURL":"${cdn.url("/x.json")}"}}}"""))
        cdn.enqueue(MockResponse().setResponseCode(404))
        assertNull(resolver().storyboard("9", 12, null))
    }

    @Test fun theDiagnosticsRecordWhatHappened() = runBlocking {
        val r = resolver()
        repeat(2) { gql.enqueue(MockResponse().setResponseCode(403)) }
        assertNull(r.storyboard("9", 12, null))
        assertEquals("seekpv: url missing, sheets 0, fmt -, frame -, err http403", r.storyboardDiag().line(null))
        val infoUrl = cdn.url("/b/storyboards/9-info.json").toString()
        gql.enqueue(MockResponse().setBody("""{"data":{"video":{"seekPreviewsURL":"$infoUrl"}}}"""))
        cdn.enqueue(MockResponse().setBody("""[{"width":100,"height":56,"rows":2,"cols":2,"interval":3,"count":4,"images":["9-low-0.jpg"],"quality":"low"}]"""))
        assertNotNull(r.storyboard("9", 12, null))
        assertEquals("seekpv: url ok, sheets 1, fmt width,height,rows,cols,interval,count,images,quality, frame 2, err -", r.storyboardDiag().line(2))
    }

    @Test fun aSheetIsFetchedAsBytes() = runBlocking {
        cdn.enqueue(MockResponse().setBody("JPEGDATA"))
        assertEquals("JPEGDATA", String(resolver().sheetBytes(cdn.url("/s.jpg").toString())!!))
        assertNull(resolver().sheetBytes("ftp://nope/s.jpg"))
    }
}
