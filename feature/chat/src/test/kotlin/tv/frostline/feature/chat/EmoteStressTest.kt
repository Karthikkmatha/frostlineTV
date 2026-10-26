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

/**
 * Caedrel's Worlds chat: a very fast chat with hundreds of distinct emotes used to leave blank gaps where emotes belong.
 * Cause: decode work ran first-in first-out (the newest, visible lines waited behind lines that had scrolled away), the still
 * LRU and the single atlas page evicted emotes that were on screen, and a failed allocation left the picture blank with nothing
 * to retry it. These tests drive the real engine with a fake CDN and a fake decoder at 30 messages/s and 400 emotes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmoteStressTest {
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
                // Widths 20..110 px so some emotes take several atlas cells.
                val w = 20 + (file.name.hashCode() and 0x7fffffff) % 91
                BitmapDrawable(Bitmap.createBitmap(w, 48, Bitmap.Config.ARGB_8888))
            },
        )
    }

    private fun emote(i: Int) = Emote("e$i", "Emote$i", "https://cdn.test/e$i/3x.webp", EmoteProvider.SevenTv, 1f)

    private fun message(seq: Long, emotes: List<Emote>) = ChatMessage(
        key = "m$seq", tsMs = seq, time = "12:00", userId = "u", login = "u", displayName = "u", colorArgb = 0xFFFFFFFF.toInt(),
        badges = emptyList(), parts = emotes.map { Part.EmoteRef(it) }, action = false, reply = null, header = null,
        highlight = false, seq = seq,
    )

    private fun awaitAll(entries: Collection<EmoteEntry>, timeoutMs: Long = 20_000): List<EmoteEntry> {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (entries.all { it.drawable }) return emptyList()
            Thread.sleep(20)
        }
        return entries.filter { !it.drawable }
    }

    @Test fun everyVisibleLineShowsItsEmotesInAVeryFastChat() {
        val eng = engine()
        val pool = (0 until 400).map(::emote)
        val rnd = Random(7)
        val window = ArrayDeque<List<Emote>>() // the lines on screen (a 1080p chat shows about 24)
        var seq = 0L
        // 30 s of chat at 30 msgs/s, in 100 ms frames of 3 messages.
        repeat(300) {
            clock.addAndGet(100)
            repeat(3) {
                val emotes = List(4) { pool[rnd.nextInt(pool.size)] }
                eng.noteMessage(message(++seq, emotes))
                window.addLast(emotes)
                for (e in emotes) { val en = eng.entry(e.url); eng.markVisible(en) }
                if (window.size > 24) {
                    for (e in window.removeFirst()) {
                        val en = eng.entry(e.url)
                        eng.markHidden(en)
                                            }
                }
            }
            Thread.sleep(3)
        }
        val visible = window.flatten().map { eng.entry(it.url) }.toSet()
        val blank = awaitAll(visible)
        assertTrue("visible emotes still blank: ${blank.size} of ${visible.size} (${eng.atlasLine()})", blank.isEmpty())
        // They stay drawable: nothing on screen was evicted for something else.
        assertTrue(visible.all { it.drawable })
    }

    @Test fun emotesOnScreenAreNeverEvictedForOthers() {
        val eng = engine()
        val onScreen = (0 until 300).map { eng.entry(emote(it).url) }
        onScreen.forEach { eng.markVisible(it) }
        assertTrue(awaitAll(onScreen).isEmpty())
        // 1500 other emotes stream through the atlas (more than both pages hold).
        val others = (1000 until 2500).map { eng.entry(emote(it).url) }
        others.forEach { eng.ensureStill(it, EmoteEngine.PRIO_VISIBLE) }
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end && others.any { it.stillPending.get() }) Thread.sleep(20)
        assertTrue("an on-screen emote lost its picture", onScreen.all { it.still != null })
    }

    @Test fun moreVisibleEmotesThanTheAtlasHoldsFallBackToDirectBitmaps() {
        val eng = engine()
        // 900 visible emotes of up to 3 cells each overflow both pages (800 cells).
        val onScreen = (0 until 900).map { eng.entry(emote(it).url) }
        onScreen.forEach { eng.markVisible(it) }
        val end = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < end && onScreen.any { it.stillPending.get() }) Thread.sleep(20)
        assertEquals(2, eng.stillAtlas.pageCount)
        // Whatever the atlas could not take is drawn directly, up to the cap (900 on screen is far beyond a real screen).
        val direct = onScreen.count { it.direct != null }
        assertTrue("direct=$direct", direct in 1..EmoteEngine.MAX_DIRECT)
        assertTrue(onScreen.count { it.still != null } > 200)
        onScreen.forEach { eng.markHidden(it) }
        assertTrue(onScreen.all { it.direct == null })
    }
}
