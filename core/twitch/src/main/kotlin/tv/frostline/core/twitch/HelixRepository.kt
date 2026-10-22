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

    /** Profile image URL by user id, for up to 100 ids per request. */
    suspend fun profileImages(ids: Collection<String>): Map<String, String> {
        val out = HashMap<String, String>()
        ids.distinct().chunked(100).forEach { chunk ->
            api.usersByIds(chunk).data.forEach { out[it.id] = it.profileImageUrl }
        }
        return out
    }

    /** The live stream of [login], or null when the channel is offline. */
    suspend fun streamByLogin(login: String): HelixStream? = api.streams(first = 1, userLogin = login).data.firstOrNull()

    suspend fun userByLogin(login: String): HelixUser? = api.usersByLogin(listOf(login)).data.firstOrNull()

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

    suspend fun globalBadges(): List<BadgeSet> = api.globalBadges().data
    suspend fun channelBadges(broadcasterId: String): List<BadgeSet> = api.channelBadges(broadcasterId).data

    /** The user's usable Twitch emotes, at most [limit] (the list can run to thousands). */
    suspend fun userEmotes(userId: String, limit: Int = 1500): List<HelixEmote> {
        val out = ArrayList<HelixEmote>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = api.userEmotes(userId, cursor)
            out += page.data
            cursor = page.pagination?.cursor
            pages++
        } while (cursor != null && out.size < limit && pages < 30)
        return if (out.size > limit) out.subList(0, limit) else out
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
