package tv.frostline.core.twitchinternal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Probes `PlaybackAccessToken` on gql.twitch.tv. This module owns its own OkHttp client on purpose:
 * the web client id and cookie never share interceptors or auth state with the Helix client.
 */
internal class GqlWebSessionValidator(
    private val gqlUrl: String = GQL_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) : WebSessionValidator {

    override suspend fun validate(authToken: String): ProbeResult {
        val token = authToken.trim().removePrefix("auth-token=").trim('"', '\'', ' ', ';')
        if (token.isEmpty()) return ProbeResult.Invalid
        val body = buildJsonObject {
            put("operationName", "PlaybackAccessToken")
            put("query", QUERY)
            put("variables", buildJsonObject { put("login", PROBE_CHANNEL) })
        }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(gqlUrl)
            .header("Client-Id", WEB_CLIENT_ID)
            .header("Authorization", "OAuth $token")
            .post(body)
            .build()
        return try {
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { r ->
                    when {
                        r.code == 401 -> ProbeResult.Invalid
                        r.code in 200..299 -> ProbeResult.Valid
                        else -> ProbeResult.Failed("HTTP ${r.code}")
                    }
                }
            }
        } catch (e: IOException) {
            ProbeResult.Failed(e.javaClass.simpleName)
        }
    }

    companion object {
        const val GQL_URL = "https://gql.twitch.tv/gql"
        // Public client id of Twitch's own web player (not an app secret).
        const val WEB_CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"
        const val PROBE_CHANNEL = "twitch"
        const val QUERY =
            "query PlaybackAccessToken(\$login: String!) { streamPlaybackAccessToken(channelName: \$login, " +
                "params: {platform: \"web\", playerBackend: \"mediaplayer\", playerType: \"site\"}) { value signature } }"
    }
}
