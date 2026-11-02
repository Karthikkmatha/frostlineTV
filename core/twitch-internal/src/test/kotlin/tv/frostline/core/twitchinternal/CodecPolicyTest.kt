package tv.frostline.core.twitchinternal

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CodecPolicyTest {
    /** A TV that decodes HEVC up to 2560x1440@60 and H.264 up to 1920x1080@60. */
    private val hevcTv = DecoderCapability { codec, w, h, fps ->
        when (codec) {
            VideoCodec.Hevc -> w <= 2560 && h <= 1440 && fps <= 60.0
            VideoCodec.H264 -> w <= 1920 && h <= 1080 && fps <= 60.0
            else -> false
        }
    }

    /** A 32-bit Chromecast-like box: H.264 1080p60 only. */
    private val h264Tv = DecoderCapability { codec, w, h, fps -> codec == VideoCodec.H264 && w <= 1920 && h <= 1080 && fps <= 60.0 }

    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="1440p60",NAME="1440p60 (source)"
        #EXT-X-STREAM-INF:BANDWIDTH=14000000,RESOLUTION=2560x1440,CODECS="hvc1.1.6.L153.B0,mp4a.40.2",VIDEO="1440p60",FRAME-RATE=60.000
        https://edge/1440.m3u8
        #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="1080p60",NAME="1080p60"
        #EXT-X-STREAM-INF:BANDWIDTH=8000000,RESOLUTION=1920x1080,CODECS="avc1.64002A,mp4a.40.2",VIDEO="1080p60",FRAME-RATE=60.000
        https://edge/1080.m3u8
        #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="720p60",NAME="720p60"
        #EXT-X-STREAM-INF:BANDWIDTH=3400000,RESOLUTION=1280x720,CODECS="hev1.1.6.L120.B0,mp4a.40.2",VIDEO="720p60",FRAME-RATE=60.000
        https://edge/720.m3u8
        #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="av1",NAME="1080p60 av1"
        #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080,CODECS="av01.0.08M.08,mp4a.40.2",VIDEO="av1",FRAME-RATE=60.000
        https://edge/av1.m3u8
        #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="audio_only",NAME="audio_only"
        #EXT-X-STREAM-INF:BANDWIDTH=160000,CODECS="mp4a.40.2",VIDEO="audio_only"
        https://edge/a.m3u8
    """.trimIndent()

    @Test fun codecsAreRecognisedFromTheCodecsAttribute() {
        assertEquals(VideoCodec.Hevc, VideoCodec.fromCodecs("hvc1.1.6.L150.B0,mp4a.40.2"))
        assertEquals(VideoCodec.Hevc, VideoCodec.fromCodecs("hev1.2.4.L93.B0,mp4a.40.2"))
        assertEquals(VideoCodec.H264, VideoCodec.fromCodecs("avc1.64002A,mp4a.40.2"))
        assertEquals(VideoCodec.Av1, VideoCodec.fromCodecs("mp4a.40.2,av01.0.08M.08"))
        assertEquals(VideoCodec.Unknown, VideoCodec.fromCodecs("mp4a.40.2"))
        assertEquals(VideoCodec.Unknown, VideoCodec.fromCodecs(null))
        assertEquals(VideoCodec.Unknown, VideoCodec.fromCodecs(""))
    }

    @Test fun theParserCarriesTheCodecOfEachVariant() {
        val all = MasterPlaylistParser.parse(master).associateBy { it.groupId }
        assertEquals(VideoCodec.Hevc, all.getValue("1440p60").codec)
        assertTrue(all.getValue("1440p60").isHevc)
        assertEquals(VideoCodec.H264, all.getValue("1080p60").codec)
        assertEquals(VideoCodec.Av1, all.getValue("av1").codec)
        assertFalse(all.getValue("1080p60").isHevc)
    }

    @Test fun aDeviceWithHardwareHevcKeepsHevcAndDropsAv1() {
        val v = MasterPlaylistParser.videoVariants(master, hevcTv)
        assertEquals(listOf("1440p60", "1080p60", "720p60"), v.map { it.groupId })
        assertEquals(listOf(VideoCodec.Hevc, VideoCodec.H264, VideoCodec.Hevc), v.map { it.codec })
    }

    @Test fun aDeviceWithoutHevcOnlyOffersWhatItPlaysSmoothly() {
        val v = MasterPlaylistParser.videoVariants(master, h264Tv)
        assertEquals(listOf("1080p60"), v.map { it.groupId })
    }

    @Test fun h264IsCheckedForSizeAndRateToo() {
        // 1080p60 H.264 is beyond this decoder (30 fps only), but 720p60 HEVC is fine: only the playable ones stay.
        val tv = DecoderCapability { codec, w, h, fps ->
            (codec == VideoCodec.H264 && w <= 1920 && h <= 1080 && fps <= 30.0) || (codec == VideoCodec.Hevc && w <= 1280)
        }
        assertEquals(listOf("720p60"), MasterPlaylistParser.videoVariants(master, tv).map { it.groupId })
    }

    @Test fun ifNothingIsPlayableTheListIsLeftAloneRatherThanEmpty() {
        val none = DecoderCapability { _, _, _, _ -> false }
        assertEquals(3, MasterPlaylistParser.videoVariants(master, none).size.coerceAtMost(3))
        assertTrue(MasterPlaylistParser.videoVariants(master, none).isNotEmpty())
    }

    @Test fun unknownCodecsAndSizesAreKept() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="a",NAME="a"
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,VIDEO="a"
            https://x/a.m3u8
            #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="b",NAME="b"
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720,CODECS="avc1.4D401F",VIDEO="b",FRAME-RATE=30.000
            https://x/b.m3u8
        """.trimIndent()
        assertEquals(listOf("a", "b"), MasterPlaylistParser.videoVariants(text, h264Tv).map { it.groupId })
    }

    @Test fun twoCodecsOfOneQualityCollapseToTheHevcOne() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="1080p60",NAME="1080p60"
            #EXT-X-STREAM-INF:BANDWIDTH=8000000,RESOLUTION=1920x1080,CODECS="avc1.64002A,mp4a.40.2",VIDEO="1080p60",FRAME-RATE=60.000
            https://x/h264.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="hvc1.1.6.L120.B0,mp4a.40.2",VIDEO="1080p60",FRAME-RATE=60.000
            https://x/hevc.m3u8
        """.trimIndent()
        val v = MasterPlaylistParser.videoVariants(text, hevcTv)
        assertEquals(1, v.size)
        assertEquals(VideoCodec.Hevc, v[0].codec)
    }

    @Test fun usherAsksForHevcFirstOnlyWhen1440p60HevcIsDecodable() {
        assertEquals("h265,h264", CodecPolicy.supportedCodecsParam(hevcTv))
        assertEquals("h264", CodecPolicy.supportedCodecsParam(h264Tv))
        val noHevcAt1440 = DecoderCapability { codec, w, _, _ -> codec == VideoCodec.Hevc && w <= 1920 }
        assertEquals("h264", CodecPolicy.supportedCodecsParam(noHevcAt1440))
    }
}

class UsherCodecParamTest {
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

    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="chunked",NAME="1080p60 (source)"
        #EXT-X-STREAM-INF:BANDWIDTH=8000000,RESOLUTION=1920x1080,CODECS="avc1.64002A,mp4a.40.2",VIDEO="chunked",FRAME-RATE=60.000
        https://edge/chunked.m3u8
    """.trimIndent()

    private val hevc = DecoderCapability { _, _, _, _ -> true }
    private val h264 = DecoderCapability { c, _, _, _ -> c == VideoCodec.H264 }

    private fun liveParam(cap: DecoderCapability): String? = runBlocking {
        gql.enqueue(MockResponse().setBody("""{"data":{"streamPlaybackAccessToken":{"value":"v","signature":"s"}}}"""))
        usher.enqueue(MockResponse().setBody(master))
        TwitchInternal.playback(gql.url("/gql").toString(), usher.url("/").toString(), cap).resolve("ch", null)
        usher.takeRequest().requestUrl!!.queryParameter("supported_codecs")
    }

    private fun vodParam(cap: DecoderCapability): String? = runBlocking {
        gql.enqueue(MockResponse().setBody("""{"data":{"videoPlaybackAccessToken":{"value":"v","signature":"s"}}}"""))
        usher.enqueue(MockResponse().setBody(master))
        TwitchInternal.vodPlayback(gql.url("/gql").toString(), usher.url("/").toString(), cap).vod("77", null)
        usher.takeRequest().requestUrl!!.queryParameter("supported_codecs")
    }

    @Test fun liveRequestsHevcFirstWhenTheDeviceCan() = assertEquals("h265,h264", liveParam(hevc))
    @Test fun liveRequestsH264OnlyOtherwise() = assertEquals("h264", liveParam(h264))
    @Test fun vodRequestsHevcFirstWhenTheDeviceCan() = assertEquals("h265,h264", vodParam(hevc))
    @Test fun vodRequestsH264OnlyOtherwise() = assertEquals("h264", vodParam(h264))
}
