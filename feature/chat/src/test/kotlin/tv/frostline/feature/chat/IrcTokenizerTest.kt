package tv.frostline.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The index-based tokenizer must produce exactly what [IrcParser] + [ChatEventParser] produce. */
class IrcTokenizerTest {
    private val tokenizer = IrcTokenizer()

    private fun fast(frame: String): List<ChatEvent> = ArrayList<ChatEvent>().also { out -> tokenizer.parseFrame(frame, 1_000L) { out += it } }
    private fun slow(frame: String): List<ChatEvent> = IrcParser.parseFrame(frame).mapNotNull { ChatEventParser.parse(it, 1_000L) }

    private fun assertSameMessage(a: RawMessage, b: RawMessage) {
        // Generated ids differ by counter; everything else must match.
        if (!b.id.startsWith("m-")) assertEquals(b.id, a.id) else assertTrue(a.id.startsWith("m-"))
        assertEquals(b.tsMs, a.tsMs)
        assertEquals(b.userId, a.userId)
        assertEquals(b.login, a.login)
        assertEquals(b.displayName, a.displayName)
        assertEquals(b.colorArgb, a.colorArgb)
        assertEquals(b.badges, a.badges)
        assertEquals(b.text, a.text)
        assertEquals(b.action, a.action)
        assertEquals(b.emotes, a.emotes)
        assertEquals(b.reply, a.reply)
        assertEquals(b.firstMessage, a.firstMessage)
        assertEquals(b.highlighted, a.highlighted)
    }

    private fun same(frame: String) {
        val a = fast(frame)
        val b = slow(frame)
        assertEquals("event count for: $frame", b.size, a.size)
        for (i in a.indices) {
            val x = a[i]
            val y = b[i]
            if (x is ChatEvent.Message && y is ChatEvent.Message) assertSameMessage(x.message, y.message)
            else if (x is ChatEvent.System && y is ChatEvent.System) { assertEquals(y.text, x.text); assertEquals(y.kind, x.kind) }
            else assertEquals(y, x)
        }
    }

    @Test fun fullPrivmsg() = same(
        "@badge-info=subscriber/8;badges=broadcaster/1,subscriber/0;color=#FF69B4;display-name=Foo\\sBar;emotes=25:0-4,12-16/1902:6-10;" +
            "first-msg=0;flags=;id=abc-123;mod=0;room-id=1;subscriber=1;tmi-sent-ts=1700000000000;turbo=0;user-id=42;user-type= " +
            ":foo!foo@foo.tmi.twitch.tv PRIVMSG #chan :Kappa Keepo Kappa hi\r\n",
    )

    @Test fun parsedFields() {
        val m = (fast("@badges=vip/1;color=#00ff00;display-name=Zed;id=i1;tmi-sent-ts=77;user-id=9;emotes=25:0-4 :zed!zed@x PRIVMSG #c :Kappa yo")[0] as ChatEvent.Message).message
        assertEquals("i1", m.id)
        assertEquals(77L, m.tsMs)
        assertEquals("zed", m.login)
        assertEquals("Zed", m.displayName)
        assertEquals(0xFF00FF00.toInt(), m.colorArgb)
        assertEquals(listOf(Badge("vip", "1")), m.badges)
        assertEquals(listOf(EmoteRange("25", 0, 4)), m.emotes)
        assertEquals("Kappa yo", m.text)
    }

    @Test fun action() = same("@id=a;display-name=A :a!a@a PRIVMSG #c :\u0001ACTION waves\u0001")
    @Test fun actionWithoutTerminator() = same("@id=a :a!a@a PRIVMSG #c :\u0001ACTION waves")
    @Test fun reply() = same("@id=r;display-name=R;reply-parent-msg-id=p1;reply-parent-user-login=bob;reply-parent-display-name=;reply-parent-msg-body=hello\\sthere\\:x :r!r@r PRIVMSG #c :@bob hi")
    @Test fun replyDisplayName() = same("@id=r;reply-parent-msg-id=p1;reply-parent-user-login=bob;reply-parent-display-name=Bob;reply-parent-msg-body=yo :r!r@r PRIVMSG #c :@bob hi")
    @Test fun highlightedAndFirst() = same("@id=h;first-msg=1;msg-id=highlighted-message :h!h@h PRIVMSG #c :look at me")
    @Test fun otherMsgIdIsNotHighlighted() = same("@id=h;msg-id=highlighted-messagex :h!h@h PRIVMSG #c :look at me")
    @Test fun noTags() = same(":foo!foo@x PRIVMSG #c :plain text")
    @Test fun missingIdAndTimestamp() = same("@display-name=;color= :nobody!n@n PRIVMSG #c :hi")
    @Test fun colonsInText() = same("@id=c :n!n@n PRIVMSG #c :look: a : b  c")
    @Test fun badTagValues() = same("@id=b;color=#12;tmi-sent-ts=abc;badges=junk,a/1,/2;emotes=b:10-12/a:0-3,5-x,7-4/:1-2/c :n!n@n PRIVMSG #c :text")
    @Test fun repeatedTagKeepsLast() = same("@id=first;id=second :n!n@n PRIVMSG #c :hi")
    @Test fun escapedEverywhere() = same("@id=e;display-name=a\\sb\\\\c\\n :n!n@n PRIVMSG #c :hi")
    @Test fun unicodeText() = same("@id=u;emotes=1:3-4 :n!n@n PRIVMSG #c :😀 hi 😀")

    @Test fun otherCommandsTakeTheGeneralRoute() {
        same("PING :tmi.twitch.tv\r\n")
        same(":tmi.twitch.tv 001 me :Welcome, GLHF!\r\n")
        same("@emote-only=1;slow=30 :tmi.twitch.tv ROOMSTATE #c\r\n")
        same("@msg-id=raid;msg-param-displayName=Big;msg-param-viewerCount=42;id=n1 :tmi.twitch.tv USERNOTICE #c\r\n")
        same("@target-user-id=5;tmi-sent-ts=9 :tmi.twitch.tv CLEARCHAT #c :baduser\r\n")
        same(":tmi.twitch.tv RECONNECT\r\n")
        same(":tmi.twitch.tv NOTICE * :Login authentication failed\r\n")
    }

    @Test fun severalLinesInOneFrame() {
        val frame = "@id=1 :a!a@a PRIVMSG #c :one\r\n:tmi 001 me :Welcome\r\n\r\n@id=2 :b!b@b PRIVMSG #c :two\r\nPING :x\r\n"
        assertEquals(4, fast(frame).size)
        same(frame)
    }

    @Test fun garbageIsIgnored() {
        for (junk in listOf("", "   ", "\r\n", "@only=tags", "@a=b :p", ":prefixonly", "PRIVMSG #c :no prefix", "@id=1 :n!n@n PRIVMSG #c")) {
            assertEquals("junk: '$junk'", slow(junk).size, fast(junk).size)
        }
    }

    @Test fun lineWithoutCrlfAtTheEndOfAFrame() = same("@id=z :n!n@n PRIVMSG #c :last")
}
