package tv.frostline.core.twitchinternal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
        data class Welcome(val keepaliveSec: Int = 30) : Message
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

    /** Ids a subscribeResponse may carry for the request it answers (the request nonce or the subscription id). */
    fun subscribeResponseIds(text: String): List<String> {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
        val r = root["subscribeResponse"] as? JsonObject
        return listOfNotNull(
            root["id"]?.jsonPrimitive?.contentOrNull,
            root["parentId"]?.jsonPrimitive?.contentOrNull,
            ((r?.get("subscription") as? JsonObject)?.get("id"))?.jsonPrimitive?.contentOrNull,
            r?.get("id")?.jsonPrimitive?.contentOrNull,
        )
    }

    /** `ok`, or the server's result/error string of a subscribeResponse (short, no tokens). */
    fun subscribeResult(text: String): String {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return "unreadable"
        val r = root["subscribeResponse"] as? JsonObject ?: return "no body"
        val result = r["result"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val err = r["error"]?.jsonPrimitive?.contentOrNull
        return (if (err != null) "$result $err" else result).take(80)
    }

    fun parse(text: String): Message? {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        return when (val type = root["type"]?.jsonPrimitive?.contentOrNull) {
            "welcome" -> Message.Welcome(
                ((root["welcome"] as? JsonObject)?.get("keepaliveSec")?.jsonPrimitive?.intOrNull ?: 30).coerceIn(5, 300),
            )
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

/** Keepalive watchdog: the server sends a keepalive every `keepaliveSec`; silence for much longer means a dead socket. */
internal object HermesWatchdog {
    fun staleAfterMs(keepaliveSec: Int): Long = keepaliveSec * 2_000L + 10_000L
    fun isStale(lastMessageAtMs: Long, nowMs: Long, keepaliveSec: Int): Boolean = nowMs - lastMessageAtMs > staleAfterMs(keepaliveSec)
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
 * Hermes websocket (the PubSub replacement). Raid topics need no token; the viewer's points topic takes the web
 * session as a bearer in its subscribe frame. Reconnects with a capped backoff until the collector goes away, and
 * drops a socket that has gone silent for longer than the server's keepalive allows.
 */
internal class HermesWebSocketClient(
    private val url: String = HERMES_URL,
    private val client: OkHttpClient = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build(),
    private val now: () -> String = { java.time.Instant.now().toString() },
    private val retryDelayMs: (Int) -> Long = { attempt -> minOf(30_000L, 1_000L shl minOf(attempt, 5)) },
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val watchdogEveryMs: Long = 5_000L,
) : HermesClient {

    private val _status = kotlinx.coroutines.flow.MutableStateFlow("idle")
    override val status: kotlinx.coroutines.flow.StateFlow<String> = _status

    private val _topicStatus = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())
    override val topicStatus: kotlinx.coroutines.flow.StateFlow<Map<String, String>> = _topicStatus

    private fun setTopic(topic: String, state: String) = _topicStatus.update { it + (topic to state) }

    override fun events(topics: List<String>): Flow<HermesEvent> = events(topics, null)

    override fun events(topics: List<String>, authToken: String?): Flow<HermesEvent> = channelFlow {
        var attempt = 0
        topics.forEach { setTopic(it, "connecting") }
        try {
            while (isActive) {
                val ended = CompletableDeferred<Boolean>()
                _status.value = "connecting"
                topics.forEach { setTopic(it, "connecting") }
                // Request nonce and subscription id both map back to the topic, whichever one the response echoes.
                val byId = java.util.concurrent.ConcurrentHashMap<String, String>()
                val waiting = java.util.concurrent.CopyOnWriteArrayList<String>()
                val lastMessage = java.util.concurrent.atomic.AtomicLong(clockMs())
                val keepalive = java.util.concurrent.atomic.AtomicInteger(30)
                val socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        lastMessage.set(clockMs())
                        when (val m = HermesProtocol.parse(text)) {
                            is HermesProtocol.Message.Welcome -> {
                                attempt = 0
                                keepalive.set(m.keepaliveSec)
                                _status.value = "welcome, subscribing ${topics.size}"
                                topics.forEach { topic ->
                                    val sub = UUID.randomUUID().toString()
                                    val nonce = UUID.randomUUID().toString()
                                    byId[sub] = topic; byId[nonce] = topic; waiting += topic
                                    setTopic(topic, "subscribing")
                                    webSocket.send(HermesProtocol.subscribe(topic, nonce, sub, now(), authToken))
                                }
                            }
                            HermesProtocol.Message.Reconnect -> {
                                webSocket.close(1000, null)
                                ended.complete(true)
                            }
                            is HermesProtocol.Message.Notification -> {
                                val topic = byId[m.subscriptionId] ?: return
                                setTopic(topic, "ok (events)")
                                trySend(HermesEvent(topic, m.payload))
                            }
                            is HermesProtocol.Message.Other -> if (m.type == "subscribeResponse") {
                                val res = HermesProtocol.subscribeResult(text)
                                val topic = HermesProtocol.subscribeResponseIds(text).firstNotNullOfOrNull { byId[it] }
                                    ?: waiting.firstOrNull()
                                topic?.let { waiting.remove(it) }
                                val state = if (res == "ok") "subscribed" else "rejected: $res"
                                if (topic != null) setTopic(topic, state)
                                _status.value = if (res == "ok") "subscribed" else "subscribe rejected: $res"
                            }
                            else -> Unit
                        }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { _status.value = "closed $code"; ended.complete(true) }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        _status.value = "failed ${t.javaClass.simpleName}" + (response?.let { " HTTP ${it.code}" } ?: "")
                        ended.complete(true)
                    }
                })
                val dog = launch {
                    while (isActive) {
                        delay(watchdogEveryMs)
                        if (HermesWatchdog.isStale(lastMessage.get(), clockMs(), keepalive.get())) {
                            _status.value = "silent, reconnecting"
                            ended.complete(true)
                            break
                        }
                    }
                }
                try {
                    ended.await()
                } finally {
                    dog.cancel()
                    socket.cancel()
                }
                topics.forEach { t -> if (_topicStatus.value[t]?.startsWith("rejected") != true) setTopic(t, "reconnecting") }
                delay(retryDelayMs(attempt++))
            }
        } finally {
            _topicStatus.update { m -> m - topics.toSet() }
        }
    }

    companion object {
        // Public client id of Twitch's own web player.
        const val HERMES_URL = "wss://hermes.twitch.tv/v1?clientId=" + GqlWebSessionValidator.WEB_CLIENT_ID
    }
}
