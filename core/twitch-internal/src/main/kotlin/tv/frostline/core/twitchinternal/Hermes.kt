package tv.frostline.core.twitchinternal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Pure Hermes wire format helpers (no I/O), so the protocol is unit-testable. */
internal object HermesProtocol {
    sealed interface Message {
        data object Welcome : Message
        data object Reconnect : Message
        data object Keepalive : Message
        data class Notification(val subscriptionId: String, val payload: String) : Message
        data class Other(val type: String) : Message
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun subscribe(topic: String, nonce: String, subscriptionId: String, timestamp: String, authToken: String? = null): String =
        buildJsonObject {
            put("type", "subscribe")
            put("id", nonce)
            put("subscribe", buildJsonObject {
                put("id", subscriptionId)
                put("type", "pubsub")
                put("pubsub", buildJsonObject { put("topic", topic) })
                if (authToken != null) put("auth", buildJsonObject { put("type", "bearer"); put("key", authToken) })
            })
            put("timestamp", timestamp)
        }.toString()

    fun parse(text: String): Message? {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        return when (val type = root["type"]?.jsonPrimitive?.contentOrNull) {
            "welcome" -> Message.Welcome
            "reconnect" -> Message.Reconnect
            "keepalive" -> Message.Keepalive
            "notification" -> {
                val n = root["notification"] as? JsonObject ?: return null
                val sub = (n["subscription"] as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull ?: return null
                val payload = n["pubsub"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: return null
                Message.Notification(sub, payload)
            }
            null -> null
            else -> Message.Other(type)
        }
    }
}

/** A raid announcement for the channel being watched. */
sealed interface RaidEvent {
    val raidId: String
    data class Update(override val raidId: String, val targetLogin: String, val targetName: String, val viewers: Int) : RaidEvent
    data class Go(override val raidId: String, val targetLogin: String) : RaidEvent
    data class Cancel(override val raidId: String) : RaidEvent
}

object RaidEvents {
    fun topic(channelId: String) = "raid.$channelId"

    private val json = Json { ignoreUnknownKeys = true }

    /** Parses a `raid.<channelId>` pubsub payload; null for anything else. */
    fun parse(payload: String): RaidEvent? {
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        val type = root["type"]?.jsonPrimitive?.contentOrNull ?: return null
        val raid = root["raid"] as? JsonObject ?: return null
        val id = raid["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val login = raid["target_login"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return when (type) {
            "raid_update_v2", "raid_update" -> {
                if (login.isEmpty()) return null
                RaidEvent.Update(
                    id, login,
                    raid["target_display_name"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null } ?: login,
                    raid["viewer_count"]?.jsonPrimitive?.intOrNull ?: 0,
                )
            }
            "raid_go_v2", "raid_go" -> if (login.isEmpty()) null else RaidEvent.Go(id, login)
            "raid_cancel_v2", "raid_cancel" -> RaidEvent.Cancel(id)
            else -> null
        }
    }
}

/**
 * Hermes websocket (the PubSub replacement). Anonymous: raid topics need no token. Reconnects with a
 * capped backoff until the collector goes away.
 */
internal class HermesWebSocketClient(
    private val url: String = HERMES_URL,
    private val client: OkHttpClient = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build(),
    private val now: () -> String = { java.time.Instant.now().toString() },
    private val retryDelayMs: (Int) -> Long = { attempt -> minOf(30_000L, 1_000L shl minOf(attempt, 5)) },
) : HermesClient {

    override fun events(topics: List<String>): Flow<HermesEvent> = events(topics, null)

    override fun events(topics: List<String>, authToken: String?): Flow<HermesEvent> = channelFlow {
        var attempt = 0
        while (isActive) {
            val ended = CompletableDeferred<Boolean>()
            val byId = HashMap<String, String>()
            val socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    when (val m = HermesProtocol.parse(text)) {
                        HermesProtocol.Message.Welcome -> {
                            attempt = 0
                            topics.forEach { topic ->
                                val sub = UUID.randomUUID().toString()
                                byId[sub] = topic
                                webSocket.send(HermesProtocol.subscribe(topic, UUID.randomUUID().toString(), sub, now(), authToken))
                            }
                        }
                        HermesProtocol.Message.Reconnect -> {
                            webSocket.close(1000, null)
                            ended.complete(true)
                        }
                        is HermesProtocol.Message.Notification -> {
                            val topic = byId[m.subscriptionId] ?: return
                            trySend(HermesEvent(topic, m.payload))
                        }
                        else -> Unit
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { ended.complete(true) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { ended.complete(true) }
            })
            try {
                ended.await()
            } finally {
                socket.cancel()
            }
            delay(retryDelayMs(attempt++))
        }
    }

    companion object {
        // Public client id of Twitch's own web player.
        const val HERMES_URL = "wss://hermes.twitch.tv/v1?clientId=" + GqlWebSessionValidator.WEB_CLIENT_ID
    }
}
