package tv.frostline.feature.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Animatable2
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/** One emote image: its still frame (always wanted) and, when animated and hot, its [FrameStrip]. */
class EmoteEntry(val url: String) {
    /** Its still frame: a cell range in the shared atlas. */
    @Volatile var still: AtlasRegion? = null
    /** The sampled animation: one region of the shared atlas holding all frames. */
    @Volatile var strip: AtlasRegion? = null
    /** null = not known yet, false = a plain image. */
    @Volatile var animated: Boolean? = null
    @Volatile var failedAt: Long = 0
    /** Consecutive still failures: the retry wait doubles from 1 s up to a minute. */
    @Volatile internal var failCount: Int = 0
    val stillPending = AtomicBoolean(false)
    /** A still decode is running right now (a second, higher-priority task for the same emote waits out). */
    internal val stillRunning = AtomicBoolean(false)
    /** A second task found the first still running (or the first shed itself): the first re-queues if the emote is on screen and still blank. */
    @Volatile internal var stillRerun = false
    /** Priority and time of the queued still task, so a later on-screen request can jump the queue and stale ones can be shed. */
    @Volatile internal var stillPrio: Int = Int.MAX_VALUE
    @Volatile internal var stillQueuedAt: Long = 0L
    /** How many chat lines / drawers showing this emote are on screen right now. Visible entries are never evicted. */
    val visible = AtomicInteger(0)
    /**
     * Last resort when the atlas has no slot for a visible emote: its decoded picture, drawn directly. Held only while
     * the emote is on screen (and capped), so it never grows.
     */
    @Volatile var direct: ImageBitmap? = null
    /** True when something can be drawn for this emote. */
    val drawable: Boolean get() = still != null || strip != null || direct != null
    val animPending = AtomicBoolean(false)
    /** Compose state, bumped when a decode lands, so canvases that read it redraw. */
    var version by mutableIntStateOf(0)
        private set
    fun bump() { version++ }
}

/** Tells an emote whether it may animate right now, given the chat's mode and how new its line is. */
fun interface EmoteGate {
    fun allows(seq: Long): Boolean
}

/**
 * Emote files and pre-decoded animations (item "animated emotes in fast chat").
 *
 *  - Files live in `cacheDir/emotes` (downloaded once, never per use).
 *  - Every emote gets a still frame (small, LRU-bounded). Animated ones can also get a [FrameStrip]: the animation
 *    sampled once at about 15 fps at display size. Strips live in a byte-bounded LRU, the hot set (most used and
 *    recently seen); the rest fall back to the still.
 *  - [mode] follows the message rate with hysteresis; [chatGate] says which lines may animate in each mode.
 *  - Work runs on two low-priority pools (stills + downloads, animation sampling) in priority order, so a visible emote
 *    is decoded before a background preload.
 */
class EmoteEngine(
    context: Context,
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build(),
    private val clockMs: () -> Long = SystemClock::uptimeMillis,
    /** Test seam: replaces the platform ImageDecoder. */
    private val decodeOverride: ((File) -> Drawable?)? = null,
) {
    private val appContext = context.applicationContext
    private val dir = File(appContext.cacheDir, DIR_NAME).also { it.mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val entries = ConcurrentHashMap<String, EmoteEntry>()
    private val usage = ConcurrentHashMap<String, AtomicInteger>()
    private val tracker = EmoteRateTracker()
    private val order = AtomicLong()

    /**
     * Two atlases of 1024 x 1024 pages (4 MB): at most two pages of stills (the second only when the first is full of emotes on screen) and two of animation frames, all at 48 px. Evicted least recently used first. [setAtlasEnabled] false gives every emote a bitmap of its own instead.
     */
    val stillAtlas = EmoteAtlas(cell = STILL_CELL, maxPages = STILL_PAGES)
    val stripAtlas = EmoteAtlas(cell = STRIP_CELL, maxPages = STRIP_PAGES)
    /** At most [STILL_CAP_ENTRIES] stills, least recently used out first (weight 1 each). */
    private val stills = WeightedLru<String, EmoteEntry>(STILL_CAP_ENTRIES.toLong(), { 1L }, { it.visible.get() > 0 }) { _, e ->
        evictions.incrementAndGet(); e.still?.let(stillAtlas::free); e.still = null; e.bump()
    }
    /** Debug counters: stills pushed out of the atlas, allocations that found no slot, direct-bitmap holders. */
    private val evictions = AtomicInteger()
    private val misses = AtomicInteger()
    private val directCount = AtomicInteger()
    private val strips = WeightedLru<String, EmoteEntry>(STRIP_CAP_CELLS.toLong(), { (it.strip?.cells ?: 0).toLong() }) { _, e -> e.strip?.let(stripAtlas::free); e.strip = null; e.bump() }

    /** Settings > Emote atlas. Off: per-emote bitmaps. Switching drops everything decoded; it is rebuilt on demand. */
    fun setAtlasEnabled(on: Boolean) {
        if (stillAtlas.enabled == on) return
        stills.clear(); strips.clear()
        stillAtlas.enabled = on; stripAtlas.enabled = on
        stillAtlas.release(); stripAtlas.release()
    }

    /** Chat is hidden or the player closed: give the atlas pages back. Pictures are decoded again from the disk cache when needed. */
    fun trimMemory() {
        stills.clear(); strips.clear()
        stillAtlas.release(); stripAtlas.release()
    }

    private val stillPool = pool(3, "emote-still", Thread.MIN_PRIORITY + 1)
    private val animPool = pool(2, "emote-anim", Thread.MIN_PRIORITY)

    /** Two low-priority looper threads drive the animated drawables while they are sampled: nothing runs on the main thread. */
    private val samplers: Array<Handler> = Array(2) { i ->
        val t = HandlerThread("emote-sample-$i", android.os.Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
        Handler(t.looper)
    }
    private val samplerNext = AtomicInteger()

    /** Chat on screen? False stops all animation work and frees the frame strips. */
    var active: Boolean by mutableStateOf(true)
        private set

    fun setChatActive(value: Boolean) {
        if (active == value) return
        active = value
        if (!value) {
            channelJob?.cancel()
            userJob?.cancel()
            strips.clear()
        }
    }

    /** Compose state: read it in a draw block and the canvas redraws when the mode flips. */
    var mode: EmoteMode by mutableStateOf(EmoteMode.Slow)
        private set
    @Volatile var newestSeq: Long = 0L
        private set
    @Volatile var diskBytes: Long = 0L
        private set
    @Volatile var ratePerSec: Float = 0f
        private set
    /** Class name / reason of the last decode or sampling failure, for the debug line (no URLs, no data). */
    @Volatile var lastFailure: String = ""
    val memoryBytes: Long get() = stillAtlas.bytes + stripAtlas.bytes
    val hotCount: Int get() = strips.size

    val chatGate = EmoteGate { seq ->
        when (mode) {
            EmoteMode.Slow -> true
            EmoteMode.Busy -> newestSeq - seq < BUSY_LINES
            EmoteMode.Fast -> false
        }
    }

    private var ticker: Job? = null
    private var globalsJob: Job? = null
    private var channelJob: Job? = null
    private var userJob: Job? = null

    init {
        ticker = scope.launch {
            while (isActive) {
                val t = clockMs()
                val prev = mode
                mode = tracker.update(t)
                // Fast chat shows stills only: nothing animated is held, and its memory is freed until the rate drops.
                if (mode == EmoteMode.Fast && prev != EmoteMode.Fast) strips.clear()
                ratePerSec = tracker.rate(t)
                // Hidden chat has no rate to follow: wake less often. (The cache directory is no longer walked here: the debug line reads it.)
                delay(if (active) 1_000 else 5_000)
            }
        }
    }

    /** One line for the debug overlay: mode, rate, hot animations, memory and disk. */
    fun debugLine(): String {
        val now = clockMs()
        if (now - diskCheckedAt > 5_000) { diskBytes = dirBytes(dir); diskCheckedAt = now } // only while the overlay asks, at most every 5 s
        return debugText()
    }

    @Volatile private var diskCheckedAt = -10_000L

    private fun debugText(): String =
        "emotes   ${mode.label} ${"%.1f".format(ratePerSec)}/s, ${strips.size} animated, mem ${memoryBytes / 1_048_576} MB, disk ${diskBytes / 1_048_576} MB" + if (lastFailure.isNotEmpty()) ", last: $lastFailure" else ""

    /**
     * Debug overlay: every bitmap this app holds in its own caches, with their bytes. The emote atlas is [AtlasPage]s
     * (stills + animation frames), Coil's memory cache holds thumbnails and avatars.
     */
    fun atlasLine(): String =
        "emotes: atlas p=${stillAtlas.pageCount}/$STILL_PAGES slots=${stillAtlas.usedCells}/${stillAtlas.totalCells.coerceAtLeast(stillAtlas.cols * stillAtlas.rows)} miss=${misses.get()} pending=${stillPool.queue.size} evict=${evictions.get()}"

    fun bitmapLine(): String {
        val coil = runCatching { coil3.SingletonImageLoader.get(appContext).memoryCache }.getOrNull()
        val coilN = coil?.keys?.size ?: 0
        val coilB = coil?.size ?: 0L
        val pages = stillAtlas.pageCount + stripAtlas.pageCount
        val total = memoryBytes + coilB
        return "bitmaps  ${pages + coilN} / ${total / 1024} KB: " + (if (stillAtlas.enabled) "atlas" else "per-emote") +
            " $pages tex ${memoryBytes / 1024} KB (${stills.size} stills, ${strips.size} strips), coil $coilN ${coilB / 1024} KB"
    }

    fun entry(url: String): EmoteEntry {
        entries[url]?.let { return it }
        if (entries.size >= MAX_ENTRIES) pruneEntries()
        return entries.getOrPut(url) { EmoteEntry(url) }
    }

    /** Hard cap on the url map (channel switches would otherwise grow it for ever): drops entries holding no picture and nothing in flight. */
    private fun pruneEntries() {
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            val e = it.next().value
            if (e.still == null && e.strip == null && !e.stillPending.get() && !e.animPending.get()) it.remove()
        }
        if (usage.size > MAX_ENTRIES) usage.keys.retainAll(entries.keys)
    }

    /** Debug overlay: sizes of the engine's caches. */
    fun cacheCounts(): String = "entries ${entries.size}, stills ${stills.size}, strips ${strips.size}"

    // ------------------------------------------------------------------ requests

    /** Makes sure [e] has a still frame (download + decode). Priority: lower runs first. */
    fun ensureStill(e: EmoteEntry, priority: Int = PRIO_VISIBLE) {
        if (e.still != null) { stills.get(e.url); return }
        if (e.direct != null) return
        if (e.failedAt != 0L && clockMs() - e.failedAt < retryWait(e)) return
        if (e.stillPending.compareAndSet(false, true)) {
            enqueueStill(e, priority)
        } else if (priority < e.stillPrio) {
            enqueueStill(e, priority) // queued at a lower priority and now on screen: a second task jumps the queue, the first finds the work done
        }
    }

    private fun retryWait(e: EmoteEntry): Long = (1_000L shl e.failCount.coerceIn(0, 6)).coerceAtMost(RETRY_MS)

    private fun enqueueStill(e: EmoteEntry, priority: Int) {
        e.stillPrio = priority
        e.stillQueuedAt = clockMs()
        stillPool.execute(Task(priority, order.incrementAndGet()) { runStill(e) })
    }

    /** An emote of a composed chat line / drawer is on screen: protect it from eviction and decode it first. */
    fun markVisible(e: EmoteEntry) {
        e.visible.incrementAndGet()
        ensureStill(e, PRIO_SCREEN)
    }

    fun markHidden(e: EmoteEntry) {
        if (e.visible.decrementAndGet() <= 0) {
            e.visible.set(0)
            e.direct?.let { e.direct = null; directCount.decrementAndGet() }
        }
    }

    /** Makes sure an animated [e] has its pre-decoded frames (no-op for plain images). */
    fun ensureAnimation(e: EmoteEntry, priority: Int = PRIO_HOT) {
        if (!active) return
        if (e.strip != null) { strips.get(e.url); return }
        if (e.animated == false) return
        if (clockMs() - e.failedAt < RETRY_MS && e.failedAt != 0L) return
        if (e.animPending.compareAndSet(false, true)) animPool.execute(Task(priority, order.incrementAndGet()) { runAnimation(e) })
    }

    /** Called for every chat message as it is built: message rate, emote usage, and the hot set. */
    fun noteMessage(m: ChatMessage) {
        tracker.record(clockMs())
        if (m.seq > newestSeq) newestSeq = m.seq
        for (p in m.parts) if (p is Part.EmoteRef) {
            val url = p.emote.url
            usage.getOrPut(url) { AtomicInteger() }.incrementAndGet()
            val e = entry(url)
            ensureStill(e, PRIO_VISIBLE)
            if (active && mode != EmoteMode.Fast) ensureAnimation(e, PRIO_HOT) else if (e.strip != null) strips.get(url)
        }
    }

    // ------------------------------------------------------------------ preloading

    /**
     * App open: download every global emote file and decode their stills, then pre-decode the global animations
     * (up to [GLOBAL_ANIM_CELLS] atlas cells), most used first.
     */
    fun preloadGlobals(emotes: List<Emote>) {
        globalsJob?.cancel()
        globalsJob = scope.launch { preload(emotes, animBudget = GLOBAL_ANIM_CELLS) }
    }

    /** Stream open: that channel's emote files + stills first, then its animations in the background. */
    fun preloadChannel(emotes: List<Emote>) {
        channelJob?.cancel()
        channelJob = scope.launch { preload(emotes, animBudget = (STRIP_CAP_CELLS * 0.85).toLong()) }
    }

    /** The signed-in user's own Twitch emotes (picker): files and stills only, plus a few animations. */
    fun preloadUser(emotes: List<Emote>) {
        userJob?.cancel()
        userJob = scope.launch { preload(emotes, animBudget = USER_ANIM_CELLS) }
    }

    private suspend fun preload(emotes: List<Emote>, animBudget: Long) {
        val list = emotes.distinctBy { it.url }.map { entry(it.url) }
        for (e in list) ensureStill(e, PRIO_PRELOAD)
        // Wait for the stills to finish (bounded), so animation sampling does not compete with them.
        var waited = 0
        while (waited < 120 && list.any { it.stillPending.get() }) { delay(500); waited++ }
        val todo = list.filter { it.animated != false }.toMutableList()
        while (todo.isNotEmpty() && isActiveJob()) {
            if (strips.bytes >= animBudget) break
            if (!active) break
            if (mode == EmoteMode.Fast || animPool.queue.size >= 2) { delay(400); continue }
            val next = todo.maxByOrNull { usage[it.url]?.get() ?: 0 } ?: break
            todo.remove(next)
            if (next.animated != false && next.strip == null) ensureAnimation(next, PRIO_PRELOAD)
        }
        pruneDisk(DISK_CAP_BYTES)
    }

    private fun isActiveJob() = scope.isActive

    // ------------------------------------------------------------------ workers

    private fun runStill(e: EmoteEntry) {
        if (!e.stillRunning.compareAndSet(false, true)) { e.stillRerun = true; return } // another task for this emote is decoding it
        var shed = false
        try {
            if (e.still != null || e.direct != null) return
            if (e.failedAt != 0L && clockMs() - e.failedAt < retryWait(e)) return
            // Chat outran the decoder: shed stale background work for lines that scrolled away (they are asked for again when shown).
            if (e.stillPrio >= PRIO_VISIBLE && e.visible.get() == 0 && stillPool.queue.size > SHED_QUEUE && clockMs() - e.stillQueuedAt > SHED_AFTER_MS) { shed = true; return }
            val file = download(e.url) ?: run { stillFailed(e); return }
            val d = decode(file) ?: run { stillFailed(e); return }
            e.failCount = 0
            if (e.animated == null) e.animated = d is AnimatedImageDrawable || ImageSniff.isAnimated(file.readBytes())
            val raw = stillOf(d)
            val bmp = fitToAtlas(raw, stillAtlas)
            val region = allocate(stillAtlas, bmp.width, bmp.height, 1)
            if (region != null) {
                stillAtlas.write(region, 0, bmp)
                e.still = region
                stills.put(e.url, e)
                e.bump()
            } else {
                misses.incrementAndGet()
                // No slot even after evicting everything not on screen: draw the decoded bitmap directly while it is shown.
                if (e.visible.get() > 0 && directCount.get() < MAX_DIRECT) {
                    val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
                    if (copy != null) {
                        directCount.incrementAndGet()
                        e.direct = copy.asImageBitmap()
                        e.bump()
                        if (e.visible.get() == 0) { e.direct = null; directCount.decrementAndGet() } // hidden meanwhile
                    }
                }
            }
            if (bmp !== raw) bmp.recycle()
            if (d !is BitmapDrawable) raw.recycle() // a bitmap we drew ourselves; a BitmapDrawable's is the decoder's, left to the GC
        } catch (_: Exception) {
            stillFailed(e)
        } finally {
            e.stillRunning.set(false)
            e.stillPending.set(false)
            // It came on screen while this task was shed or while it ran: ask again (the on-screen request had nowhere to queue).
            if ((shed || e.stillRerun) && e.visible.get() > 0 && e.still == null && e.direct == null) {
                e.stillRerun = false
                ensureStill(e, PRIO_SCREEN)
            }
        }
    }

    /** A still could not be fetched or decoded: wait a little (doubling), then try again if the emote is still on screen. */
    private fun stillFailed(e: EmoteEntry) {
        e.failedAt = clockMs().coerceAtLeast(1)
        val wait = retryWait(e)
        e.failCount++
        scope.launch {
            delay(wait + 50)
            if (e.visible.get() > 0 && e.still == null && e.direct == null) ensureStill(e, PRIO_SCREEN)
        }
    }

    private fun runAnimation(e: EmoteEntry) {
        try {
            if (e.strip != null || e.animated == false) return
            val file = download(e.url) ?: run { lastFailure = "download"; e.failedAt = clockMs().coerceAtLeast(1); return }
            val d = decode(file) ?: run { e.failedAt = clockMs().coerceAtLeast(1); return }
            if (d !is AnimatedImageDrawable) {
                // The file is animated but the platform decoder gave one frame (e.g. animated AVIF): nothing to play.
                lastFailure = if (ImageSniff.isAnimated(file.readBytes())) "decoder gave a still" else "plain image"
                e.animated = false; return
            }
            e.animated = true
            val frames = sample(d)
            if (frames == null) { e.animated = false; return }
            val strip = packStrip(frames) ?: return // the atlas has no room right now: try again later
            if (mode == EmoteMode.Fast || !active) { stripAtlas.free(strip); return } // chat got fast (or hidden) while this was sampling: drop it
            e.strip = strip
            strips.put(e.url, e)
            e.bump()
        } catch (x: Exception) {
            lastFailure = "anim ${x.javaClass.simpleName}"
            e.failedAt = clockMs().coerceAtLeast(1)
        } finally {
            e.animPending.set(false)
        }
    }

    /**
     * Plays [d] once, grabbing a frame every [FRAME_MS] into small bitmaps. The drawable is driven the way the
     * framework expects (a Drawable.Callback on the main thread schedules its frames); this worker thread waits for
     * the result. Failures are recorded in [lastFailure] for the debug line.
     */
    private fun sample(d: AnimatedImageDrawable): List<Bitmap>? {
        val w = d.intrinsicWidth.coerceAtLeast(1)
        val h = d.intrinsicHeight.coerceAtLeast(1)
        val main = samplers[(samplerNext.getAndIncrement() and 1)]
        val done = CountDownLatch(1)
        val frames = ArrayList<Bitmap>(32)
        val finished = AtomicBoolean(false)
        var tick: Runnable? = null
        fun finish() {
            if (!finished.compareAndSet(false, true)) return
            tick?.let { main.removeCallbacks(it) }
            runCatching { d.stop() }
            d.callback = null
            done.countDown()
        }
        val cb = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) {}
            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) { main.postAtTime(what, who, `when`) }
            override fun unscheduleDrawable(who: Drawable, what: Runnable) { main.removeCallbacks(what, who) }
        }
        main.post {
            try {
                d.callback = cb
                d.setBounds(0, 0, w, h)
                // Loop forever and register no AnimationCallback: AnimatedImageDrawable posts its end callbacks to the
                // main looper and is not thread-safe, so an end event racing an unregister crashes the app.
                d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                val t0 = SystemClock.uptimeMillis()
                val t = object : Runnable {
                    override fun run() {
                        if (finished.get()) return
                        capture(d, w, h, frames)
                        // The loop is complete when the first frame comes round again after the picture changed.
                        if (frames.size >= 4 && frames.last().sameAs(frames[0]) && frames.drop(1).dropLast(1).any { !it.sameAs(frames[0]) }) {
                            frames.removeAt(frames.size - 1)
                            finish(); return
                        }
                        if (frames.size >= MAX_FRAMES || SystemClock.uptimeMillis() - t0 > MAX_SAMPLE_MS) finish() else main.postDelayed(this, FRAME_MS)
                    }
                }
                tick = t
                d.start()
                t.run()
            } catch (e: Exception) {
                lastFailure = "sample ${e.javaClass.simpleName}"
                finish()
            }
        }
        if (!done.await(MAX_SAMPLE_MS + 2_000, TimeUnit.MILLISECONDS)) {
            main.post { finish() }
            lastFailure = "sample timeout"
        }
        val out = ArrayList(frames)
        if (out.size < 2) { lastFailure = "sample ${out.size} frame"; return null }
        // A decoder that never advanced gives N identical frames: not an animation.
        if (out.drop(1).none { !it.sameAs(out[0]) }) { lastFailure = "sample no motion"; return null }
        return out
    }

    /** Packs the sampled [frames] into the atlas (all in one page) and recycles them. Null when there is no room. */
    private fun packStrip(frames: List<Bitmap>): AtlasRegion? {
        val fitted = frames.map { fitToAtlas(it, stripAtlas) }
        val region = allocate(stripAtlas, fitted[0].width, fitted[0].height, fitted.size)
        if (region != null) fitted.forEachIndexed { i, f -> stripAtlas.write(region, i, f) }
        fitted.forEachIndexed { i, f -> if (f !== frames[i]) f.recycle() }
        frames.forEach { it.recycle() }
        return region
    }

    /**
     * Room for [count] images in the atlas. When every page is full, the least recently used strip, then still, is
     * dropped and the allocation retried, so the atlas never grows past its page budget.
     */
    private fun allocate(atlas: EmoteAtlas, w: Int, h: Int, count: Int): AtlasRegion? {
        if (atlas === stripAtlas) {
            repeat(MAX_EVICTIONS) {
                atlas.tryAlloc(w, h, count)?.let { return it }
                if (!strips.evictOldest()) return null
            }
            return null
        }
        // Stills: reuse free cells, then push out stills that are NOT on screen (oldest first); only when everything left is
        // on screen does the atlas grow to its second page, and after that the caller falls back to a direct bitmap.
        repeat(MAX_EVICTIONS) {
            atlas.tryAlloc(w, h, count, grow = false)?.let { return it }
            if (!stills.evictOldest()) return atlas.tryAlloc(w, h, count)
        }
        return atlas.tryAlloc(w, h, count)
    }

    private fun capture(d: Drawable, w: Int, h: Int, into: MutableList<Bitmap>) {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.draw(Canvas(bmp))
        into += bmp
    }

    private fun stillOf(d: Drawable): Bitmap {
        if (d is BitmapDrawable) return d.bitmap
        val w = d.intrinsicWidth.coerceAtLeast(1)
        val h = d.intrinsicHeight.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, h)
        d.draw(Canvas(bmp)) // an animated drawable that was never started draws its first frame
        return bmp
    }

    private fun decode(file: File): Drawable? = try {
        if (decodeOverride != null) decodeOverride.invoke(file) else ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val sw = info.size.width
            val sh = info.size.height
            if (sh > TARGET_H && sw > 0) {
                val s = TARGET_H.toFloat() / sh
                decoder.setTargetSize(((sw * s).roundToInt()).coerceIn(1, TARGET_H * 4), TARGET_H)
            }
        }
    } catch (x: Exception) {
        lastFailure = "decode ${x.javaClass.simpleName}"
        null
    }

    // ------------------------------------------------------------------ disk

    private fun fileFor(url: String): File {
        val md = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        return File(dir, md.joinToString("") { "%02x".format(it) })
    }

    /** The emote file, downloaded on first use. Null when offline or the CDN says no. */
    private fun download(url: String): File? {
        val f = fileFor(url)
        if (f.length() > 0) return f
        val tmp = File(dir, f.name + ".part" + Thread.currentThread().id)
        return try {
            http.newCall(Request.Builder().url(url).header("User-Agent", "FrostlineTV").build()).execute().use { r ->
                if (!r.isSuccessful) return null
                r.body.byteStream().use { input -> tmp.outputStream().use { out -> input.copyTo(out) } }
            }
            // Another worker (an animation decode of the same file) may have won the rename: its file is as good.
            if (tmp.renameTo(f) || f.length() > 0) f else null
        } catch (_: IOException) {
            null
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun pruneDisk(cap: Long) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= cap) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= cap) break
            total -= f.length()
            f.delete()
        }
    }

    /** Drops every file and every decoded frame (Settings > Clear image and emote cache). */
    fun clearAll() {
        stills.clear()
        strips.clear()
        entries.clear()
        dir.listFiles()?.forEach { it.delete() }
        diskBytes = 0
    }

    private class Task(val prio: Int, val order: Long, val body: () -> Unit) : Runnable, Comparable<Task> {
        override fun run() = body()
        // Lower prio first. Among on-screen / newly arrived work the NEWEST request goes first (the line just shown, not one that scrolled away);
        // background preloads stay first-in first-out.
        override fun compareTo(other: Task): Int = when {
            prio != other.prio -> prio.compareTo(other.prio)
            prio <= PRIO_VISIBLE -> other.order.compareTo(order)
            else -> order.compareTo(other.order)
        }
    }

    companion object {
        const val DIR_NAME = "emotes"
        /** Display height of decoded frames (px). Emotes are drawn at most about this tall in chat. */
        const val TARGET_H = 48
        const val FRAME_MS = 67L
        const val MAX_FRAMES = 40
        const val MAX_SAMPLE_MS = 4_000L
        const val BUSY_LINES = 12
        /** Stills kept at once (LRU): one 1024 px page of 50 px cells (400 cells). */
        const val STILL_CAP_ENTRIES = 600
        const val STILL_CELL = 50
        const val STRIP_CELL = 50
        const val STILL_PAGES = 2
        /** Direct-bitmap fallbacks held at once (only for emotes on screen with no atlas slot). */
        const val MAX_DIRECT = 64
        /** Stale background still tasks are shed when the queue is longer than this and they waited longer than [SHED_AFTER_MS]. */
        const val SHED_QUEUE = 48
        const val SHED_AFTER_MS = 3_000L
        /** Animation strips: two pages (800 cells), 700 used. Big enough that the hot set is not decoded again and again (CPU). */
        const val STRIP_CAP_CELLS = 700
        const val STRIP_PAGES = 2
        const val MAX_EVICTIONS = 64
        const val MAX_ENTRIES = 3000
        const val GLOBAL_ANIM_CELLS = 300L
        const val USER_ANIM_CELLS = 150L
        const val DISK_CAP_BYTES = 64L * 1024 * 1024
        const val RETRY_MS = 60_000L
        const val PRIO_SCREEN = -1
        const val PRIO_VISIBLE = 0
        const val PRIO_HOT = 1
        const val PRIO_PRELOAD = 2

        @Volatile var current: EmoteEngine? = null
            private set

        fun init(context: Context): EmoteEngine = current ?: synchronized(this) {
            current ?: EmoteEngine(context).also { current = it }
        }

        private fun pool(n: Int, name: String, prio: Int) = ThreadPoolExecutor(
            n, n, 0L, TimeUnit.MILLISECONDS, PriorityBlockingQueue<Runnable>(),
            ThreadFactory { r -> Thread(r, name).apply { priority = prio; isDaemon = true } },
        )

        fun dirBytes(dir: File): Long = dir.listFiles()?.sumOf { if (it.isFile) it.length() else dirBytes(it) } ?: 0L
    }
}
