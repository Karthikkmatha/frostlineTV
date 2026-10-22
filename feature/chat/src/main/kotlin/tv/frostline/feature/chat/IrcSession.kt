package tv.frostline.feature.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.random.Random

const val TWITCH_IRC_URL = "wss://irc-ws.chat.twitch.tv:443"

/** Login + OAuth access token. The token never appears in [toString]. */
class IrcCredentials(val login: String, val token: String) {
    override fun toString(): String = "IrcCredentials(login=$login)"
}

sealed interface ConnState {
    data object Idle : ConnState
    data object Connecting : ConnState
    data object Connected : ConnState
    data class Reconnecting(val attempt: Int) : ConnState
}

/** Transport seam so the session logic is testable without sockets. */
interface IrcTransport {
    fun send(line: String): Boolean
    fun close()
}

interface IrcTransportListener {
    fun onOpen(transport: IrcTransport)
    fun onText(text: String)
    fun onClosed()
    fun onFailure(t: Throwable)
}

fun interface IrcTransportFactory {
    fun open(url: String, listener: IrcTransportListener): IrcTransport
}

/** The production transport: one OkHttp WebSocket, own client (no interceptors, no auth headers). */
class OkHttpIrcTransportFactory(
    private val client: OkHttpClient = defaultClient(),
) : IrcTransportFactory {
    override fun open(url: String, listener: IrcTransportListener): IrcTransport {
        fun wrap(ws: WebSocket) = object : IrcTransport {
            override fun send(line: String) = ws.send(line)
            override fun close() {
                ws.close(1000, "bye")
            }
        }
        val ws = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                // onOpen can run before newWebSocket returns, so the transport is handed to the listener.
                override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen(wrap(webSocket))
                override fun onMessage(webSocket: WebSocket, text: String) = listener.onText(text)
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                    listener.onClosed()
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed()
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = listener.onFailure(t)
            },
        )
        return wrap(ws)
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // the socket is long-lived; we run our own watchdog
            .pingInterval(0, TimeUnit.SECONDS)
            .build()
    }
}

/** 1 s, 2 s, 4 s ... capped at 30 s, plus up to 20% jitter. */
fun reconnectDelayMs(attempt: Int, jitter: Double = Random.nextDouble()): Long {
    val base = (1_000L shl (attempt - 1).coerceIn(0, 5)).coerceAtMost(30_000L)
    return base + (base * 0.2 * jitter).toLong()
}

/**
 * Twitch chat over IRC WebSocket for one channel at a time. Anonymous (justinfan) when [credentials]
 * returns null, authenticated otherwise. Reconnects with backoff, answers PING, joins on [setChannel],
 * and treats silence longer than [silenceTimeoutMs] as a dead link.
 */
class IrcSession(
    private val scope: CoroutineScope,
    private val factory: IrcTransportFactory,
    private val credentials: suspend () -> IrcCredentials?,
    private val url: String = TWITCH_IRC_URL,
    private val silenceTimeoutMs: Long = 6 * 60_000L,
    private val jitter: () -> Double = { Random.nextDouble() },
    private val anonymousNick: () -> String = { "justinfan${Random.nextInt(10_000, 99_999)}" },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _events = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<ChatEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    /** True while connected with a user token (so [send] can work). */
    private val _canSend = MutableStateFlow(false)
    val canSend: StateFlow<Boolean> = _canSend.asStateFlow()

    private val lock = Any()
    private var transport: IrcTransport? = null
    private var channel: String? = null
    private var joined: String? = null
    private var authed = false
    @Volatile private var forceAnon = false
    @Volatile private var attempt = 0
    @Volatile private var running = false
    @Volatile private var generation = 0
    private var job: Job? = null
    private var retry: Job? = null
    private var watchdog: Job? = null
    @Volatile private var lastRx = 0L
    private var lastSendAt = 0L

    /** Starts (or keeps) the connection. Idempotent. */
    fun start(login: String) {
        synchronized(lock) {
            channel = login.lowercase().removePrefix("#")
            if (running) return
            running = true
            attempt = 0
            forceAnon = false
        }
        connect()
    }

    /** Switches channel: PART the old, JOIN the new on the live socket (or at next connect). */
    fun setChannel(login: String) {
        val next = login.lowercase().removePrefix("#")
        val t: IrcTransport?
        val old: String?
        synchronized(lock) {
            if (channel == next) return
            channel = next
            t = transport
            old = joined
            if (_state.value == ConnState.Connected && t != null) joined = next
        }
        if (t != null && _state.value == ConnState.Connected) {
            old?.let { t.send("PART #$it") }
            t.send("JOIN #$next")
        }
    }

    /** Disconnects and stops reconnecting. */
    fun stop() {
        val t: IrcTransport?
        synchronized(lock) {
            running = false
            generation++
            t = transport
            transport = null
            joined = null
        }
        job?.cancel()
        retry?.cancel()
        watchdog?.cancel()
        t?.close()
        _canSend.value = false
        _state.value = ConnState.Idle
    }

    /** Sends a chat line to the current channel. False if not connected with a user token. */
    fun send(text: String): Boolean {
        val t: IrcTransport
        val ch: String
        synchronized(lock) {
            t = transport ?: return false
            ch = joined ?: return false
            if (!authed) return false
        }
        val clean = text.replace("\r", " ").replace("\n", " ").trim()
        if (clean.isEmpty()) return false
        return t.send("PRIVMSG #$ch :$clean")
    }

    private fun connect() {
        val gen: Int
        synchronized(lock) { gen = ++generation }
        job?.cancel()
        _state.value = if (attempt == 0) ConnState.Connecting else ConnState.Reconnecting(attempt)
        job = scope.launch {
            val creds = if (forceAnon) null else credentials()
            val nick = creds?.login ?: anonymousNick()
            val listener = object : IrcTransportListener {
                override fun onOpen(transport: IrcTransport) {
                    val t = transport
                    synchronized(lock) {
                        if (gen != generation) {
                            t.close()
                            return
                        }
                        this@IrcSession.transport = t
                    }
                    lastRx = clock()
                    t.send("CAP REQ :twitch.tv/tags twitch.tv/commands")
                    // PASS is sent but never logged or kept; anonymous uses the documented placeholder.
                    t.send("PASS " + (if (creds != null) "oauth:${creds.token.removePrefix("oauth:")}" else "SCHMOOPIIE"))
                    t.send("NICK $nick")
                }

                override fun onText(text: String) {
                    if (gen != generation) return
                    lastRx = clock()
                    for (line in IrcParser.parseFrame(text)) handle(gen, line, creds != null)
                }

                override fun onClosed() = lost(gen)
                override fun onFailure(t: Throwable) = lost(gen)
            }
            // The transport must be visible to onOpen, which may run before open() returns.
            val holder = factory.open(url, listener)
            synchronized(lock) {
                if (gen != generation) holder.close() else if (transport == null) transport = holder
            }
            startWatchdog(gen)
        }
    }

    private fun startWatchdog(gen: Int) {
        watchdog?.cancel()
        watchdog = scope.launch {
            lastRx = clock()
            while (true) {
                delay(silenceTimeoutMs / 4)
                if (gen != generation) return@launch
                if (clock() - lastRx > silenceTimeoutMs) {
                    lost(gen)
                    return@launch
                }
            }
        }
    }

    private fun handle(gen: Int, line: IrcMessage, withToken: Boolean) {
        val ev = ChatEventParser.parse(line) ?: return
        when (ev) {
            is ChatEvent.Ping -> transport?.send("PONG :${ev.payload}")
            is ChatEvent.Reconnect -> lost(gen)
            is ChatEvent.Welcome -> {
                attempt = 0
                val ch: String?
                val t: IrcTransport?
                synchronized(lock) {
                    authed = withToken
                    ch = channel
                    t = transport
                    joined = ch
                }
                _canSend.value = withToken
                _state.value = ConnState.Connected
                if (ch != null) t?.send("JOIN #$ch")
                _events.tryEmit(ev)
            }
            is ChatEvent.AuthFailed -> {
                // Bad/expired token: fall back to read-only so chat still works, and tell the UI.
                _events.tryEmit(ev)
                if (withToken) {
                    forceAnon = true
                    lost(gen)
                }
            }
            else -> _events.tryEmit(ev)
        }
    }

    private fun lost(gen: Int) {
        val t: IrcTransport?
        synchronized(lock) {
            if (gen != generation || !running) return
            t = transport
            transport = null
            joined = null
            authed = false
            generation++
        }
        t?.close()
        watchdog?.cancel()
        _canSend.value = false
        attempt++
        _state.value = ConnState.Reconnecting(attempt)
        val wait = reconnectDelayMs(attempt, jitter())
        retry?.cancel()
        retry = scope.launch {
            delay(wait)
            if (running) connect()
        }
    }
}
