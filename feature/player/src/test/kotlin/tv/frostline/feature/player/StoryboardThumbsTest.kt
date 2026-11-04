package tv.frostline.feature.player

import android.graphics.Bitmap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.frostline.core.twitchinternal.StoryboardSheetSet

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoryboardThumbsTest {
    /** 5 sheets of 10 x 10 frames, one frame per 10 s: 500 frames, 5000 s. */
    private val set = StoryboardSheetSet(
        "low", (0 until 5).map { "https://cdn/low-$it.jpg" }, cols = 10, rows = 10, frameWidth = 160, frameHeight = 90, intervalSec = 10.0, count = 500,
    )
    private val fetched = mutableListOf<String>()
    private val cuts = mutableListOf<Triple<Int, Int, Int>>() // sheet byte tag, col, row
    private var failFetch = false

    private fun TestScope.thumbs(): StoryboardThumbs = StoryboardThumbs(
        scope = this,
        worker = StandardTestDispatcher(testScheduler),
        fetch = { url -> fetched += url; if (failFetch) null else byteArrayOf(url.last { it.isDigit() }.digitToInt().toByte()) },
        cut = { bytes, _, col, row -> cuts += Triple(bytes[0].toInt(), col, row); Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888) },
    )

    @Test fun nothingIsFetchedUntilAPreviewAsksForAFrame() = runTest {
        val t = thumbs()
        t.load(set)
        runCurrent()
        assertTrue(fetched.isEmpty())
        assertNull(t.frame.value)
    }

    @Test fun theFrameNearestThePositionComesFromTheRightSheetAndCell() = runTest {
        val t = thumbs()
        t.load(set)
        t.show(1_234_000) // 1234 s -> frame 123 -> sheet 1, cell 23 -> col 3, row 2
        runCurrent()
        assertEquals(123, t.frame.value!!.index)
        assertEquals(Triple(1, 3, 2), cuts.single())
    }

    @Test fun onlyTheCurrentSheetAndItsNeighboursAreFetchedAndKept() = runTest {
        val t = thumbs()
        t.load(set)
        t.show(2_500_000) // frame 250 -> sheet 2
        runCurrent()
        assertEquals(setOf("https://cdn/low-1.jpg", "https://cdn/low-2.jpg", "https://cdn/low-3.jpg"), fetched.toSet())
        assertEquals(setOf(1, 2, 3), t.heldSheets)
        t.show(4_500_000) // frame 450 -> sheet 4: the window slides
        runCurrent()
        assertEquals(setOf(3, 4), t.heldSheets)
    }

    @Test fun movingInsideOneFrameDoesNoWork() = runTest {
        val t = thumbs()
        t.load(set)
        t.show(100_000); runCurrent()
        val f = t.frame.value
        val cutsBefore = cuts.size; val fetchesBefore = fetched.size
        t.show(100_500); t.show(109_999); runCurrent()
        assertSame(f, t.frame.value)
        assertEquals(cutsBefore, cuts.size)
        assertEquals(fetchesBefore, fetched.size)
        t.show(110_000); runCurrent()
        assertEquals(11, t.frame.value!!.index)
    }

    @Test fun goingBackToAFrameUsesTheCachedCutNotANewOne() = runTest {
        val t = thumbs()
        t.load(set)
        t.show(100_000); runCurrent()
        t.show(900_000); runCurrent()
        val n = cuts.size
        t.show(100_000); runCurrent()
        assertEquals(n, cuts.size)
        assertEquals(10, t.frame.value!!.index)
    }

    @Test fun noStoryboardMeansNoThumbnail() = runTest {
        val t = thumbs()
        t.show(100_000); runCurrent()
        assertNull(t.frame.value)
        t.load(null); t.show(100_000); runCurrent()
        assertNull(t.frame.value)
        assertTrue(fetched.isEmpty())
    }

    @Test fun aFailedFetchMeansNoThumbnailAndNoCrash() = runTest {
        failFetch = true
        val t = thumbs()
        t.load(set)
        t.show(100_000); runCurrent()
        assertNull(t.frame.value)
    }

    @Test fun hideRemovesTheCardAndShowCanComeBack() = runTest {
        val t = thumbs()
        t.load(set)
        t.show(100_000); runCurrent()
        assertNotNull(t.frame.value)
        t.hide()
        assertNull(t.frame.value)
        t.show(100_000); runCurrent()
        assertEquals(10, t.frame.value!!.index)
    }

    @Test fun cellsAreCutAtDisplaySizeNotWholeSheets() {
        assertEquals(1, sampleFor(160, 90, 440, 248)) // small cells are never shrunk
        assertEquals(2, sampleFor(1000, 560, 440, 248))
        assertEquals(4, sampleFor(1920, 1080, 440, 248))
        assertEquals(1, sampleFor(600, 337, 440, 248))
    }
}
