package tv.frostline.feature.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeTransport : IrcTransport {
    val sent = mutableListOf<String>()
    var closed = false
    override fun send(line: String): Boolean {
        sent += line
        return true
    }
    override fun close() {
        closed = true
    }
}

private class FakeFactory : IrcTransportFactory {
    val transports = mutableListOf<FakeTransport>()
    val listeners = mutableListOf<IrcTransportListener>()
    val urls = mutableListOf<String>()
    override fun open(url: String, listener: IrcTransportListener): IrcTransport {
        urls += url
        return FakeTransport().also {
            transports += it
            listeners += listener
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class IrcSessionTest {
    private val welcome = ":tmi.twitch.tv 001 me :Welcome, GLHF!\r\n"

    private fun TestScope.session(
        f: FakeFactory,
        creds: IrcCredentials? = null,
        silenceMs: Long = 360_000L,
    ) = IrcSession(
        scope = backgroundScope,
        factory = f,
        credentials = { creds },
        silenceTimeoutMs = silenceMs,
        jitter = { 0.0 },
        anonymousNick = { "justinfan12345" },
        clock = { testScheduler.currentTime },
    )

    /** Starts a session and completes the open + welcome handshake on transport [i]. */
    private fun TestScope.handshake(f: FakeFactory, s: IrcSession, i: Int = 0) {
        runCurrent()
        f.listeners[i].onOpen(f.transports[i])
        f.listeners[i].onText(welcome)
        runCurrent()
    }

    @Test fun anonymousHandshakeUsesJustinfanAndJoinsAfterWelcome() = runTest {
        val f = FakeFactory()
        val s = session(f)
        s.start("SomeChannel")
        runCurrent()
        assertEquals(listOf(TWITCH_IRC_URL), f.urls)
        assertEquals(ConnState.Connecting, s.state.value)
        f.listeners[0].onOpen(f.transports[0])
        assertEquals(
            listOf("CAP REQ :twitch.tv/tags twitch.tv/commands", "PASS SCHMOOPIIE", "NICK justinfan12345"),
            f.transports[0].sent,
        )
        f.listeners[0].onText(welcome)
        assertEquals(ConnState.Connected, s.state.value)
        assertEquals("JOIN #somechannel", f.transports[0].sent.last())
        assertFalse(s.canSend.value)
        assertFalse(s.send("hello")) // anonymous cannot talk
    }

    @Test fun authenticatedHandshakeSendsTokenAndCanSend() = runTest {
        val f = FakeFactory()
        val s = session(f, IrcCredentials("me", "abc123"))
        s.start("chan")
        handshake(f, s)
        val sent = f.transports[0].sent
        assertTrue(sent.contains("PASS oauth:abc123"))
        assertTrue(sent.contains("NICK me"))
        assertTrue(s.canSend.value)
        assertTrue(s.send("hello\nworld "))
        assertEquals("PRIVMSG #chan :hello world", sent.last())
        assertFalse(s.send("   "))
    }

    @Test fun credentialsNeverAppearInToString() {
        assertEquals("IrcCredentials(login=me)", IrcCredentials("me", "secret").toString())
    }

    @Test fun pingIsAnsweredWithPong() = runTest {
        val f = FakeFactory()
        val s = session(f)
        s.start("chan")
        handshake(f, s)
        f.listeners[0].onText("PING :tmi.twitch.tv\r\n")
        assertEquals("PONG :tmi.twitch.tv", f.transports[0].sent.last())
    }

    @Test fun eventsAreEmittedForChatLines() = runTest {
        val f = FakeFactory()
        val s = session(f)
        val got = mutableListOf<ChatEvent>()
        backgroundScope.launch { s.events.collect { got += it } }
        s.start("chan")
        handshake(f, s)
        f.listeners[0].onText("@id=1;display-name=A :a!a@a PRIVMSG #chan :hi\r\n@target-msg-id=1 :tmi CLEARMSG #chan :hi\r\n")
        runCurrent()
        assertTrue(got.any { it is ChatEvent.Message && it.message.text == "hi" })
        assertTrue(got.any { it is ChatEvent.ClearMsg })
    }

    @Test fun failureReconnectsWithBackoffAndRejoins() = runTest {
        val f = FakeFactory()
        val s = session(f)
        s.start("chan")
        handshake(f, s)
        f.listeners[0].onFailure(RuntimeException("boom"))
        assertEquals(ConnState.Reconnecting(1), s.state.value)
        assertTrue(f.transports[0].closed)
        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, f.transports.size) // not yet
        advanceTimeBy(2)
        runCurrent()
        assertEquals(2, f.transports.size)
        handshake(f, s, 1)
        assertEquals(ConnState.Connected, s.state.value)
        assertEquals("JOIN #chan", f.transports[1].sent.last())
    }

    @Test fun repeatedFailuresBackOffExponentiallyAndResetAfterWelcome() = runTest {
        val f = FakeFactory()
        val s = session(f)
        s.start("chan")
        runCurrent()
        f.listeners[0].onFailure(RuntimeException())
        advanceTimeBy(1_001); runCurrent()
        f.listeners[1].onFailure(RuntimeException())
        assertEquals(ConnState.Reconnecting(2), s.state.value)
        advanceTimeBy(1_999); runCurrent()
        assertEquals(2, f.transports.size)
        advanceTimeBy(2); runCurrent()
        assertEquals(3, f.transports.size)
        handshake(f, s, 2)
        f.listeners[2].onClosed()
        assertEquals(ConnState.Reconnecting(1), s.state.value) // reset by the welcome
    }

    @Test fun backoffSequenceIsCappedAtThirtySeconds() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (1..7).map { reconnectDelayMs(it, 0.0) })
        assertEquals(1_200L, reconnectDelayMs(1, 1.0))
        assertEquals(36_000L, reconnectDelayMs(20, 1.0))
    }

    @Test fun serverReconnectCommandReconnects() = runTest {
        val f = FakeFactory()
        val s = session(f)
        s.start("chan")
        handshake(f, s)
        f.listeners[0].onText(":tmi.twitch.tv RECONNECT\r\n")
        assertEquals(ConnState.Reconnecting(1), s.state.value)
        advanceTimeBy(1_001); runCurrent()
        assertEquals(2, f.transports.size)
    }

    @Test fun channelChangePartsAndJoinsOnTheLiveSocket() = runTest {
        val f = FakeFactory()
        val s = session(f)
        s.start("one")
        handshake(f, s)
        s.setChannel("Two")
        assertEquals(listOf("PART #one", "JOIN #two"), f.transports[0].sent.takeLast(2))
        s.setChannel("two") // same channel: no-op
        assertEquals("JOIN #two", f.transports[0].sent.last())
    }

    @Test fun channelChangeBeforeWelcomeJoinsTheNewChannel() = runTest {
        val f = FakeFactory()
        val s = session(f)
        s.start("one")
        runCurrent()
        f.listeners[0].onOpen(f.transports[0])
        s.setChannel("two")
        f.listeners[0].onText(welcome)
        assertEquals("JOIN #two", f.transports[0].sent.last())
        assertFalse(f.transports[0].sent.contains("JOIN #one"))
    }

    @Test fun sendAfterChannelChangeTargetsTheNewChannel() = runTest {
        val f = FakeFactory()
        val s = session(f, IrcCredentials("me", "t"))
        s.start("one")
        handshake(f, s)
        s.setChannel("two")
        s.send("yo")
        assertEquals("PRIVMSG #two :yo", f.transports[0].sent.last())
    }

    @Test fun failedAuthFallsBackToAnonymous() = runTest {
        val f = FakeFactory()
        val s = session(f, IrcCredentials("me", "bad"))
        val got = mutableListOf<ChatEvent>()
        backgroundScope.launch { s.events.collect { got += it } }
        s.start("chan")
        runCurrent()
        f.listeners[0].onOpen(f.transports[0])
        f.listeners[0].onText(":tmi.twitch.tv NOTICE * :Login authentication failed\r\n")
        runCurrent()
        assertTrue(got.any { it is ChatEvent.AuthFailed })
        advanceTimeBy(1_001); runCurrent()
        f.listeners[1].onOpen(f.transports[1])
        assertTrue(f.transports[1].sent.contains("PASS SCHMOOPIIE"))
        assertTrue(f.transports[1].sent.contains("NICK justinfan12345"))
    }

    @Test fun silenceTriggersReconnect() = runTest {
        val f = FakeFactory()
        val s = session(f, silenceMs = 1_000)
        s.start("chan")
        handshake(f, s)
        advanceTimeBy(1_600); runCurrent()
        assertEquals(ConnState.Reconnecting(1), s.state.value)
    }

    @Test fun trafficKeepsTheWatchdogQuiet() = runTest {
        val f = FakeFactory()
        val s = session(f, silenceMs = 1_000)
        s.start("chan")
        handshake(f, s)
        repeat(8) {
            advanceTimeBy(500)
            f.listeners[0].onText("PING :x\r\n")
            runCurrent()
        }
        assertEquals(ConnState.Connected, s.state.value)
    }

    @Test fun staleCallbacksFromAnOldSocketAreIgnored() = runTest {
        val f = FakeFactory()
        val s = session(f)
        s.start("chan")
        handshake(f, s)
        f.listeners[0].onFailure(RuntimeException())
        advanceTimeBy(1_001); runCurrent()
        handshake(f, s, 1)
        f.listeners[0].onText(":tmi 001 me :late\r\n")
        f.listeners[0].onFailure(RuntimeException())
        assertEquals(ConnState.Connected, s.state.value)
    }

    @Test fun stopClosesAndDoesNotReconnect() = runTest {
        val f = FakeFactory()
        val s = session(f)
        s.start("chan")
        handshake(f, s)
        s.stop()
        assertTrue(f.transports[0].closed)
        assertEquals(ConnState.Idle, s.state.value)
        f.listeners[0].onFailure(RuntimeException())
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, f.transports.size)
        // and it can be started again
        s.start("chan")
        runCurrent()
        assertEquals(2, f.transports.size)
    }
}
