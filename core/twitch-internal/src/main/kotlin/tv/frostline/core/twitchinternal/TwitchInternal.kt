package tv.frostline.core.twitchinternal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

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

/** Twitch's internal realtime channel (the PubSub replacement). Stage 1 only defines the seam. */
interface HermesClient {
    fun events(topics: List<String>): Flow<HermesEvent>
}

data class HermesEvent(val topic: String, val payload: String)

/** Public entry point. Everything else in this module is internal; callers only see the interfaces. */
object TwitchInternal {
    fun webSessionValidator(): WebSessionValidator = GqlWebSessionValidator()

    /** Test hook: point the validator at another host. */
    fun webSessionValidator(gqlUrl: String): WebSessionValidator = GqlWebSessionValidator(gqlUrl)

    fun hermes(): HermesClient = object : HermesClient {
        override fun events(topics: List<String>): Flow<HermesEvent> = emptyFlow() // stage 2+
    }
}
