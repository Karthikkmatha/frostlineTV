package tv.frostline.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.frostline.core.data.ChatSettings
import tv.frostline.core.data.SettingsStore
import tv.frostline.core.data.TokenVault
import tv.frostline.core.twitch.HelixRepository
import java.util.concurrent.atomic.AtomicBoolean

/** Emotes for the picker, per tab. */
data class PickerData(
    val twitch: List<Emote> = emptyList(),
    val sevenTv: List<Emote> = emptyList(),
    val bttv: List<Emote> = emptyList(),
    val ffz: List<Emote> = emptyList(),
    val twitchLoading: Boolean = false,
) {
    fun tab(p: EmoteProvider): List<Emote> = when (p) {
        EmoteProvider.Twitch -> twitch
        EmoteProvider.SevenTv -> sevenTv
        EmoteProvider.Bttv -> bttv
        EmoteProvider.Ffz -> ffz
    }
}

/**
 * Owns the chat connection and the backlog for the player screen. Lifecycle mirrors the player's:
 * [start] on ON_START, [stop] on ON_STOP / leaving. Hiding the panel does not disconnect.
 */
class ChatViewModel(
    initialLogin: String,
    sessionFactory: (kotlinx.coroutines.CoroutineScope) -> IrcSession,
    private val emotes: EmoteRepository,
    private val helix: HelixRepository,
    private val vault: TokenVault,
    private val settingsStore: SettingsStore,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val flushMs: Long = 200L,
) : ViewModel() {
    private val session: IrcSession = sessionFactory(viewModelScope)
    private val buffer = ChatBuffer(MAX_LINES)

    private val _lines = MutableStateFlow<List<ChatLine>>(emptyList())
    /** Newest first. */
    val lines: StateFlow<List<ChatLine>> = _lines.asStateFlow()

    val connection: StateFlow<ConnState> get() = session.state
    val canSend: StateFlow<Boolean> get() = session.canSend

    private val _room = MutableStateFlow("")
    /** Short room-mode summary ("Slow 30 s, Subs only") or empty. */
    val room: StateFlow<String> = _room.asStateFlow()

    private val _picker = MutableStateFlow(PickerData())
    val picker: StateFlow<PickerData> = _picker.asStateFlow()

    /** "Sign in to chat" style notice when not allowed to send (guest) vs. the signed-in state. */
    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    val settings: StateFlow<ChatSettings> = settingsStore.chat.stateIn(viewModelScope, SharingStarted.Eagerly, ChatSettings())

    private var login = initialLogin.lowercase()
    private var channelId: String? = null
    private var sets: EmoteSets = EmoteSets.Empty
    private var badgeUrls: Map<String, String> = emptyMap()
    @Volatile private var ctx = ResolveContext(emptyMap(), emptyMap())
    @Volatile private var twitchByCode: Map<String, Emote> = emptyMap()
    @Volatile private var userState: UserState? = null
    @Volatile private var ownId: String? = null
    private var ownLogin: String? = null

    @Volatile private var visible = true
    private var started = false
    private var wanted = false
    private var eventsJob: Job? = null
    private var settingsJob: Job? = null
    private var channelJob: Job? = null
    private var pickerJob: Job? = null
    private val flushScheduled = AtomicBoolean(false)
    private var localSeq = 0L

    /** Screen visible (ON_START). Connects if chat is on (or was shown). Idempotent. */
    fun start() {
        if (started) return
        started = true
        settingsJob = viewModelScope.launch(worker) {
            settingsStore.chat.collect {
                rebuildContext(it)
                // Hidden panel: keep buffering (so showing it again is instant) but stop publishing new lists.
                val wasVisible = visible
                visible = it.enabled
                if (visible && !wasVisible) publish()
            }
        }
        viewModelScope.launch {
            // Connect once settings are known (first emission), if chat is enabled.
            val s = settingsStore.chat.first()
            if (s.enabled || wanted) connect()
        }
    }

    /** Screen left or backgrounded: drop the socket. Idempotent. */
    fun stop() {
        if (!started) return
        started = false
        settingsJob?.cancel()
        eventsJob?.cancel()
        channelJob?.cancel()
        session.stop()
    }

    /** The user turned chat on: connect now if the screen is running. */
    fun show() {
        wanted = true
        if (started && eventsJob?.isActive != true) connect()
    }

    fun setEnabled(on: Boolean) {
        viewModelScope.launch { settingsStore.updateChat { it.copy(enabled = on) } }
        if (on) show()
    }

    fun update(transform: (ChatSettings) -> ChatSettings) {
        viewModelScope.launch { settingsStore.updateChat(transform) }
    }

    /** Raid or channel switch: PART/JOIN on the live socket and reload emotes. */
    fun setChannel(newLogin: String) {
        val l = newLogin.lowercase()
        if (l == login) return
        login = l
        channelId = null
        sets = EmoteSets.Empty
        badgeUrls = emptyMap()
        _room.value = ""
        buffer.clear()
        buffer.addInfo("Joined #$l")
        publish()
        rebuildContext(settings.value)
        if (started && eventsJob?.isActive == true) {
            session.setChannel(l)
            loadChannelAssets()
        }
    }

    /** Sends [text] as the signed-in user. Returns false when not allowed or not connected. */
    fun send(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || !session.send(t)) return false
        val us = userState
        val raw = RawMessage(
            id = "local-${localSeq++}",
            tsMs = System.currentTimeMillis(),
            userId = ownId.orEmpty(),
            login = ownLogin ?: login,
            displayName = us?.displayName?.ifEmpty { null } ?: ownLogin ?: "me",
            colorArgb = us?.colorArgb,
            badges = us?.badges.orEmpty(),
            text = t,
            action = false,
            emotes = emptyList(),
            reply = null,
        )
        buffer.addLocal(MessageBuilder.build(raw, ctx, mine = true, fallback = twitchByCode))
        publish()
        return true
    }

    /** Loads the signed-in user's Twitch emotes for the picker (once). */
    fun ensurePickerLoaded() {
        if (pickerJob != null || _picker.value.twitch.isNotEmpty()) return
        pickerJob = viewModelScope.launch(worker) {
            _picker.value = _picker.value.copy(twitchLoading = true)
            val id = ownId ?: vault.currentTokens()?.userId
            val list = if (id != null) runCatching { helix.userEmotes(id) }.getOrDefault(emptyList()) else emptyList()
            val mapped = list.map { Emote(it.id, it.name, TwitchEmotes.url(it.id), EmoteProvider.Twitch) }
            twitchByCode = mapped.associateBy { it.code }
            _picker.value = _picker.value.copy(twitch = mapped, twitchLoading = false)
            if (mapped.isEmpty()) pickerJob = null // allow a retry next time the picker opens
        }
    }

    private fun connect() {
        eventsJob?.cancel()
        eventsJob = viewModelScope.launch(worker) {
            val tokens = vault.currentTokens()
            ownId = tokens?.userId
            ownLogin = tokens?.login
            _signedIn.value = tokens != null
            session.start(login)
            loadChannelAssets()
            session.events.collect { ev -> onEvent(ev) }
        }
    }

    private fun loadChannelAssets() {
        channelJob?.cancel()
        val forLogin = login
        channelJob = viewModelScope.launch(worker) {
            val id = channelId ?: runCatching { helix.userByLogin(forLogin)?.id }.getOrNull().also { if (login == forLogin) channelId = it }
            launch {
                val s = emotes.forChannel(id)
                if (login == forLogin) {
                    sets = s
                    rebuildContext(settings.value)
                }
            }
            launch {
                val g = runCatching { helix.globalBadges() }.getOrDefault(emptyList())
                val c = if (id != null) runCatching { helix.channelBadges(id) }.getOrDefault(emptyList()) else emptyList()
                val m = HashMap<String, String>()
                for (set in g + c) for (v in set.versions) {
                    val u = v.url2x.ifEmpty { v.url1x }
                    if (u.isNotEmpty()) m["${set.setId}/${v.id}"] = u
                }
                if (login == forLogin) {
                    badgeUrls = m
                    rebuildContext(settings.value)
                }
            }
        }
    }

    private fun rebuildContext(s: ChatSettings) {
        ctx = ResolveContext(sets.index(s.show7tv, s.showBttv, s.showFfz), badgeUrls)
        _picker.value = _picker.value.copy(
            sevenTv = if (s.show7tv) sets.sevenTv else emptyList(),
            bttv = if (s.showBttv) sets.bttv else emptyList(),
            ffz = if (s.showFfz) sets.ffz else emptyList(),
        )
    }

    private fun onEvent(ev: ChatEvent) {
        when (ev) {
            is ChatEvent.Room -> {
                _roomState = ev.state.mergeInto(_roomState)
                _room.value = roomSummary(_roomState)
            }
            is ChatEvent.User -> userState = ev.state
            is ChatEvent.Welcome -> {
                buffer.addInfo("Connected to #$login" + if (session.canSend.value) "" else " (read only)")
                publish()
            }
            is ChatEvent.AuthFailed -> {
                _signedIn.value = false
                buffer.addInfo("Chat sign-in failed; reading as guest. Sign in again from Settings.")
                publish()
            }
            else -> if (buffer.apply(ev, ctx)) scheduleFlush()
        }
    }

    private var _roomState = RoomState()

    private fun roomSummary(m: RoomState): String {
        val bits = ArrayList<String>(4)
        if (m.slowSeconds != null && m.slowSeconds > 0) bits += "Slow ${m.slowSeconds} s"
        if (m.subsOnly == true) bits += "Subs only"
        if (m.emoteOnly == true) bits += "Emote only"
        if (m.followersOnlyMinutes != null && m.followersOnlyMinutes >= 0) bits += if (m.followersOnlyMinutes == 0) "Followers only" else "Followers ${m.followersOnlyMinutes} min"
        if (m.r9k == true) bits += "Unique only"
        return bits.joinToString(", ")
    }

    private fun scheduleFlush() {
        if (!flushScheduled.compareAndSet(false, true)) return
        viewModelScope.launch(worker) {
            delay(flushMs)
            flushScheduled.set(false)
            publish()
        }
    }

    private fun publish() {
        if (!visible) return
        _lines.value = buffer.snapshot()
    }

    override fun onCleared() {
        session.stop()
    }

    companion object {
        const val MAX_LINES = 200
    }
}

/** ROOMSTATE on join carries every field, later ones only the changed field. */
internal fun RoomState.mergeInto(old: RoomState) = RoomState(
    emoteOnly ?: old.emoteOnly,
    followersOnlyMinutes ?: old.followersOnlyMinutes,
    subsOnly ?: old.subsOnly,
    slowSeconds ?: old.slowSeconds,
    r9k ?: old.r9k,
)
