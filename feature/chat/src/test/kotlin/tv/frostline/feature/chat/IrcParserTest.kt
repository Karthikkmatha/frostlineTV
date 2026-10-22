package tv.frostline.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IrcParserTest {
    private fun ev(line: String) = ChatEventParser.parse(IrcParser.parse(line)!!, nowMs = 1_000L)

    // ---- raw line parsing ----

    @Test fun parsesTagsPrefixCommandParamsAndTrailing() {
        val m = IrcParser.parse("@a=1;b=;c :nick!nick@nick.tmi.twitch.tv PRIVMSG #chan :hello there")!!
        assertEquals(mapOf("a" to "1", "b" to "", "c" to ""), m.tags)
        assertEquals("nick!nick@nick.tmi.twitch.tv", m.prefix)
        assertEquals("nick", m.nick)
        assertEquals("PRIVMSG", m.command)
        assertEquals(listOf("#chan"), m.params)
        assertEquals("hello there", m.trailing)
    }

    @Test fun parsesLineWithoutTagsOrTrailing() {
        val m = IrcParser.parse(":tmi.twitch.tv CAP * ACK")!!
        assertEquals("CAP", m.command)
        assertEquals(listOf("*", "ACK"), m.params)
        assertNull(m.trailing)
    }

    @Test fun trailingMayContainColonsAndSpaces() {
        val m = IrcParser.parse(":n!n@n PRIVMSG #c :look: a : b  c")!!
        assertEquals("look: a : b  c", m.trailing)
    }

    @Test fun commandIsUppercased() {
        assertEquals("PING", IrcParser.parse("ping :tmi.twitch.tv")!!.command)
    }

    @Test fun tagEscapesAreDecoded() {
        val tags = IrcParser.parseTags("system-msg=a\\sb\\:c\\\\d\\ne;x=y\\")
        assertEquals("a b;c\\d\ne", tags["system-msg"])
        assertEquals("y", tags["x"]) // dangling backslash dropped
    }

    @Test fun malformedLinesReturnNull() {
        assertNull(IrcParser.parse(""))
        assertNull(IrcParser.parse("   "))
        assertNull(IrcParser.parse("@only=tags"))
        assertNull(IrcParser.parse(":prefixonly"))
        assertNull(IrcParser.parse("@a=b :p"))
    }

    @Test fun frameWithSeveralLinesIsSplit() {
        val msgs = IrcParser.parseFrame(":tmi 001 me :Welcome\r\n:tmi 002 me :Host\r\n\r\nPING :x\r\n")
        assertEquals(listOf("001", "002", "PING"), msgs.map { it.command })
    }

    // ---- PRIVMSG ----

    private val privmsg = "@badge-info=subscriber/8;badges=broadcaster/1,subscriber/6;color=#1E90FF;display-name=Some\\sOne;" +
        "emotes=25:0-4,12-16/1902:6-10;first-msg=0;id=abc-123;mod=0;room-id=1;subscriber=1;tmi-sent-ts=1700000000000;" +
        "user-id=42 :someone!someone@someone.tmi.twitch.tv PRIVMSG #chan :Kappa Keepo Kappa"

    @Test fun privmsgFields() {
        val e = ev(privmsg) as ChatEvent.Message
        val m = e.message
        assertEquals("abc-123", m.id)
        assertEquals(1_700_000_000_000L, m.tsMs)
        assertEquals("42", m.userId)
        assertEquals("someone", m.login)
        assertEquals("Some One", m.displayName)
        assertEquals(0xFF1E90FF.toInt(), m.colorArgb)
        assertEquals(listOf(Badge("broadcaster", "1"), Badge("subscriber", "6")), m.badges)
        assertEquals("Kappa Keepo Kappa", m.text)
        assertFalse(m.action)
        assertEquals(
            listOf(EmoteRange("25", 0, 4), EmoteRange("1902", 6, 10), EmoteRange("25", 12, 16)),
            m.emotes,
        )
        assertNull(m.reply)
    }

    @Test fun actionMessageIsUnwrapped() {
        val e = ev("@id=1;display-name=A :a!a@a PRIVMSG #c :\u0001ACTION waves hello\u0001") as ChatEvent.Message
        assertTrue(e.message.action)
        assertEquals("waves hello", e.message.text)
    }

    @Test fun actionWithoutClosingMarkerStillParses() {
        val e = ev("@id=1 :a!a@a PRIVMSG #c :\u0001ACTION waves") as ChatEvent.Message
        assertTrue(e.message.action)
        assertEquals("waves", e.message.text)
    }

    @Test fun missingColorAndDisplayNameFallBack() {
        val e = ev("@id=1;color=;display-name= :bob!bob@bob PRIVMSG #c :hi") as ChatEvent.Message
        assertNull(e.message.colorArgb)
        assertEquals("bob", e.message.displayName)
    }

    @Test fun missingIdGetsLocalIdAndTimestampFallsBackToNow() {
        val e = ev(":bob!bob@bob PRIVMSG #c :hi") as ChatEvent.Message
        assertTrue(e.message.id.startsWith("m-"))
        assertEquals(1_000L, e.message.tsMs)
    }

    @Test fun replyParentTags() {
        val e = ev(
            "@id=r1;display-name=A;reply-parent-msg-id=p1;reply-parent-user-login=other;" +
                "reply-parent-display-name=Other;reply-parent-msg-body=what\\sis\\sthis :a!a@a PRIVMSG #c :@Other it is",
        ) as ChatEvent.Message
        val r = e.message.reply!!
        assertEquals("p1", r.messageId)
        assertEquals("other", r.login)
        assertEquals("Other", r.displayName)
        assertEquals("what is this", r.body)
    }

    @Test fun firstMessageAndHighlightFlags() {
        val e = ev("@id=1;first-msg=1;msg-id=highlighted-message :a!a@a PRIVMSG #c :hi") as ChatEvent.Message
        assertTrue(e.message.firstMessage)
        assertTrue(e.message.highlighted)
    }

    @Test fun privmsgWithoutNickIsDropped() {
        assertNull(ev("@id=1 PRIVMSG #c :hi"))
    }

    // ---- badges / emotes / colors ----

    @Test fun badgesParsing() {
        assertEquals(emptyList<Badge>(), ChatEventParser.parseBadges(""))
        assertEquals(emptyList<Badge>(), ChatEventParser.parseBadges(null))
        assertEquals(listOf(Badge("bits", "100")), ChatEventParser.parseBadges("bits/100"))
        assertEquals(listOf(Badge("a", "1")), ChatEventParser.parseBadges("junk,a/1,/2"))
    }

    @Test fun emoteRangesAreSortedAndMalformedSkipped() {
        val r = ChatEventParser.parseEmotes("b:10-12/a:0-3,5-x,7-4/:1-2/c")
        assertEquals(listOf(EmoteRange("a", 0, 3), EmoteRange("b", 10, 12)), r)
        assertEquals(emptyList<EmoteRange>(), ChatEventParser.parseEmotes(""))
    }

    @Test fun colorParsing() {
        assertEquals(0xFF00FF00.toInt(), ChatEventParser.parseColor("#00FF00"))
        assertNull(ChatEventParser.parseColor(""))
        assertNull(ChatEventParser.parseColor("00FF00"))
        assertNull(ChatEventParser.parseColor("#GG0000"))
        assertNull(ChatEventParser.parseColor("#00FF0"))
    }

    // ---- USERNOTICE ----

    @Test fun subBecomesSystemLine() {
        val e = ev("@id=n1;msg-id=sub;system-msg=Foo\\ssubscribed\\swith\\sPrime.;tmi-sent-ts=5 :tmi.twitch.tv USERNOTICE #c") as ChatEvent.System
        assertEquals("Foo subscribed with Prime.", e.text)
        assertEquals(ChatEvent.System.Kind.Sub, e.kind)
        assertEquals("n1", e.id)
        assertEquals(5L, e.tsMs)
    }

    @Test fun resubWithMessageIsAMessageWithHeader() {
        val e = ev(
            "@id=n2;msg-id=resub;login=foo;display-name=Foo;color=#FF0000;user-id=7;badges=subscriber/12;" +
                "system-msg=Foo\\ssubscribed\\sfor\\s12\\smonths.;emotes=25:0-4 :tmi.twitch.tv USERNOTICE #c :Kappa thanks",
        ) as ChatEvent.Message
        assertEquals("Foo subscribed for 12 months.", e.message.systemHeader)
        assertEquals("Kappa thanks", e.message.text)
        assertEquals("foo", e.message.login)
        assertEquals(listOf(EmoteRange("25", 0, 4)), e.message.emotes)
    }

    @Test fun resubWithoutMessageIsSystemLine() {
        val e = ev("@id=n3;msg-id=resub;system-msg=Foo\\sresubbed :tmi.twitch.tv USERNOTICE #c") as ChatEvent.System
        assertEquals("Foo resubbed", e.text)
    }

    @Test fun subgiftAndMysteryGift() {
        val a = ev("@id=g1;msg-id=subgift;system-msg=A\\sgifted\\sa\\ssub\\sto\\sB. :tmi USERNOTICE #c") as ChatEvent.System
        assertEquals("A gifted a sub to B.", a.text)
        val b = ev("@id=g2;msg-id=submysterygift;system-msg=A\\sis\\sgifting\\s5\\ssubs. :tmi USERNOTICE #c") as ChatEvent.System
        assertEquals(ChatEvent.System.Kind.Sub, b.kind)
    }

    @Test fun raidSystemLine() {
        val e = ev("@id=r;msg-id=raid;msg-param-displayName=Raider;msg-param-login=raider;msg-param-viewerCount=1234 :tmi USERNOTICE #c") as ChatEvent.System
        assertEquals("Raider is raiding with 1234 viewers", e.text)
        assertEquals(ChatEvent.System.Kind.Raid, e.kind)
    }

    @Test fun announcementIsAHighlightedMessage() {
        val e = ev("@id=a1;msg-id=announcement;login=mod;display-name=Mod;color=#00FF7F :tmi USERNOTICE #c :Stream starts soon") as ChatEvent.Message
        assertTrue(e.message.announcement)
        assertEquals("Announcement", e.message.systemHeader)
        assertEquals("Stream starts soon", e.message.text)
    }

    @Test fun unknownUsernoticeWithoutSystemMsgIsDropped() {
        assertNull(ev("@id=x;msg-id=mystery :tmi USERNOTICE #c"))
        val e = ev("@id=x;msg-id=mystery;system-msg=Hello :tmi USERNOTICE #c") as ChatEvent.System
        assertEquals("Hello", e.text)
    }

    // ---- moderation ----

    @Test fun clearChatTimeout() {
        val e = ev("@ban-duration=600;room-id=1;target-user-id=9;tmi-sent-ts=77 :tmi.twitch.tv CLEARCHAT #c :troll") as ChatEvent.ClearChat
        assertEquals("troll", e.login)
        assertEquals("9", e.userId)
        assertEquals(600, e.durationSec)
        assertEquals(77L, e.tsMs)
    }

    @Test fun clearChatBanHasNoDuration() {
        val e = ev("@room-id=1;target-user-id=9 :tmi.twitch.tv CLEARCHAT #c :troll") as ChatEvent.ClearChat
        assertEquals("troll", e.login)
        assertNull(e.durationSec)
    }

    @Test fun clearChatWholeRoom() {
        val e = ev("@room-id=1 :tmi.twitch.tv CLEARCHAT #c") as ChatEvent.ClearChat
        assertNull(e.login)
    }

    @Test fun clearMsg() {
        val e = ev("@login=troll;target-msg-id=abc-123 :tmi.twitch.tv CLEARMSG #c :bad words") as ChatEvent.ClearMsg
        assertEquals("abc-123", e.targetMessageId)
        assertEquals("troll", e.login)
        assertNull(ev("@login=troll :tmi.twitch.tv CLEARMSG #c :bad"))
    }

    // ---- state ----

    @Test fun roomStateFullAndPartial() {
        val full = (ev("@emote-only=0;followers-only=-1;r9k=0;slow=30;subs-only=1 :tmi ROOMSTATE #c") as ChatEvent.Room).state
        assertEquals(false, full.emoteOnly)
        assertEquals(-1, full.followersOnlyMinutes)
        assertEquals(30, full.slowSeconds)
        assertEquals(true, full.subsOnly)
        val part = (ev("@slow=0 :tmi ROOMSTATE #c") as ChatEvent.Room).state
        assertEquals(0, part.slowSeconds)
        assertNull(part.emoteOnly)
        assertNull(part.subsOnly)
    }

    @Test fun userState() {
        val u = (ev("@badges=moderator/1;color=#8A2BE2;display-name=Me;emote-sets=0,33 :tmi USERSTATE #c") as ChatEvent.User).state
        assertEquals("Me", u.displayName)
        assertEquals(0xFF8A2BE2.toInt(), u.colorArgb)
        assertEquals(listOf(Badge("moderator", "1")), u.badges)
    }

    // ---- session commands ----

    @Test fun pingReconnectWelcome() {
        assertEquals("tmi.twitch.tv", (ev("PING :tmi.twitch.tv") as ChatEvent.Ping).payload)
        assertEquals(ChatEvent.Reconnect, ev(":tmi.twitch.tv RECONNECT"))
        assertEquals(ChatEvent.Welcome, ev(":tmi.twitch.tv 001 me :Welcome, GLHF!"))
        assertNull(ev(":tmi.twitch.tv 372 me :You are in a maze of twisty passages."))
        assertNull(ev(":tmi.twitch.tv CAP * ACK :twitch.tv/tags"))
    }

    @Test fun noticeAuthFailureVersusInfo() {
        assertNotNull(ev(":tmi.twitch.tv NOTICE * :Login authentication failed") as ChatEvent.AuthFailed)
        val n = ev("@msg-id=slow_on :tmi.twitch.tv NOTICE #c :This room is now in slow mode.") as ChatEvent.System
        assertEquals(ChatEvent.System.Kind.Notice, n.kind)
        assertEquals("This room is now in slow mode.", n.text)
    }
}
