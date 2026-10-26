package tv.frostline.feature.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * One bitmap (one GPU texture) of an atlas, cut into a grid of cells. A "private" page (atlas turned off in Settings) holds
 * exactly one emote and has no grid. [used] marks live cells; only touched under the owning [EmoteAtlas]'s lock.
 */
class AtlasPage internal constructor(val index: Int, internal val bitmap: Bitmap, cells: Int) {
    internal val canvas = Canvas(bitmap)
    val image: ImageBitmap = bitmap.asImageBitmap()
    internal val used = BooleanArray(cells)
    internal var usedCells = 0
    val bytes: Long = bitmap.allocationByteCount.toLong()
}

/** One image, or [count] equal-sized frames of an animation, inside one [AtlasPage]. Drawn with src rects. */
class AtlasRegion internal constructor(
    val page: AtlasPage,
    val w: Int,
    val h: Int,
    internal val xs: IntArray,
    internal val ys: IntArray,
    internal val firstCells: IntArray,
    internal val cellsPerFrame: Int,
    internal val isPrivate: Boolean,
) {
    val count: Int get() = xs.size
    val image: ImageBitmap get() = page.image
    fun srcX(frame: Int): Int = xs[frame]
    fun srcY(frame: Int): Int = ys[frame]
    /** Atlas cells this region occupies (its share of the texture). */
    val cells: Int get() = cellsPerFrame * xs.size
    @Volatile internal var freed = false
}

/**
 * A shared emote atlas for one size class: images up to [cell] - 2 px tall are packed into [pageSize] x [pageSize] pages of
 * [cell] px cells (1 px transparent gutter all round), at most [maxPages] pages (so at most that many textures). A page is
 * only added when the others are full. When the atlas is full [tryAlloc] returns null and the caller evicts LRU entries.
 * With [enabled] false every region gets a bitmap of its own instead (the pre-atlas behaviour, a Settings switch).
 * Thread-safe.
 */
class EmoteAtlas(val cell: Int, val maxPages: Int = 2, val pageSize: Int = 1024) {
    companion object { const val PAD = 1 }

    val cols: Int = pageSize / cell
    val rows: Int = pageSize / cell
    val maxImageH: Int = cell - 2 * PAD
    val maxImageW: Int = 4 * cell - 2 * PAD
    private val cellsPerPage = cols * rows

    @Volatile var enabled: Boolean = true

    private val pages = ArrayList<AtlasPage>(maxPages)
    private var privateBytes = 0L
    private var privateCount = 0
    private val srcPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
    /** Transparent black written with SRC: replaces the pixels. */
    private val clearPaint = Paint().apply { color = 0; xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }

    val pageCount: Int @Synchronized get() = pages.size + privateCount
    val bytes: Long @Synchronized get() = pages.sumOf { it.bytes } + privateBytes
    val usedCells: Int @Synchronized get() = pages.sumOf { it.usedCells }

    fun spanOf(w: Int): Int = (w + 2 * PAD + cell - 1) / cell

    /** Room for [count] images of [w] x [h] px, all in one page, or null when every page is full. */
    @Synchronized
    fun tryAlloc(w: Int, h: Int, count: Int): AtlasRegion? {
        if (count < 1 || w < 1 || h < 1) return null
        if (!enabled) return allocPrivate(w, h, count)
        val span = spanOf(w)
        if (span > cols || h > maxImageH) return null
        for (p in pages) allocIn(p, w, h, count, span)?.let { return it }
        if (pages.size < maxPages) {
            val p = AtlasPage(pages.size, Bitmap.createBitmap(pageSize, pageSize, Bitmap.Config.ARGB_8888), cellsPerPage)
            pages += p
            return allocIn(p, w, h, count, span)
        }
        return null
    }

    private fun allocPrivate(w: Int, h: Int, count: Int): AtlasRegion {
        val bmp = Bitmap.createBitmap(w, h * count, Bitmap.Config.ARGB_8888)
        val p = AtlasPage(-1, bmp, 0)
        privateBytes += p.bytes
        privateCount++
        return AtlasRegion(p, w, h, IntArray(count), IntArray(count) { it * h }, IntArray(count), 0, true)
    }

    private fun allocIn(p: AtlasPage, w: Int, h: Int, count: Int, span: Int): AtlasRegion? {
        if (cellsPerPage - p.usedCells < count * span) return null
        val first = IntArray(count)
        var found = 0
        outer@ for (row in 0 until rows) {
            var col = 0
            while (col + span <= cols) {
                val start = row * cols + col
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
        val xs = IntArray(count) { (first[it] % cols) * cell + PAD }
        val ys = IntArray(count) { (first[it] / cols) * cell + PAD }
        return AtlasRegion(p, w, h, xs, ys, first, span, false)
    }

    /** Copies [src] into frame [frame] of [r], replacing whatever the cells held (gutter included). */
    @Synchronized
    fun write(r: AtlasRegion, frame: Int, src: Bitmap) {
        if (r.freed) return
        val x = r.xs[frame]
        val y = r.ys[frame]
        val c = r.page.canvas
        if (!r.isPrivate) {
            val left = (x - PAD).toFloat()
            val top = (y - PAD).toFloat()
            c.drawRect(left, top, left + r.cellsPerFrame * cell, top + cell, clearPaint)
        }
        c.drawBitmap(src, x.toFloat(), y.toFloat(), srcPaint)
    }

    @Synchronized
    fun free(r: AtlasRegion) {
        if (r.freed) return
        r.freed = true
        if (r.isPrivate) {
            privateBytes -= r.page.bytes
            privateCount--
            return
        }
        for (start in r.firstCells) for (j in 0 until r.cellsPerFrame) r.page.used[start + j] = false
        r.page.usedCells -= r.cells
    }

    /** Frees every allocation and drops the page bitmaps (regions already handed out become unusable: callers clear them first). */
    @Synchronized
    fun release() {
        pages.clear()
    }
}

/** Scales [src] down (never up) so it fits [atlas]: at most `maxImageH` tall and 4 cells wide. Returns [src] when it already fits. */
internal fun fitToAtlas(src: Bitmap, atlas: EmoteAtlas): Bitmap {
    val maxW = atlas.maxImageW
    val maxH = atlas.maxImageH
    if (src.width <= maxW && src.height <= maxH) return src
    val s = minOf(maxW.toFloat() / src.width, maxH.toFloat() / src.height)
    return Bitmap.createScaledBitmap(src, (src.width * s).toInt().coerceAtLeast(1), (src.height * s).toInt().coerceAtLeast(1), true)
}
