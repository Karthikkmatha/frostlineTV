package tv.frostline.core.twitchinternal

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesTest {
    private val update = """{"type":"raid_update_v2","raid":{"id":"r1","creator_id":"1","source_id":"1","target_id":"2","target_login":"foo","target_display_name":"Foo","viewer_count":321,"force_raid_now_seconds":90}}"""

    @Test fun parsesRaidLifecycle() {
        val u = RaidEvents.parse(update) as RaidEvent.Update
        assertEquals("foo", u.targetLogin)
        assertEquals("Foo", u.targetName)
        assertEquals(321, u.viewers)
        val go = RaidEvents.parse("""{"type":"raid_go_v2","raid":{"id":"r1","target_login":"foo"}}""")
        assertEquals(RaidEvent.Go("r1", "foo"), go)
        assertEquals(RaidEvent.Cancel("r1"), RaidEvents.parse("""{"type":"raid_cancel_v2","raid":{"id":"r1"}}"""))
    }

    @Test fun ignoresUnrelatedPayloads() {
        assertNull(RaidEvents.parse("""{"type":"something_else","raid":{"id":"r"}}"""))
        assertNull(RaidEvents.parse("nonsense"))
        assertNull(RaidEvents.parse("""{"type":"raid_update_v2","raid":{"id":"r"}}"""))
        assertEquals("raid.99", RaidEvents.topic("99"))
    }

    @Test fun parsesProtocolMessages() {
        assertEquals(HermesProtocol.Message.Welcome(30), HermesProtocol.parse("""{"type":"welcome","welcome":{"keepaliveSec":30}}"""))
        assertEquals(HermesProtocol.Message.Welcome(30), HermesProtocol.parse("""{"type":"welcome"}"""))
        assertEquals(HermesProtocol.Message.Keepalive, HermesProtocol.parse("""{"type":"keepalive"}"""))
        assertEquals(HermesProtocol.Message.Reconnect, HermesProtocol.parse("""{"type":"reconnect"}"""))
        val n = HermesProtocol.parse(
            """{"type":"notification","notification":{"id":"n","subscription":{"id":"s1"},"type":"pubsub","pubsub":"{\"a\":1}"}}""",
        ) as HermesProtocol.Message.Notification
        assertEquals("s1", n.subscriptionId)
        assertEquals("""{"a":1}""", n.payload)
        assertNull(HermesProtocol.parse("not json"))
    }

    @Test fun subscribeResponseIdsCoverNonceAndSubscription() {
        val ids = HermesProtocol.subscribeResponseIds("""{"type":"subscribeResponse","id":"n1","subscribeResponse":{"result":"ok","subscription":{"id":"s1"}}}""")
        assertTrue("n1" in ids && "s1" in ids)
        assertEquals("ok", HermesProtocol.subscribeResult("""{"type":"subscribeResponse","subscribeResponse":{"result":"ok"}}"""))
        assertEquals("error unauthorized", HermesProtocol.subscribeResult("""{"type":"subscribeResponse","subscribeResponse":{"result":"error","error":"unauthorized"}}"""))
    }

    @Test fun watchdogCallsSilenceStaleOnlyAfterTwoKeepalives() {
        assertTrue(!HermesWatchdog.isStale(0, 60_000, 30))
        assertTrue(HermesWatchdog.isStale(0, 80_000, 30))
    }

    @Test fun reportsEachTopicSeparately() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"type":"welcome","welcome":{"keepaliveSec":30}}""")
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val nonce = Regex("\"id\":\"([^\"]+)\",\"subscribe\"").find(text)?.groupValues?.get(1) ?: return
                    val bad = text.contains("community-points")
                    webSocket.send(
                        if (bad) """{"type":"subscribeResponse","id":"$nonce","subscribeResponse":{"result":"error","error":"unauthorized"}}"""
                        else """{"type":"subscribeResponse","id":"$nonce","subscribeResponse":{"result":"ok"}}""",
                    )
                }
            }
            server.enqueue(MockResponse().withWebSocketUpgrade(listener))
            val url = server.url("/v1").toString().replaceFirst("http", "ws")
            val client = HermesWebSocketClient(url = url, retryDelayMs = { 50L }, authTimeoutMs = 200L)
            val job = kotlinx.coroutines.GlobalScope.launch { client.events(listOf("community-points-user-v1.1", "raid.2"), "tok").collect { } }
            val m = withTimeout(10_000) { client.topicStatus.first { it["raid.2"] == "subscribed" && it["community-points-user-v1.1"]?.startsWith("rejected") == true } }
            assertEquals("rejected: error unauthorized", m["community-points-user-v1.1"])
            job.cancel()
        } finally {
            server.shutdown()
        }
    }

    @Test fun subscribeMessageNamesTheTopicAndCarriesNoToken() {
        val m = HermesProtocol.subscribe("raid.5", "nonce", "sub", "2026-01-01T00:00:00Z")
        assertTrue(m.contains("\"topic\":\"raid.5\""))
        assertTrue(m.contains("\"type\":\"pubsub\""))
        assertTrue(!m.contains("auth", ignoreCase = true))
    }

    @Test fun theAuthenticateFrameCarriesTheBareTokenAndSubscribesCarryNone() {
        for (raw in listOf("abc123", "OAuth abc123", "oauth abc123", "Bearer abc123", "  abc123 ")) {
            val m = HermesProtocol.authenticate("id1", raw, "2026-01-01T00:00:00Z")
            assertTrue(m, m.contains("\"type\":\"authenticate\"") && m.contains("\"authenticate\":{\"token\":\"abc123\"}") && m.contains("\"id\":\"id1\""))
        }
        assertEquals("abc123", BearerToken.bare("OAuth abc123"))
    }

    @Test fun parsesTheAuthenticateResponse() {
        assertEquals(
            HermesProtocol.Message.AuthResponse("id1", "ok"),
            HermesProtocol.parse("""{"type":"authenticateResponse","parentId":"id1","authenticateResponse":{"result":"ok"}}"""),
        )
        assertEquals(
            HermesProtocol.Message.AuthResponse("id1", "error unauthorized"),
            HermesProtocol.parse("""{"type":"authenticateResponse","parentId":"id1","authenticateResponse":{"result":"error","error":"unauthorized"}}"""),
        )
    }

    /** A server that wants the authenticate frame first: user topics are refused until it came, public ones never are. */
    private fun authServer(server: MockWebServer, authResult: String?, frames: MutableList<String>) {
        val listener = object : WebSocketListener() {
            var authed = false
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"welcome","welcome":{"keepaliveSec":30}}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                frames += text
                if (text.contains("\"type\":\"authenticate\"")) {
                    val id = Regex("\"id\":\"([^\"]+)\"").find(text)!!.groupValues[1]
                    if (authResult == null) return // never answers
                    authed = authResult == "ok"
                    webSocket.send(
                        if (authed) """{"type":"authenticateResponse","parentId":"$id","authenticateResponse":{"result":"ok"}}"""
                        else """{"type":"authenticateResponse","parentId":"$id","authenticateResponse":{"result":"error","error":"$authResult"}}""",
                    )
                    return
                }
                val nonce = Regex("\"id\":\"([^\"]+)\",\"subscribe\"").find(text)?.groupValues?.get(1) ?: return
                val user = text.contains("community-points")
                webSocket.send(
                    if (user && !authed) """{"type":"subscribeResponse","id":"$nonce","subscribeResponse":{"result":"error","error":"unauthorized"}}"""
                    else """{"type":"subscribeResponse","id":"$nonce","subscribeResponse":{"result":"ok"}}""",
                )
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
    }

    @Test fun authenticatesFirstThenSubscribesWithoutAnyAuthField() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            val frames = java.util.concurrent.CopyOnWriteArrayList<String>()
            authServer(server, "ok", frames)
            val client = TwitchInternal.hermes(server.url("/v1").toString().replaceFirst("http", "ws"))
            val job = kotlinx.coroutines.GlobalScope.launch { client.events(listOf("community-points-user-v1.1"), "OAuth tok").collect { } }
            withTimeout(10_000) { client.topicStatus.first { it["community-points-user-v1.1"] == "subscribed" } }
            assertEquals("ok", client.authStatus.value)
            assertTrue(frames.first().contains("\"type\":\"authenticate\"") && frames.first().contains("\"token\":\"tok\""))
            val subs = frames.filter { it.contains("\"type\":\"subscribe\"") }
            assertEquals(1, subs.size)
            assertTrue(subs.all { !it.contains("auth", ignoreCase = true) && !it.contains("tok") })
            job.cancel()
        } finally {
            server.shutdown()
        }
    }

    @Test fun anAuthErrorStillSubscribesAndIsShown() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            authServer(server, "unauthorized", java.util.concurrent.CopyOnWriteArrayList())
            val client = TwitchInternal.hermes(server.url("/v1").toString().replaceFirst("http", "ws"))
            val job = kotlinx.coroutines.GlobalScope.launch { client.events(listOf("community-points-user-v1.1", "raid.2"), "tok").collect { } }
            withTimeout(10_000) { client.topicStatus.first { it["raid.2"] == "subscribed" && it["community-points-user-v1.1"]?.startsWith("rejected") == true } }
            assertEquals("error unauthorized", client.authStatus.value)
            job.cancel()
        } finally {
            server.shutdown()
        }
    }

    @Test fun noAnswerToAuthenticateTimesOutAndSubscribesAnyway() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            authServer(server, null, java.util.concurrent.CopyOnWriteArrayList())
            val client = HermesWebSocketClient(url = server.url("/v1").toString().replaceFirst("http", "ws"), retryDelayMs = { 50L }, authTimeoutMs = 300L)
            val job = kotlinx.coroutines.GlobalScope.launch { client.events(listOf("raid.2"), "tok").collect { } }
            withTimeout(10_000) { client.topicStatus.first { it["raid.2"] == "subscribed" } }
            assertEquals("timeout", client.authStatus.value)
            job.cancel()
        } finally {
            server.shutdown()
        }
    }

    @Test fun noTokenMeansNoAuthenticateFrame() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            val frames = java.util.concurrent.CopyOnWriteArrayList<String>()
            authServer(server, "ok", frames)
            val client = TwitchInternal.hermes(server.url("/v1").toString().replaceFirst("http", "ws"))
            val job = kotlinx.coroutines.GlobalScope.launch { client.events(listOf("raid.2")).collect { } }
            withTimeout(10_000) { client.topicStatus.first { it["raid.2"] == "subscribed" } }
            assertTrue(frames.none { it.contains("\"type\":\"authenticate\"") })
            job.cancel()
        } finally {
            server.shutdown()
        }
    }

    @Test fun subscribesAfterWelcomeAndDeliversNotifications() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"type":"welcome","welcome":{"keepaliveSec":30}}""")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    // Echo the subscription id back inside a raid notification.
                    val id = Regex("\"subscribe\":\\{\"id\":\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: return
                    val payload = update.replace("\"", "\\\"")
                    webSocket.send("""{"type":"notification","notification":{"id":"n","subscription":{"id":"$id"},"type":"pubsub","pubsub":"$payload"}}""")
                }
            }
            server.enqueue(MockResponse().withWebSocketUpgrade(listener))
            val url = server.url("/v1").toString().replaceFirst("http", "ws")
            val ev = withTimeout(10_000) { TwitchInternal.hermes(url).events(listOf("raid.7")).first() }
            assertEquals("raid.7", ev.topic)
            assertTrue(RaidEvents.parse(ev.payload) is RaidEvent.Update)
        } finally {
            server.shutdown()
        }
    }
}
