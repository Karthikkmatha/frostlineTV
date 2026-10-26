package tv.frostline.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LineBuilderTest {
    private val kappa = Emote("1", "Kappa", "https://e/1", EmoteProvider.SevenTv, aspect = 1f)
    private val wide = Emote("2", "WideOne", "https://e/2", EmoteProvider.Bttv, aspect = 2f)

    private fun msg(vararg parts: Part, badges: List<BadgeImage> = emptyList(), action: Boolean = false) = ChatMessage(
        key = "k1", tsMs = 0, time = "12:34", userId = "1", login = "bob", displayName = "Bob", colorArgb = 0xFF00FF00.toInt(),
        badges = badges, parts = parts.toList(), action = action, reply = null, header = null, highlight = false,
    )

    @Test fun oneSlotPerBadgeAndEmoteInOrder() {
        val m = msg(Part.Text("hi "), Part.EmoteRef(kappa), Part.Text(" and "), Part.EmoteRef(wide), badges = listOf(BadgeImage("vip", "https://b/vip")))
        val p = LineBuilder.prepare(m, 14, timestamps = false)
        assertEquals(listOf("vip", "Kappa", "WideOne"), p.images.map { it.description })
        assertEquals(listOf(false, true, true), p.images.map { it.isEmote })
        assertEquals(3, p.placeholders.size)
        // Each placeholder sits on exactly one object-replacement character of the text.
        for (r in p.placeholders) assertEquals(LineBuilder.OBJECT, p.text.text.substring(r.start, r.end))
        assertEquals(3, p.text.text.count { it == '￼' })
        assertEquals("Bob: hi Kappa and WideOne", p.spoken)
    }

    @Test fun timestampsAndActionStyle() {
        val p = LineBuilder.prepare(msg(Part.Text("waves"), action = true), 14, timestamps = true)
        assertEquals("12:34  Bob waves", p.text.text)
        assertEquals("12:34 Bob waves", p.spoken)
        assertTrue(p.timestamps)
    }

    @Test fun emoteWidthFollowsAspectAndIsClamped() {
        val p = LineBuilder.prepare(msg(Part.EmoteRef(kappa), Part.EmoteRef(wide)), 20, timestamps = false)
        val h = 20 * 1.6f
        assertEquals(h, p.placeholders[0].item.width.value, 0.01f)
        assertEquals(h * 2, p.placeholders[1].item.width.value, 0.01f)
        assertEquals(h, p.placeholders[0].item.height.value, 0.01f)
    }

    @Test fun preparedIsCachedPerSettingAndRebuiltOnChange() {
        val m = msg(Part.Text("x"))
        val a = m.prepared(14, false)
        assertSame(a, m.prepared(14, false))
        val b = m.prepared(18, false)
        assertEquals(18, b.fontSp)
        assertSame(b, m.prepared(18, false))
        assertEquals(true, m.prepared(18, true).timestamps)
    }

    @Test fun codeLengthFilterSkipsImpossibleWordsButKeepsEmotes() {
        val ctx = ResolveContext(mapOf("Kappa" to kappa, "WideOne" to wide), emptyMap())
        assertEquals(5, ctx.minCodeLen)
        assertEquals(7, ctx.maxCodeLen)
        val raw = RawMessage("i", 0, "1", "bob", "Bob", null, emptyList(), "a Kappa bb WideOne toolongtobeanemote Kappa", false, emptyList(), null)
        val parts = MessageBuilder.build(raw, ctx).parts
        assertEquals(listOf("Kappa", "WideOne", "Kappa"), parts.filterIsInstance<Part.EmoteRef>().map { it.emote.code })
        assertEquals("a ", (parts[0] as Part.Text).text)
    }

    @Test fun emoteSetIndexIsBuiltOncePerSelection() {
        val sets = EmoteSets(sevenTv = listOf(kappa), bttv = listOf(wide))
        val a = sets.index(true, true, true)
        assertSame(a, sets.index(true, true, true))
        assertEquals(setOf("Kappa"), sets.index(true, false, false).keys)
        assertEquals(setOf("WideOne"), sets.index(false, true, false).keys)
        assertEquals(setOf("Kappa", "WideOne"), a.keys)
    }
}
