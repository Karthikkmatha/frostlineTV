package tv.frostline.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageBuilderTest {
    private fun e(code: String, p: EmoteProvider = EmoteProvider.SevenTv) = Emote("id-$code", code, "https://x/$code", p)
    private val third = mapOf("PogU" to e("PogU"), "monkaS" to e("monkaS", EmoteProvider.Bttv))

    private fun desc(parts: List<Part>) = parts.joinToString("|") {
        when (it) {
            is Part.Text -> "T:" + it.text
            is Part.EmoteRef -> "E:" + it.emote.code
        }
    }

    @Test fun plainTextIsOnePart() {
        assertEquals("T:hello world", desc(MessageBuilder.tokenize("hello world", emptyList(), emptyMap())))
        assertEquals("", desc(MessageBuilder.tokenize("", emptyList(), third)))
    }

    @Test fun twitchRangesBecomeEmotes() {
        val parts = MessageBuilder.tokenize("Kappa Keepo Kappa", ChatEventParser.parseEmotes("25:0-4,12-16/1902:6-10"), emptyMap())
        assertEquals("E:Kappa|T: |E:Keepo|T: |E:Kappa", desc(parts))
        assertEquals("https://static-cdn.jtvnw.net/emoticons/v2/25/default/dark/2.0", (parts[0] as Part.EmoteRef).emote.url)
    }

    @Test fun sameTwitchIdIsTheSameObject() {
        val a = MessageBuilder.tokenize("Kappa", ChatEventParser.parseEmotes("25:0-4"), emptyMap())[0] as Part.EmoteRef
        val b = MessageBuilder.tokenize("hi Kappa", ChatEventParser.parseEmotes("25:3-7"), emptyMap())[1] as Part.EmoteRef
        assertSame(a.emote, b.emote)
    }

    @Test fun thirdPartyWordsAreReplaced() {
        assertEquals("T:so |E:PogU|T:  nice |E:monkaS", desc(MessageBuilder.tokenize("so PogU  nice monkaS", emptyList(), third)))
    }

    @Test fun thirdPartyMatchIsWholeWordAndCaseSensitive() {
        assertEquals("T:PogUU pogu xPogU", desc(MessageBuilder.tokenize("PogUU pogu xPogU", emptyList(), third)))
    }

    @Test fun twitchAndThirdPartyMix() {
        val parts = MessageBuilder.tokenize("Kappa PogU hi", ChatEventParser.parseEmotes("25:0-4"), third)
        assertEquals("E:Kappa|T: |E:PogU|T: hi", desc(parts))
    }

    @Test fun rangesAreCodePointIndicesNotUtf16() {
        // The first emoji is one code point but two chars; "Kappa" starts at code point 2.
        val text = "\uD83D\uDE00 Kappa"
        val parts = MessageBuilder.tokenize(text, ChatEventParser.parseEmotes("25:2-6"), emptyMap())
        assertEquals("T:\uD83D\uDE00 |E:Kappa", desc(parts))
    }

    @Test fun invalidOrOverlappingRangesAreIgnored() {
        assertEquals("T:Kappa", desc(MessageBuilder.tokenize("Kappa", ChatEventParser.parseEmotes("25:0-40"), emptyMap())))
        val overlap = MessageBuilder.tokenize("Kappa", listOf(EmoteRange("25", 0, 4), EmoteRange("26", 2, 3)), emptyMap())
        assertEquals("E:Kappa", desc(overlap))
    }

    @Test fun fallbackMapUsedForLocalEcho() {
        val parts = MessageBuilder.tokenize("hey LUL", emptyList(), emptyMap(), mapOf("LUL" to e("LUL", EmoteProvider.Twitch)))
        assertEquals("T:hey |E:LUL", desc(parts))
    }

    @Test fun buildResolvesBadgesAndReadableColor() {
        val raw = RawMessage(
            "id", 0L, "1", "dark", "Dark", 0xFF000033.toInt(),
            listOf(Badge("moderator", "1"), Badge("unknown", "1"), Badge("vip", "1"), Badge("subscriber", "3"), Badge("bits", "1")),
            "hi", false, emptyList(), null,
        )
        val ctx = ResolveContext(emptyMap(), mapOf("moderator/1" to "u1", "vip/1" to "u2", "subscriber/3" to "u3", "bits/1" to "u4"))
        val m = MessageBuilder.build(raw, ctx)
        assertEquals(listOf("moderator", "vip", "subscriber"), m.badges.map { it.set }) // unknown skipped, capped at 3
        assertTrue(ChatText.luminance(m.colorArgb) >= 0.25)
    }

    @Test fun readableLeavesBrightColorsAlone() {
        assertEquals(0xFFFFD866.toInt(), ChatText.readable(0xFFFFD866.toInt()))
    }

    @Test fun defaultColorIsStableAndReadable() {
        assertEquals(ChatText.defaultColor("abc"), ChatText.defaultColor("abc"))
        assertTrue(ChatText.luminance(ChatText.defaultColor("abc")) >= 0.25)
    }
}
