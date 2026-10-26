package tv.frostline.feature.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * One page of the emote atlas: a single bitmap (so a single GPU texture) cut into a grid of [AtlasGeometry.CELL] px cells.
 * [used] marks the cells that hold a live region. Only touched under the owning [EmoteAtlas]'s lock.
 */
class AtlasPage internal constructor(val index: Int, cols: Int, rows: Int, cell: Int) {
    internal val bitmap: Bitmap = Bitmap.createBitmap(cols * cell, rows * cell, Bitmap.Config.ARGB_8888)
    internal val canvas = Canvas(bitmap)
    val image: ImageBitmap = bitmap.asImageBitmap()
    internal val used = BooleanArray(cols * rows)
    internal var usedCells = 0
    val bytes: Long = bitmap.allocationByteCount.toLong()
}

/**
 * A rectangle (or [count] equal rectangles, the frames of an animation) inside one [AtlasPage]. Drawn with source rects
 * into the page's shared texture; never a texture of its own.
 */
class AtlasRegion internal constructor(
    val page: AtlasPage,
    val w: Int,
    val h: Int,
    internal val xs: IntArray,
    internal val ys: IntArray,
    internal val firstCells: IntArray,
    internal val cellsPerFrame: Int,
) {
    val count: Int get() = xs.size
    val image: ImageBitmap get() = page.image
    fun srcX(frame: Int): Int = xs[frame]
    fun srcY(frame: Int): Int = ys[frame]
    /** Atlas cells this region occupies (its share of the texture). */
    val cells: Int get() = cellsPerFrame * xs.size
    @Volatile internal var freed = false
}

object AtlasGeometry {
    /** Emotes are decoded about this tall (EmoteEngine.TARGET_H); a cell holds one such image plus a 1 px gutter all round. */
    const val CELL = 50
    const val PAD = 1
    const val MAX_IMAGE_H = CELL - 2 * PAD
    const val COLS = 20
    const val ROWS = 20
    /** Cells needed for an image [w] px wide: it spans whole cells in one row. */
    fun spanOf(w: Int): Int = (w + 2 * PAD + CELL - 1) / CELL
}

/**
 * The shared emote atlas for one size class (48 px tall emotes): stills and animation frames are packed into a few
 * large bitmaps instead of one bitmap (and GPU texture) per emote or frame. A page is 1000x1000 (4 MB); a new page is
 * only added when the others are full, up to [maxPages]. Allocation and release are exact (cells), so there is no
 * fragmentation beyond a row; the caller evicts LRU entries when [tryAlloc] returns null. Thread-safe.
 */
class EmoteAtlas(val maxPages: Int = 4) {
    private val pages = ArrayList<AtlasPage>(maxPages)
    /** Transparent black written with SRC: replaces the pixels (a CLEAR draw behaves the same on a device). */
    private val clearPaint = Paint().apply { color = 0; xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
    private val srcPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }

    val pageCount: Int @Synchronized get() = pages.size
    val bytes: Long @Synchronized get() = pages.sumOf { it.bytes }
    val usedCells: Int @Synchronized get() = pages.sumOf { it.usedCells }

    /** Room for [count] images of [w] x [h] px, all in one page, or null when every page is full. */
    @Synchronized
    fun tryAlloc(w: Int, h: Int, count: Int): AtlasRegion? {
        val span = AtlasGeometry.spanOf(w)
        if (span > AtlasGeometry.COLS || h > AtlasGeometry.MAX_IMAGE_H || count < 1) return null
        for (p in pages) allocIn(p, w, h, count, span)?.let { return it }
        if (pages.size < maxPages) {
            val p = AtlasPage(pages.size, AtlasGeometry.COLS, AtlasGeometry.ROWS, AtlasGeometry.CELL)
            pages += p
            return allocIn(p, w, h, count, span)
        }
        return null
    }

    private fun allocIn(p: AtlasPage, w: Int, h: Int, count: Int, span: Int): AtlasRegion? {
        if (AtlasGeometry.COLS * AtlasGeometry.ROWS - p.usedCells < count * span) return null
        val first = IntArray(count)
        var found = 0
        outer@ for (row in 0 until AtlasGeometry.ROWS) {
            var col = 0
            while (col + span <= AtlasGeometry.COLS) {
                val start = row * AtlasGeometry.COLS + col
                var free = true
                var k = 0
                while (k < span) {
                    if (p.used[start + k]) { free = false; col += k + 1; break }
                    k++
                }
                if (free) {
                    first[found++] = start
                    for (j in 0 until span) p.used[start + j] = true // reserved while scanning; undone below if we run out
                    col += span
                    if (found == count) break@outer
                }
            }
        }
        if (found < count) {
            for (i in 0 until found) for (j in 0 until span) p.used[first[i] + j] = false
            return null
        }
        p.usedCells += count * span
        val xs = IntArray(count) { (first[it] % AtlasGeometry.COLS) * AtlasGeometry.CELL + AtlasGeometry.PAD }
        val ys = IntArray(count) { (first[it] / AtlasGeometry.COLS) * AtlasGeometry.CELL + AtlasGeometry.PAD }
        return AtlasRegion(p, w, h, xs, ys, first, span)
    }

    /** Copies [src] into frame [frame] of [r] (replacing whatever the cells held, gutter included). */
    @Synchronized
    fun write(r: AtlasRegion, frame: Int, src: Bitmap) {
        if (r.freed) return
        val x = r.xs[frame]
        val y = r.ys[frame]
        val c = r.page.canvas
        val left = (x - AtlasGeometry.PAD).toFloat()
        val top = (y - AtlasGeometry.PAD).toFloat()
        c.drawRect(left, top, left + r.cellsPerFrame * AtlasGeometry.CELL, top + AtlasGeometry.CELL, clearPaint)
        c.drawBitmap(src, x.toFloat(), y.toFloat(), srcPaint)
    }

    @Synchronized
    fun free(r: AtlasRegion) {
        if (r.freed) return
        r.freed = true
        for (start in r.firstCells) for (j in 0 until r.cellsPerFrame) r.page.used[start + j] = false
        r.page.usedCells -= r.cells
    }

    /** Drops every allocation (the pages stay allocated and are reused). */
    @Synchronized
    fun clear() {
        for (p in pages) { p.used.fill(false); p.usedCells = 0 }
    }
}

/** Scales [src] down (never up) so it fits one atlas image: at most 48 px tall and 4 cells wide. Returns [src] when it already fits. */
internal fun fitToAtlas(src: Bitmap): Bitmap {
    val maxW = 4 * AtlasGeometry.CELL - 2 * AtlasGeometry.PAD
    val maxH = AtlasGeometry.MAX_IMAGE_H
    if (src.width <= maxW && src.height <= maxH) return src
    val s = minOf(maxW.toFloat() / src.width, maxH.toFloat() / src.height)
    return Bitmap.createScaledBitmap(src, (src.width * s).toInt().coerceAtLeast(1), (src.height * s).toInt().coerceAtLeast(1), true)
}
