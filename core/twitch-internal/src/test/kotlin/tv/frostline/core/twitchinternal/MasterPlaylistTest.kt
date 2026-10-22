package tv.frostline.core.twitchinternal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MasterPlaylistTest {
    private val playlist = """
        #EXTM3U
        #EXT-X-TWITCH-INFO:NODE="video-edge",MANIFEST-NODE-TYPE="weaver_cluster"
        #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="chunked",NAME="1080p60 (source)",AUTOSELECT=YES,DEFAULT=YES
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=8000000,RESOLUTION=1920x1080,CODECS="avc1.64002A,mp4a.40.2",VIDEO="chunked",FRAME-RATE=60.000
        https://edge/chunked.m3u8
        #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="720p60",NAME="720p60",AUTOSELECT=YES,DEFAULT=YES
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=3400000,RESOLUTION=1280x720,CODECS="avc1.4D401F,mp4a.40.2",VIDEO="720p60",FRAME-RATE=60.000
        https://edge/720p60.m3u8
        #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="audio_only",NAME="audio_only",AUTOSELECT=NO,DEFAULT=NO
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=160000,CODECS="mp4a.40.2",VIDEO="audio_only"
        https://edge/audio_only.m3u8
        #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="480p30",NAME="480p",AUTOSELECT=YES,DEFAULT=YES
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=1400000,RESOLUTION=852x480,CODECS="avc1.4D401F,mp4a.40.2",VIDEO="480p30",FRAME-RATE=30.000
        https://edge/480p30.m3u8
    """.trimIndent()

    @Test fun parsesAllVariantsIncludingAudioOnly() {
        val all = MasterPlaylistParser.parse(playlist)
        assertEquals(4, all.size)
        assertTrue(all.single { it.groupId == "audio_only" }.isAudioOnly)
    }

    @Test fun videoVariantsAreBestFirstWithLabels() {
        val v = MasterPlaylistParser.videoVariants(playlist)
        assertEquals(listOf("1080p60 (Source)", "720p60", "480p"), v.map { it.label })
        assertEquals(1080, v[0].height)
        assertEquals(1920, v[0].width)
        assertEquals(60.0, v[0].frameRate, 0.01)
        assertEquals(8_000_000L, v[0].bandwidth)
        assertTrue(v[0].isSource)
        assertFalse(v[1].isSource)
    }

    @Test fun commasInsideQuotedAttributesDoNotSplit() {
        val v = MasterPlaylistParser.videoVariants(playlist)
        assertEquals(3, v.size) // CODECS="a,b" must not eat the following attributes
        assertEquals("720p60", v[1].groupId)
    }

    @Test fun garbageYieldsNothing() {
        assertTrue(MasterPlaylistParser.parse("not a playlist").isEmpty())
        assertTrue(MasterPlaylistParser.parse("").isEmpty())
    }
}
