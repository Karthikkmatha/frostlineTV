package tv.frostline.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBufferTest {
    private val ctx = ResolveContext(emptyMap(), emptyMap())

    private fun raw(i: Int, login: String = "u$i") = RawMessage(
        "id$i", i.toLong(), "$i", login, login, null, emptyList(), "msg $i", false, emptyList(), null,
    )

    private fun ChatBuffer.msg(i: Int, login: String = "u$i") = apply(ChatEvent.Message(raw(i, login)), ctx)
    private fun ChatBuffer.keys() = snapshot().map { it.key }

    @Test fun snapshotIsNewestFirst() {
        val b = ChatBuffer()
        b.msg(1); b.msg(2); b.msg(3)
        assertEquals(listOf("id3", "id2", "id1"), b.keys())
    }

    @Test fun backlogIsCappedAtTwoHundredDroppingTheOldest() {
        val b = ChatBuffer(200)
        for (i in 1..500) b.msg(i)
        assertEquals(200, b.size)
        val k = b.keys()
        assertEquals("id500", k.first())
        assertEquals("id301", k.last())
    }

    @Test fun snapshotIsACopy() {
        val b = ChatBuffer()
        b.msg(1)
        val s = b.snapshot()
        b.msg(2)
        assertEquals(1, s.size)
    }

    @Test fun clearMsgRemovesOnlyThatMessage() {
        val b = ChatBuffer()
        b.msg(1); b.msg(2); b.msg(3)
        assertTrue(b.apply(ChatEvent.ClearMsg("id2", "u2"), ctx))
        assertEquals(listOf("id3", "id1"), b.keys())
        assertFalse(b.apply(ChatEvent.ClearMsg("nope", null), ctx))
    }

    @Test fun clearChatForAUserRemovesTheirMessagesAndNotes() {
        val b = ChatBuffer()
        b.msg(1, "troll"); b.msg(2, "ok"); b.msg(3, "Troll")
        assertTrue(b.apply(ChatEvent.ClearChat("troll", "9", 600, 0L), ctx))
        val s = b.snapshot()
        assertEquals(2, s.size)
        assertEquals("id2", s[1].key)
        assertTrue((s[0] as SystemLine).text.contains("timed out for 600 s"))
    }

    @Test fun banIsWordedAsBanned() {
        val b = ChatBuffer()
        b.msg(1, "troll")
        b.apply(ChatEvent.ClearChat("troll", "9", null, 0L), ctx)
        assertTrue((b.snapshot()[0] as SystemLine).text.endsWith("banned"))
    }

    @Test fun clearChatForUnknownUserChangesNothing() {
        val b = ChatBuffer()
        b.msg(1)
        assertFalse(b.apply(ChatEvent.ClearChat("nobody", null, 10, 0L), ctx))
        assertEquals(1, b.size)
    }

    @Test fun clearWholeChatLeavesOnlyTheNotice() {
        val b = ChatBuffer()
        b.msg(1); b.msg(2)
        b.apply(ChatEvent.ClearChat(null, null, null, 0L), ctx)
        assertEquals(1, b.size)
        assertTrue(b.snapshot()[0] is SystemLine)
    }

    @Test fun systemEventsAreLines() {
        val b = ChatBuffer()
        b.apply(ChatEvent.System("s1", 0L, "Raider is raiding with 5 viewers", ChatEvent.System.Kind.Raid), ctx)
        assertEquals("s1", b.snapshot()[0].key)
    }

    @Test fun keysStayUniqueAcrossInfoLines() {
        val b = ChatBuffer()
        b.addInfo("a"); b.addInfo("b")
        assertEquals(2, b.keys().toSet().size)
    }

    @Test fun nonLineEventsAreIgnored() {
        val b = ChatBuffer()
        assertFalse(b.apply(ChatEvent.Room(RoomState(slowSeconds = 3)), ctx))
        assertFalse(b.apply(ChatEvent.Welcome, ctx))
    }
}
