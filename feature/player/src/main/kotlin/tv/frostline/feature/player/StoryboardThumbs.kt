package tv.frostline.feature.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tv.frostline.core.twitchinternal.StoryboardSheetSet

/** The frame shown above the timeline thumb: [index] is the storyboard frame number it was cut for. */
class Thumb(val index: Int, val bitmap: Bitmap)

/**
 * Seek-preview thumbnails from a Twitch storyboard. Everything is lazy: nothing is fetched until the first [show], only the
 * sheet of the frame wanted plus its two neighbours are kept (as the small encoded JPEGs), and a frame is cut out of its sheet
 * at display size only. [frame] changes only when the frame INDEX changes; moving within one frame's interval does nothing.
 * If there is no storyboard or a fetch or decode fails, [frame] stays null and the UI shows no card.
 */
class StoryboardThumbs(
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher,
    private val fetch: suspend (String) -> ByteArray?,
    private val cut: (sheet: ByteArray, set: StoryboardSheetSet, col: Int, row: Int) -> Bitmap?,
    private val keepFrames: Int = 12,
) {
    private val _frame = MutableStateFlow<Thumb?>(null)

    /** The frame for the position last passed to [show], once it is ready (the previous one stays until then). */
    val frame: StateFlow<Thumb?> = _frame.asStateFlow()

    private var set: StoryboardSheetSet? = null
    private var shownIndex = -1
    private var job: Job? = null

    // Touched only on [worker] (limited to one thread) except for the first two, which are only read to decide to start work.
    private val sheets = LinkedHashMap<Int, ByteArray>()
    private val inflight = HashMap<Int, Deferred<ByteArray?>>()
    private val frames = object : LinkedHashMap<Int, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>?) = size > keepFrames
    }

    /** Sheets currently held, for tests. */
    val heldSheets: Set<Int> get() = synchronized(sheets) { sheets.keys.toSet() }

    /** Starts a storyboard (null = none). Drops everything of the previous one. */
    fun load(newSet: StoryboardSheetSet?) {
        job?.cancel()
        set = newSet
        shownIndex = -1
        _frame.value = null
        synchronized(sheets) { sheets.clear() }
        scope.launch(worker) { inflight.values.forEach { it.cancel() }; inflight.clear(); frames.clear() }
    }

    val hasStoryboard: Boolean get() = set != null

    /** The preview moved to [positionMs]. Cheap when the frame index is unchanged. */
    fun show(positionMs: Long) {
        val s = set ?: return
        val idx = s.frameIndex(positionMs)
        if (idx == shownIndex) return
        shownIndex = idx
        job?.cancel()
        job = scope.launch(worker) {
            val cached = frames[idx]
            if (cached != null) {
                _frame.value = Thumb(idx, cached)
            } else {
                val bytes = sheet(s, s.sheetOf(idx)) ?: return@launch
                val (col, row) = s.cellOf(idx)
                val bmp = cut(bytes, s, col, row) ?: return@launch
                frames[idx] = bmp
                _frame.value = Thumb(idx, bmp)
            }
            keepWindow(s, s.sheetOf(idx))
        }
    }

    /** Back or focus leaving: the card goes away; sheets stay cached for the next time. */
    fun hide() {
        job?.cancel()
        shownIndex = -1
        _frame.value = null
    }

    private suspend fun sheet(s: StoryboardSheetSet, i: Int): ByteArray? {
        if (i !in s.images.indices) return null
        synchronized(sheets) { sheets[i] }?.let { return it }
        if (inflight[i]?.isCompleted == true) inflight.remove(i) // finished earlier and since evicted: fetch again
        val d = inflight.getOrPut(i) {
            scope.async(worker) { fetch(s.images[i])?.also { b -> synchronized(sheets) { sheets[i] = b } } }
        }
        return try { d.await() } finally { if (d.isCompleted) inflight.remove(i) }
    }

    /** Keeps the current sheet and its neighbours; the neighbours are fetched ahead, the rest are dropped. */
    private fun keepWindow(s: StoryboardSheetSet, cur: Int) {
        val keep = setOf(cur - 1, cur, cur + 1)
        synchronized(sheets) { sheets.keys.retainAll(keep) }
        for (n in keep) if (n in s.images.indices && n != cur) {
            if (inflight[n]?.isCompleted == true) inflight.remove(n)
            val have = synchronized(sheets) { sheets.containsKey(n) }
            if (!have && !inflight.containsKey(n)) inflight[n] = scope.async(worker) { fetch(s.images[n])?.also { b -> synchronized(sheets) { sheets[n] = b } } }
        }
    }
}

/**
 * Cuts one frame out of a sprite sheet at [targetW] x [targetH] px or the nearest power-of-two sub-sampling above it, decoding
 * only that cell (BitmapRegionDecoder), never the whole sheet.
 */
internal fun cutFrame(sheet: ByteArray, set: StoryboardSheetSet, col: Int, row: Int, targetW: Int, targetH: Int): Bitmap? = runCatching {
    @Suppress("DEPRECATION")
    val dec = BitmapRegionDecoder.newInstance(sheet, 0, sheet.size, false) ?: return@runCatching null
    try {
        val r = Rect(col * set.frameWidth, row * set.frameHeight, (col + 1) * set.frameWidth, (row + 1) * set.frameHeight)
        r.intersect(0, 0, dec.width, dec.height)
        if (r.isEmpty) return@runCatching null
        val o = BitmapFactory.Options().apply {
            inSampleSize = sampleFor(r.width(), r.height(), targetW, targetH)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        dec.decodeRegion(r, o)
    } finally {
        dec.recycle()
    }
}.getOrNull()

/** Largest power of two that keeps the cell at least as big as the target. */
internal fun sampleFor(cellW: Int, cellH: Int, targetW: Int, targetH: Int): Int {
    var s = 1
    while (cellW / (s * 2) >= targetW && cellH / (s * 2) >= targetH) s *= 2
    return s
}
