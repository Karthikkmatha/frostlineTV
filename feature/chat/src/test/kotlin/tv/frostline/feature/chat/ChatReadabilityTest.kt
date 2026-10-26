package tv.frostline.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReadabilityTest {
    private fun hue(argb: Int): Double {
        val r = ((argb shr 16) and 0xFF) / 255.0
        val g = ((argb shr 8) and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        val mx = maxOf(r, g, b)
        val d = mx - minOf(r, g, b)
        if (d == 0.0) return 0.0
        val h = when (mx) { r -> ((g - b) / d + 6) % 6; g -> (b - r) / d + 2; else -> (r - g) / d + 4 }
        return h * 60
    }

    @Test fun darkNamesAreLightenedKeepingTheirHue() {
        for (dark in intArrayOf(0xFF0000FF.toInt(), 0xFF1E3A8A.toInt(), 0xFF800000.toInt(), 0xFF004D00.toInt())) {
            val c = ChatText.readable(dark)
            assertTrue(ChatText.luminance(c) >= 0.25)
            assertEquals(hue(dark), hue(c), 4.0)
        }
    }

    @Test fun mentionsMatchWholeWordsOnly() {
        assertEquals("karthik", ChatText.mentionOf("hey @Karthik nice", "karthik"))
        assertEquals("karthik", ChatText.mentionOf("@karthik", "karthik"))
        assertNull(ChatText.mentionOf("hey @karthikk", "karthik"))
        assertNull(ChatText.mentionOf("karthik without at", "karthik"))
        assertNull(ChatText.mentionOf("@anyone", null))
    }

    @Test fun mentionedLinesAreBoldedAndMarked() {
        val ctx = ResolveContext(emptyMap(), emptyMap(), ownLogin = "me")
        val raw = RawMessage("1", 0L, "9", "other", "Other", 0xFFFFFFFF.toInt(), emptyList(), "hi @Me!", false, emptyList(), null)
        val m = MessageBuilder.build(raw, ctx)
        assertNotNull(m.mentionLogin)
        val text = m.prepared(14, false).text
        assertTrue(text.spanStyles.any { it.item.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold && text.text.substring(it.start, it.end) == "@Me" })
    }
}
