package tv.frostline.core.twitchinternal

import kotlinx.coroutines.flow.first
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
        assertEquals(HermesProtocol.Message.Welcome, HermesProtocol.parse("""{"type":"welcome","welcome":{"keepaliveSec":30}}"""))
        assertEquals(HermesProtocol.Message.Keepalive, HermesProtocol.parse("""{"type":"keepalive"}"""))
        assertEquals(HermesProtocol.Message.Reconnect, HermesProtocol.parse("""{"type":"reconnect"}"""))
        val n = HermesProtocol.parse(
            """{"type":"notification","notification":{"id":"n","subscription":{"id":"s1"},"type":"pubsub","pubsub":"{\"a\":1}"}}""",
        ) as HermesProtocol.Message.Notification
        assertEquals("s1", n.subscriptionId)
        assertEquals("""{"a":1}""", n.payload)
        assertNull(HermesProtocol.parse("not json"))
    }

    @Test fun subscribeMessageNamesTheTopicAndCarriesNoToken() {
        val m = HermesProtocol.subscribe("raid.5", "nonce", "sub", "2026-01-01T00:00:00Z")
        assertTrue(m.contains("\"topic\":\"raid.5\""))
        assertTrue(m.contains("\"type\":\"pubsub\""))
        assertTrue(!m.contains("auth", ignoreCase = true))
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
