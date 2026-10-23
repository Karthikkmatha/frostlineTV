package tv.frostline.feature.chat

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real, tiny files: a 3-frame GIF, a 3-frame animated WebP and a still WebP. */
class ImageSniffTest {
    private fun bytes(s: String) = Base64.getDecoder().decode(s)
    private val gif = bytes("R0lGODlhEAAQAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQABgAAACwAAAAAEAAQAAAIHQABCBxIsKDBgwgTKlzIsKHDhxAjSpxIsaLFgQEBACH5BAEGAAEALAAAAAAQABAAgQD/AAAAAAAAAAAAAAgdAAEIHEiwoMGDCBMqXMiwocOHECNKnEixosWBAQEAIfkEAQYAAQAsAAAAABAAEACBAAD/AAAAAAAAAAAACB0AAQgcSLCgwYMIEypcyLChw4cQI0qcSLGixYEBAQA7")
    private val animatedWebp = bytes("UklGRrQAAABXRUJQVlA4WAoAAAACAAAADwAADwAAQU5JTQYAAAAAAAAAAABBTk1GKAAAAAAAAAAAAA8AAA8AADwAAAJWUDhMDwAAAC8PwAMABxD9j/4HIqL/AQBBTk1GKAAAAAAAAAAAAA8AAA8AADwAAABWUDhMDwAAAC8PwAMAB9D/iP4HIqL/AQBBTk1GKAAAAAAAAAAAAA8AAA8AADwAAABWUDhMDwAAAC8PwAMABxDR//4HIqL/AQA=")
    private val stillWebp = bytes("UklGRhwAAABXRUJQVlA4TA8AAAAvD8ADAAcQ/Y/+ByKi/wEA")

    @Test fun gifHasThreeFrames() { assertEquals(3, ImageSniff.frameCount(gif)); assertTrue(ImageSniff.isAnimated(gif)) }
    @Test fun animatedWebpHasThreeFrames() { assertEquals(3, ImageSniff.frameCount(animatedWebp)); assertTrue(ImageSniff.isAnimated(animatedWebp)) }
    @Test fun stillWebpIsOneFrame() { assertEquals(1, ImageSniff.frameCount(stillWebp)); assertFalse(ImageSniff.isAnimated(stillWebp)) }
    @Test fun garbageIsOneFrame() { assertEquals(1, ImageSniff.frameCount(ByteArray(40) { 7 })) }
}
