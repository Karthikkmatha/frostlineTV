package tv.frostline.feature.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Fetches a URL body as text, or null on any failure. Seam for tests. */
fun interface TextFetcher {
    suspend fun fetch(url: String): String?
}

class OkHttpTextFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) : TextFetcher {
    override suspend fun fetch(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).header("User-Agent", "FrostlineTV").build()).execute().use { r ->
                if (r.isSuccessful) r.body.string() else null
            }
        }.getOrNull()
    }
}

/**
 * Third-party emotes, cached per channel id (small LRU, so switching between a few channels via raids or
 * Back does not refetch) with the globals fetched once per process. Failed fetches are not cached, so
 * the next join retries.
 */
class EmoteRepository(private val fetcher: TextFetcher, private val maxChannels: Int = 6) {
    private val channels = object : LinkedHashMap<String, EmoteSets>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EmoteSets>?) = size > maxChannels
    }
    private var globals: EmoteSets? = null

    @Synchronized private fun cached(id: String): EmoteSets? = channels[id]
    @Synchronized private fun putChannel(id: String, s: EmoteSets) { channels[id] = s }

    suspend fun forChannel(channelId: String?): EmoteSets = coroutineScope {
        channelId?.let { cached(it) }?.let { return@coroutineScope it }
        val g = async { globals() }
        val c = async { if (channelId != null) channelOnly(channelId) else EmoteSets.Empty }
        val (global, chan) = listOf(g, c).awaitAll().let { it[0] to it[1] }
        val merged = EmoteSets(chan.sevenTv + global.sevenTv, chan.bttv + global.bttv, chan.ffz + global.ffz)
        // Only cache a complete result (a channel lookup that came back empty may just have failed).
        if (channelId != null && (chan.sevenTv.isNotEmpty() || chan.bttv.isNotEmpty() || chan.ffz.isNotEmpty())) putChannel(channelId, merged)
        merged
    }

    private suspend fun globals(): EmoteSets {
        synchronized(this) { globals }?.let { return it }
        val s = coroutineScope {
            val a = async { fetcher.fetch("https://7tv.io/v3/emote-sets/global")?.let(EmoteParsers::sevenTv).orEmpty() }
            val b = async { fetcher.fetch("https://api.betterttv.net/3/cached/emotes/global")?.let(EmoteParsers::bttv).orEmpty() }
            val c = async { fetcher.fetch("https://api.frankerfacez.com/v1/set/global")?.let(EmoteParsers::ffz).orEmpty() }
            EmoteSets(a.await(), b.await(), c.await())
        }
        if (s.sevenTv.isNotEmpty() || s.bttv.isNotEmpty() || s.ffz.isNotEmpty()) synchronized(this) { globals = s }
        return s
    }

    private suspend fun channelOnly(id: String): EmoteSets = coroutineScope {
        val a = async { fetcher.fetch("https://7tv.io/v3/users/twitch/$id")?.let(EmoteParsers::sevenTv).orEmpty() }
        val b = async { fetcher.fetch("https://api.betterttv.net/3/cached/users/twitch/$id")?.let(EmoteParsers::bttv).orEmpty() }
        val c = async { fetcher.fetch("https://api.frankerfacez.com/v1/room/id/$id")?.let(EmoteParsers::ffz).orEmpty() }
        EmoteSets(a.await(), b.await(), c.await())
    }
}
