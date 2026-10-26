package tv.frostline.feature.chat

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmoteAtlasTest {
    private val perPage = AtlasGeometry.COLS * AtlasGeometry.ROWS

    @Test fun oneBitmapSharedByManyRegions() {
        val atlas = EmoteAtlas(maxPages = 2)
        val a = atlas.tryAlloc(48, 48, 1)!!
        val b = atlas.tryAlloc(48, 48, 12)!!
        assertSame(a.page, b.page)
        assertEquals(1, atlas.pageCount)
        assertEquals(13, atlas.usedCells)
        // Distinct cells: no two frames share an origin.
        val origins = HashSet<Pair<Int, Int>>()
        origins += a.srcX(0) to a.srcY(0)
        for (i in 0 until b.count) origins += b.srcX(i) to b.srcY(i)
        assertEquals(13, origins.size)
    }

    @Test fun wideImagesSpanSeveralCells() {
        val atlas = EmoteAtlas(maxPages = 1)
        val w = atlas.tryAlloc(150, 48, 2)!!
        assertEquals(AtlasGeometry.spanOf(150), w.cellsPerFrame)
        assertEquals(2 * AtlasGeometry.spanOf(150), atlas.usedCells)
        // A frame never straddles a row end.
        for (i in 0 until w.count) assertTrue(w.srcX(i) + w.w <= AtlasGeometry.COLS * AtlasGeometry.CELL)
    }

    @Test fun freedCellsAreReusedExactly() {
        val atlas = EmoteAtlas(maxPages = 1)
        val all = atlas.tryAlloc(48, 48, perPage)!!
        assertNull(atlas.tryAlloc(48, 48, 1)) // full, and the atlas may not grow
        atlas.free(all)
        assertEquals(0, atlas.usedCells)
        assertNotNull(atlas.tryAlloc(48, 48, perPage))
        atlas.free(all) // freeing twice is harmless
    }

    @Test fun growsByPagesUpToTheLimit() {
        val atlas = EmoteAtlas(maxPages = 2)
        assertNotNull(atlas.tryAlloc(48, 48, perPage))
        assertNotNull(atlas.tryAlloc(48, 48, perPage))
        assertEquals(2, atlas.pageCount)
        assertNull(atlas.tryAlloc(48, 48, 1))
    }

    @Test fun anAnimationNeverSplitsAcrossPages() {
        val atlas = EmoteAtlas(maxPages = 2)
        atlas.tryAlloc(48, 48, perPage - 5)!!
        val strip = atlas.tryAlloc(48, 48, 10)!! // does not fit the rest of page 0
        assertEquals(1, strip.page.index)
    }

    @Test fun writeFillsTheCells() {
        val atlas = EmoteAtlas(maxPages = 1)
        val first = atlas.tryAlloc(10, 10, 1)!!
        val red = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.RED) }
        atlas.write(first, 0, red)
        val px = first.page.bitmap
        assertEquals(Color.RED, px.getPixel(first.srcX(0), first.srcY(0)))
        assertEquals(Color.RED, px.getPixel(first.srcX(0) + 9, first.srcY(0) + 9))
        atlas.free(first)
        val again = atlas.tryAlloc(10, 10, 1)!!
        val blue = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.BLUE) }
        atlas.write(again, 0, blue)
        // (Clearing the old pixels relies on the SRC xfermode, which Robolectric's canvas does not apply; checked on device.)
        assertEquals(Color.BLUE, px.getPixel(again.srcX(0), again.srcY(0)))
    }

    @Test fun oversizedImagesAreScaledToFit() {
        val big = Bitmap.createBitmap(400, 96, Bitmap.Config.ARGB_8888)
        val fit = fitToAtlas(big)
        assertTrue(fit.width <= 4 * AtlasGeometry.CELL - 2 && fit.height <= AtlasGeometry.MAX_IMAGE_H)
        val small = Bitmap.createBitmap(30, 30, Bitmap.Config.ARGB_8888)
        assertSame(small, fitToAtlas(small))
    }

    @Test fun lruEvictOldestFreesInOrder() {
        val evicted = ArrayList<String>()
        val lru = WeightedLru<String, Int>(300, { 1L }) { k, _ -> evicted += k }
        lru.put("a", 1); lru.put("b", 2); lru.put("c", 3)
        lru.get("a")
        assertTrue(lru.evictOldest())
        assertEquals(listOf("b"), evicted)
        assertEquals(2, lru.size)
    }
}
