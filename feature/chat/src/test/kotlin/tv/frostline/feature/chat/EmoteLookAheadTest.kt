package tv.frostline.feature.chat

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** The look-ahead against the real emote engine (fake CDN, fake decoder): queued lines are decoded early and never evicted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmoteLookAheadTest {
    private val clock = AtomicLong(1_000)

    private fun engine(): EmoteEngine {
        val cdn = Interceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("emote".toResponseBody("image/png".toMediaType())).build()
        }
        return EmoteEngine(
            RuntimeEnvironment.getApplication(),
            http = OkHttpClient.Builder().addInterceptor(cdn).build(),
            clockMs = { clock.get() },
            decodeOverride = { file ->
                val w = 20 + (file.name.hashCode() and 0x7fffffff) % 60 // 20..79 px: one or two cells
                BitmapDrawable(Bitmap.createBitmap(w, 48, Bitmap.Config.ARGB_8888))
            },
        )
    }

    private fun emote(i: Int) = Emote("e$i", "Emote$i", "https://cdn.test/e$i/3x.webp", EmoteProvider.SevenTv, 1f)

    private fun message(id: String, emotes: List<Emote>) = ChatMessage(
        key = id, tsMs = 0, time = "12:00", userId = "u", login = "u", displayName = "u", colorArgb = 0xFFFFFFFF.toInt(),
        badges = emptyList(), parts = emotes.map { Part.EmoteRef(it) }, action = false, reply = null, header = null, highlight = false,
    )

    private fun until(timeoutMs: Long = 20_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(10) }
        return cond()
    }

    @Test fun everyLineIsReadyAtReleaseAtThirtyMessagesASecondWithAFourHundredEmoteVocabulary() {
        val eng = engine()
        val q = LookAheadQueue(ahead = { eng })
        val pool = (0 until 400).map(::emote)
        val rnd = Random(11)
        val used = HashSet<String>()
        val total = 600 // 20 s of chat at 30 msgs/s
        var sim = 0L
        for (n in 0 until total) {
            sim = n * 33L
            clock.set(1_000 + sim)
            val m = message("m$n", List(4) { pool[rnd.nextInt(pool.size)] })
            m.imageUrls.forEach { used += it }
            q.enqueue(m, sim, 10_000) // the 10 s hold of a Stable stream
            eng.noteMessage(m, stillsRequested = true)
            if (n % 3 == 2) Thread.sleep(3)
        }
        // Ten seconds of hold later the first lines are due: by then everything asked for has been decoded.
        assertTrue("decoder did not catch up: ${eng.atlasLine()}", until { used.all { eng.entry(it).drawable } })
        var released = 0
        var t = 10_000L
        while (q.size > 0 && t < 40_000) {
            clock.set(1_000 + t)
            released += q.poll(t).size
            t += 33
        }
        assertEquals(total, released)
        assertTrue("ready ${q.readyPercent}% late ${q.late} (${eng.atlasLine()})", q.readyPercent >= 99)
    }

    @Test fun emotesOfQueuedLinesAreNeverEvictedWhileTheyWait() {
        val eng = engine()
        val q = LookAheadQueue(ahead = { eng })
        val queuedEmotes = (0 until 250).map(::emote)
        queuedEmotes.chunked(4).forEachIndexed { i, c -> q.enqueue(message("q$i", c), 0, 12_000) }
        val entries = queuedEmotes.map { eng.entry(it.url) }
        assertTrue(until { entries.all { it.drawable } })
        // 1500 other emotes stream through the atlas, more than both pages hold.
        val others = (1000 until 2500).map { eng.entry(emote(it).url) }
        others.forEach { eng.ensureStill(it, EmoteEngine.PRIO_VISIBLE) }
        until(30_000) { others.none { it.stillPending.get() } }
        assertTrue("a queued emote lost its picture", entries.all { it.still != null })
        // Once the lines are released and the protection runs out, they may go.
        q.poll(12_000)
        clock.addAndGet(5_000)
        eng.sweepQueuedReleases()
        assertTrue(entries.all { it.queued.get() == 0 })
    }

    @Test fun aQueuedLineAndAVisibleLineBothProtect() {
        val eng = engine()
        val e = eng.entry(emote(1).url)
        e.queued.incrementAndGet()
        eng.markVisible(e)
        eng.markHidden(e)
        assertEquals(1, e.queued.get())
        assertTrue(until { e.still != null })
    }

    @Test fun aFailedPictureDoesNotHoldALineBack() {
        val eng = engine()
        val e = eng.entry("https://cdn.test/never")
        e.failCount = 1
        assertTrue(eng.isReady(message("x", listOf(Emote("n", "N", "https://cdn.test/never", EmoteProvider.SevenTv)))))
    }

    @Test fun theMostUsedEmotesArePinned() {
        val eng = engine()
        val hot = (0 until 50).map(::emote)
        val cold = (100 until 160).map(::emote)
        repeat(20) { eng.noteMessage(message("h$it", hot)) }
        eng.noteMessage(message("c", cold))
        eng.refreshHotSet()
        assertTrue(hot.all { eng.entry(it.url).pinned })
        assertTrue(cold.none { eng.entry(it.url).pinned })
    }

    @Test fun theSecondStillPageIsGivenBackAfterAMinuteUnused() {
        val eng = engine()
        val onScreen = (0 until 900).map { eng.entry(emote(it).url) }
        onScreen.forEach { eng.markVisible(it) }
        until(30_000) { onScreen.none { it.stillPending.get() } }
        assertEquals(2, eng.stillAtlas.pageCount)
        onScreen.forEach { eng.markHidden(it) }
        clock.addAndGet(30_000)
        eng.trimExtraStillPage()
        assertEquals("not yet: 30 s", 2, eng.stillAtlas.pageCount)
        clock.addAndGet(31_000)
        eng.trimExtraStillPage()
        assertEquals(1, eng.stillAtlas.pageCount)
        assertTrue(onScreen.all { it.direct == null })
    }

    @Test fun theSecondPageStaysWhileSomethingOnItIsStillShown() {
        val eng = engine()
        val onScreen = (0 until 900).map { eng.entry(emote(it).url) }
        onScreen.forEach { eng.markVisible(it) }
        until(30_000) { onScreen.none { it.stillPending.get() } }
        clock.addAndGet(120_000)
        eng.trimExtraStillPage()
        assertEquals(2, eng.stillAtlas.pageCount) // everything is still on screen
    }
}
