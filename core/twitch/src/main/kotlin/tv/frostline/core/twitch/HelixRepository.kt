package tv.frostline.core.twitch

/** A–Z, case-insensitive, by display name (falling back to login). */
fun List<FollowedChannel>.sortedAz(): List<FollowedChannel> =
    sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })

/** Followed channels that are not currently live, sorted A–Z. */
fun offlineChannels(all: List<FollowedChannel>, live: List<HelixStream>): List<FollowedChannel> {
    val liveIds = live.mapTo(HashSet()) { it.userId }
    return all.filter { it.broadcasterId !in liveIds }.sortedAz()
}

class HelixRepository(private val api: HelixApi) {
    suspend fun me(): HelixUser = api.users().data.first()

    suspend fun followedLive(userId: String): List<HelixStream> {
        val out = mutableListOf<HelixStream>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = api.followedStreams(userId, 100, cursor)
            out += page.data.filter { it.type == "live" || it.type.isEmpty() }
            cursor = page.pagination?.cursor
        } while (cursor != null && ++pages < MAX_PAGES)
        return out.sortedByDescending { it.viewerCount }
    }

    /** Every followed channel, following all pagination cursors. */
    suspend fun followedChannels(userId: String): List<FollowedChannel> {
        val out = mutableListOf<FollowedChannel>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = api.followedChannels(userId, 100, cursor)
            out += page.data
            cursor = page.pagination?.cursor
        } while (cursor != null && ++pages < MAX_PAGES)
        return out
    }

    suspend fun topStreams(): List<HelixStream> = api.streams(first = 48).data
    suspend fun topCategories(): List<HelixGame> = api.topGames(first = 48).data
    suspend fun categoryStreams(gameId: String): List<HelixStream> = api.streams(first = 48, gameId = gameId).data
    suspend fun searchChannels(query: String): List<SearchChannel> = api.searchChannels(query).data
    suspend fun searchCategories(query: String): List<HelixGame> = api.searchCategories(query).data

    private companion object {
        const val MAX_PAGES = 100
    }
}
