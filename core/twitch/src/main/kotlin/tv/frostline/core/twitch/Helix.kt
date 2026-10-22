package tv.frostline.core.twitch

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

@Serializable
data class Pagination(val cursor: String? = null)

@Serializable
data class Page<T>(val data: List<T> = emptyList(), val pagination: Pagination? = null)

@Serializable
data class HelixUser(
    val id: String,
    val login: String,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("profile_image_url") val profileImageUrl: String = "",
)

@Serializable
data class HelixStream(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("user_login") val userLogin: String,
    @SerialName("user_name") val userName: String,
    @SerialName("game_id") val gameId: String = "",
    @SerialName("game_name") val gameName: String = "",
    val type: String = "",
    val title: String = "",
    @SerialName("viewer_count") val viewerCount: Int = 0,
    @SerialName("started_at") val startedAt: String = "",
    @SerialName("thumbnail_url") val thumbnailUrl: String = "",
)

@Serializable
data class FollowedChannel(
    @SerialName("broadcaster_id") val broadcasterId: String,
    @SerialName("broadcaster_login") val login: String,
    @SerialName("broadcaster_name") val name: String = "",
    @SerialName("followed_at") val followedAt: String = "",
) {
    val displayName: String get() = name.ifBlank { login }
}

@Serializable
data class HelixGame(
    val id: String,
    val name: String,
    @SerialName("box_art_url") val boxArtUrl: String = "",
)

@Serializable
data class SearchChannel(
    val id: String,
    @SerialName("broadcaster_login") val login: String,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("game_name") val gameName: String = "",
    @SerialName("is_live") val isLive: Boolean = false,
    val title: String = "",
    @SerialName("thumbnail_url") val thumbnailUrl: String = "",
)

interface HelixApi {
    @GET("users")
    suspend fun users(): Page<HelixUser>

    @GET("users")
    suspend fun usersByIds(@Query("id") ids: List<String>): Page<HelixUser>

    @GET("users")
    suspend fun usersByLogin(@Query("login") logins: List<String>): Page<HelixUser>

    @GET("streams/followed")
    suspend fun followedStreams(
        @Query("user_id") userId: String,
        @Query("first") first: Int = 100,
        @Query("after") after: String? = null,
    ): Page<HelixStream>

    @GET("channels/followed")
    suspend fun followedChannels(
        @Query("user_id") userId: String,
        @Query("first") first: Int = 100,
        @Query("after") after: String? = null,
    ): Page<FollowedChannel>

    @GET("streams")
    suspend fun streams(
        @Query("first") first: Int = 40,
        @Query("after") after: String? = null,
        @Query("game_id") gameId: String? = null,
        @Query("user_login") userLogin: String? = null,
    ): Page<HelixStream>

    @GET("games/top")
    suspend fun topGames(
        @Query("first") first: Int = 40,
        @Query("after") after: String? = null,
    ): Page<HelixGame>

    @GET("search/channels")
    suspend fun searchChannels(
        @Query("query") query: String,
        @Query("first") first: Int = 24,
    ): Page<SearchChannel>

    @GET("search/categories")
    suspend fun searchCategories(
        @Query("query") query: String,
        @Query("first") first: Int = 24,
    ): Page<HelixGame>
}

internal class HelixAuthInterceptor(
    private val clientId: String,
    private val tokens: TokenManager,
    private val guest: GuestTokenProvider? = null,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        // Signed in: the user token. Guest: an app access token (client credentials).
        val token = runBlocking { tokens.accessToken() ?: guest?.token() }
        val b = chain.request().newBuilder().header("Client-Id", clientId)
        if (token != null) b.header("Authorization", "Bearer $token")
        return chain.proceed(b.build())
    }
}

/** On a 401, refresh once and retry; a second 401 gives up. */
internal class HelixAuthenticator(
    private val tokens: TokenManager,
    private val guest: GuestTokenProvider? = null,
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.priorResponse != null) return null
        val failed = response.request.header("Authorization")?.removePrefix("Bearer ")
        val fresh = runBlocking {
            if (guest != null && failed != null && guest.owns(failed)) guest.refreshAfter401(failed)
            else tokens.refreshAfter401(failed)
        } ?: return null
        return response.request.newBuilder().header("Authorization", "Bearer $fresh").build()
    }
}

fun createHelixApi(
    config: TwitchConfig,
    tokens: TokenManager,
    base: OkHttpClient = OkHttpClient(),
    guest: GuestTokenProvider? = null,
): HelixApi {
    val client = base.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(HelixAuthInterceptor(config.clientId, tokens, guest))
        .authenticator(HelixAuthenticator(tokens, guest))
        .build()
    return Retrofit.Builder()
        .baseUrl(config.helixBaseUrl)
        .client(client)
        .addConverterFactory(TwitchJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(HelixApi::class.java)
}

/** Replaces Twitch's `{width}x{height}` placeholders. */
fun String.sized(width: Int, height: Int): String =
    replace("{width}", width.toString()).replace("{height}", height.toString())
