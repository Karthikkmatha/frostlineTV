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

/** All animation frames of one emote at display size. Played by index from the shared clock; never decoded again per frame. */
class FrameStrip(val frames: Array<Bitmap>) {
    val images: Array<ImageBitmap> = Array(frames.size) { frames[it].asImageBitmap() }
    val bytes: Long = frames.sumOf { it.allocationByteCount.toLong() }
}

/** One emote image: its still frame (always wanted) and, when animated and hot, its [FrameStrip]. */
class EmoteEntry(val url: String) {
    @Volatile var still: ImageBitmap? = null
    @Volatile var stillBytes: Long = 0
    @Volatile var strip: FrameStrip? = null
    /** null = not known yet, false = a plain image. */
    @Volatile var animated: Boolean? = null
    @Volatile var failedAt: Long = 0
    val stillPending = AtomicBoolean(false)
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
) {
    private val appContext = context.applicationContext
    private val dir = File(appContext.cacheDir, DIR_NAME).also { it.mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val entries = ConcurrentHashMap<String, EmoteEntry>()
    private val usage = ConcurrentHashMap<String, AtomicInteger>()
    private val tracker = EmoteRateTracker()
    private val order = AtomicLong()

    private val stills = WeightedLru<String, EmoteEntry>(STILL_CAP_BYTES, { it.stillBytes }) { _, e -> e.still = null; e.stillBytes = 0 }
    private val strips = WeightedLru<String, EmoteEntry>(HOT_CAP_BYTES, { it.strip?.bytes ?: 0 }) { _, e -> e.strip = null }

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
    val memoryBytes: Long get() = stills.bytes + strips.bytes
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
            var n = 0
            while (isActive) {
                val t = clockMs()
                mode = tracker.update(t)
                ratePerSec = tracker.rate(t)
                if (n++ % 5 == 0) diskBytes = dirBytes(dir)
                delay(1_000)
            }
        }
    }

    /** One line for the debug overlay: mode, rate, hot animations, memory and disk. */
    fun debugLine(): String =
        "emotes   ${mode.label} ${"%.1f".format(ratePerSec)}/s, ${strips.size} animated, mem ${memoryBytes / 1_048_576} MB, disk ${diskBytes / 1_048_576} MB" + if (lastFailure.isNotEmpty()) ", last: $lastFailure" else ""

    fun entry(url: String): EmoteEntry = entries.getOrPut(url) { EmoteEntry(url) }

    // ------------------------------------------------------------------ requests

    /** Makes sure [e] has a still frame (download + decode). Priority: lower runs first. */
    fun ensureStill(e: EmoteEntry, priority: Int = PRIO_VISIBLE) {
        if (e.still != null) { stills.get(e.url); return }
        if (clockMs() - e.failedAt < RETRY_MS && e.failedAt != 0L) return
        if (e.stillPending.compareAndSet(false, true)) stillPool.execute(Task(priority, order.incrementAndGet()) { runStill(e) })
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
     * (up to [GLOBAL_ANIM_BYTES]), most used first.
     */
    fun preloadGlobals(emotes: List<Emote>) {
        globalsJob?.cancel()
        globalsJob = scope.launch { preload(emotes, animBudget = GLOBAL_ANIM_BYTES) }
    }

    /** Stream open: that channel's emote files + stills first, then its animations in the background. */
    fun preloadChannel(emotes: List<Emote>) {
        channelJob?.cancel()
        channelJob = scope.launch { preload(emotes, animBudget = (HOT_CAP_BYTES * 0.85).toLong()) }
    }

    /** The signed-in user's own Twitch emotes (picker): files and stills only, plus a few animations. */
    fun preloadUser(emotes: List<Emote>) {
        userJob?.cancel()
        userJob = scope.launch { preload(emotes, animBudget = STILL_CAP_BYTES / 3) }
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
        try {
            if (e.still != null) return
            val file = download(e.url) ?: run { e.failedAt = clockMs().coerceAtLeast(1); return }
            val d = decode(file) ?: run { e.failedAt = clockMs().coerceAtLeast(1); return }
            val bmp = stillOf(d)
            if (e.animated == null) e.animated = d is AnimatedImageDrawable || ImageSniff.isAnimated(file.readBytes())
            e.still = bmp.asImageBitmap()
            e.stillBytes = bmp.allocationByteCount.toLong()
            stills.put(e.url, e)
            e.bump()
        } catch (_: Exception) {
            e.failedAt = clockMs().coerceAtLeast(1)
        } finally {
            e.stillPending.set(false)
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
            val strip = sample(d)
            if (strip == null) { e.animated = false; return }
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
    private fun sample(d: AnimatedImageDrawable): FrameStrip? {
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
        val end = object : Animatable2.AnimationCallback() {
            override fun onAnimationEnd(drawable: Drawable?) {
                if (frames.isNotEmpty()) capture(d, w, h, frames)
                finish()
            }
        }
        main.post {
            try {
                d.callback = cb
                d.setBounds(0, 0, w, h)
                d.repeatCount = 0 // play once; the strip is the loop
                d.registerAnimationCallback(end)
                val t0 = SystemClock.uptimeMillis()
                val t = object : Runnable {
                    override fun run() {
                        if (finished.get()) return
                        capture(d, w, h, frames)
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
        main.post { runCatching { d.unregisterAnimationCallback(end) } }
        val out = ArrayList(frames)
        if (out.size < 2) { lastFailure = "sample ${out.size} frame"; return null }
        // A decoder that never advanced gives N identical frames: not an animation.
        if (out.drop(1).none { !it.sameAs(out[0]) }) { lastFailure = "sample no motion"; return null }
        return FrameStrip(out.toTypedArray())
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
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
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
            if (tmp.renameTo(f)) f else null
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
        override fun compareTo(other: Task): Int = if (prio != other.prio) prio.compareTo(other.prio) else order.compareTo(other.order)
    }

    companion object {
        const val DIR_NAME = "emotes"
        /** Display height of decoded frames (px). Emotes are drawn at most about this tall in chat. */
        const val TARGET_H = 48
        const val FRAME_MS = 67L
        const val MAX_FRAMES = 40
        const val MAX_SAMPLE_MS = 4_000L
        const val BUSY_LINES = 12
        const val STILL_CAP_BYTES = 8L * 1024 * 1024
        /** Strips + stills together stay within 24 MB. */
        const val HOT_CAP_BYTES = 16L * 1024 * 1024
        const val GLOBAL_ANIM_BYTES = 8L * 1024 * 1024
        const val DISK_CAP_BYTES = 64L * 1024 * 1024
        const val RETRY_MS = 60_000L
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
