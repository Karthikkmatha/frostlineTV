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
    fun events(topics: List<String>): Flow<HermesEvent>
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

    /** Test hook: point the resolver at other hosts. */
    fun playback(gqlUrl: String, usherBase: String): PlaybackResolver =
        TwitchPlaybackResolver(gqlUrl = gqlUrl, usherBase = usherBase, random = { 42 })
}
