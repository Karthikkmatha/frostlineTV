package tv.frostline.core.twitchinternal

import kotlinx.coroutines.flow.Flow

/** Result of probing Twitch's web GQL API with a user-supplied `auth-token` cookie. */
sealed interface ProbeResult {
    data object Valid : ProbeResult
    /** Twitch answered 401: the cookie is wrong or expired. */
    data object Invalid : ProbeResult
    /** The probe could not run (network, 5xx, unreadable answer). The cookie's validity is unknown. */
    data class Failed(val reason: String) : ProbeResult
}

interface WebSessionValidator {
    /** Never logs [authToken]. */
    suspend fun validate(authToken: String): ProbeResult
}

/** Twitch's internal realtime channel (the PubSub replacement). */
interface HermesClient {
    /** Connection state for the debug overlay: connecting, welcome, subscribed, or the real failure (class / HTTP status). */
    val status: kotlinx.coroutines.flow.StateFlow<String> get() = kotlinx.coroutines.flow.MutableStateFlow("idle")

    /** Per-topic state (connecting, subscribing, subscribed, "rejected: reason", ok (events), reconnecting), keyed by topic. No tokens. */
    val topicStatus: kotlinx.coroutines.flow.StateFlow<Map<String, String>> get() = kotlinx.coroutines.flow.MutableStateFlow(emptyMap())

    fun events(topics: List<String>): Flow<HermesEvent>

    /** For topics that need the viewer's web session. [authToken] is only ever put in the subscribe frame. */
    fun events(topics: List<String>, authToken: String?): Flow<HermesEvent> = events(topics)
}

data class HermesEvent(val topic: String, val payload: String)

/** Public entry point. Everything else in this module is internal; callers only see the interfaces. */
object TwitchInternal {
    fun webSessionValidator(): WebSessionValidator = GqlWebSessionValidator()

    /** Test hook: point the validator at another host. */
    fun webSessionValidator(gqlUrl: String): WebSessionValidator = GqlWebSessionValidator(gqlUrl)

    fun hermes(): HermesClient = HermesWebSocketClient()

    /** Test hook: point Hermes at another websocket URL. */
    fun hermes(url: String): HermesClient = HermesWebSocketClient(url = url, retryDelayMs = { 50L })

    fun playback(): PlaybackResolver = TwitchPlaybackResolver()

    /** Channel points, rewards, predictions and polls (GQL). */
    fun engagement(): EngagementApi = GqlEngagementApi()

    /** Same, sending the integrity token and device id that [integrity] captured. */
    fun engagement(integrity: IntegrityProvider): EngagementApi = GqlEngagementApi(integrity = integrity)

    /** Test hook: another GQL URL plus an integrity source. */
    fun engagement(gqlUrl: String, integrity: IntegrityProvider): EngagementApi = GqlEngagementApi(gqlUrl = gqlUrl, integrity = integrity)

    /** Headless-WebView integrity capture; [session] returns the linked web session's auth-token. */
    fun integrity(context: android.content.Context, session: suspend () -> String?): IntegrityProvider =
        WebViewIntegrityProvider(context, session)

    /** Test hook: point the engagement client at another GQL URL. */
    fun engagement(gqlUrl: String): EngagementApi = GqlEngagementApi(gqlUrl = gqlUrl)

    /** Past broadcasts and clips. */
    fun vodPlayback(): VodPlaybackResolver = TwitchVodResolver()

    /** Test hook: point the VOD resolver at other hosts. */
    fun vodPlayback(gqlUrl: String, usherBase: String): VodPlaybackResolver =
        TwitchVodResolver(gqlUrl = gqlUrl, usherBase = usherBase, random = { 42 })

    /** Chat replay of a past broadcast (GQL VideoCommentsByOffsetOrCursor). */
    fun videoComments(): VideoCommentsApi = GqlVideoComments()

    /** Test hook: point replay at another GQL URL. */
    fun videoComments(gqlUrl: String): VideoCommentsApi = GqlVideoComments(gqlUrl = gqlUrl)

    /** Test hook: point the resolver at other hosts. */
    fun playback(gqlUrl: String, usherBase: String): PlaybackResolver =
        TwitchPlaybackResolver(gqlUrl = gqlUrl, usherBase = usherBase, random = { 42 })
}
