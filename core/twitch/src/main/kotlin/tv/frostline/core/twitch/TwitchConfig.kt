package tv.frostline.core.twitch

import kotlinx.serialization.json.Json

data class TwitchConfig(
    val clientId: String,
    /** Optional. Included on refresh when present; the refresh is retried without it on a 400. */
    val clientSecret: String? = null,
    val idBaseUrl: String = "https://id.twitch.tv/",
    val helixBaseUrl: String = "https://api.twitch.tv/helix/",
) {
    // Never leak the secret through toString.
    override fun toString(): String = "TwitchConfig(idBaseUrl=$idBaseUrl, helixBaseUrl=$helixBaseUrl)"
}

object TwitchScopes {
    val ALL: List<String> = listOf(
        "user:read:follows",
        "chat:read",
        "chat:edit",
        "user:read:emotes",
        "user:read:chat",
        "user:write:chat",
        "user:read:subscriptions",
        "channel:read:redemptions",
    )
}

internal val TwitchJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}
