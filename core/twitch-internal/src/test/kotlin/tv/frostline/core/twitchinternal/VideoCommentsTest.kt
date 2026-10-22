package tv.frostline.core.twitchinternal

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class VideoCommentsTest {
    private lateinit var gql: MockWebServer

    @Before fun setUp() { gql = MockWebServer().also { it.start() } }
    @After fun tearDown() = gql.shutdown()

    private val page = """{"data":{"video":{"comments":{"edges":[
      {"cursor":"c1","node":{"id":"a","contentOffsetSeconds":12,"commenter":{"id":"5","login":"bob","displayName":"Bob"},
        "message":{"userColor":"#FF0000","userBadges":[{"setID":"subscriber","version":"6"},null],
          "fragments":[{"text":"hi ","emote":null},{"text":"Kappa","emote":{"emoteID":"25"}}]}}},
      {"cursor":"c2","node":{"id":"b","contentOffsetSeconds":14,"commenter":null,"message":{"userColor":null,"userBadges":[],"fragments":[{"text":"anon"}]}}}
    ],"pageInfo":{"hasNextPage":true,"hasPreviousPage":false}}}}}"""

    @Test fun parsesCommentsBadgesEmotesAndTheNextCursor() {
        val p = CommentsParser.parse(page)
        assertEquals(2, p.comments.size)
        val a = p.comments[0]
        assertEquals(12, a.offsetSeconds)
        assertEquals("Bob", a.displayName)
        assertEquals("#FF0000", a.colorHex)
        assertEquals(listOf(VodBadge("subscriber", "6")), a.badges)
        assertEquals(listOf(VodFragment("hi ", null), VodFragment("Kappa", "25")), a.fragments)
        assertEquals("?", p.comments[1].displayName)
        assertNull(p.comments[1].colorHex)
        assertEquals("c2", p.nextCursor)
        assertTrue(p.hasNext)
    }

    @Test fun lastPageHasNoNext() {
        val p = CommentsParser.parse(page.replace("\"hasNextPage\":true", "\"hasNextPage\":false"))
        assertFalse(p.hasNext)
    }

    @Test fun persistedQueryErrorsMeanUnavailable() {
        try { CommentsParser.parse("""{"errors":[{"message":"PersistedQueryNotFound"}]}"""); fail() } catch (e: ReplayUnavailableException) { }
        try { CommentsParser.parse("""{"data":{"video":null}}"""); fail() } catch (e: ReplayUnavailableException) { }
    }

    @Test fun requestsUseThePersistedQueryByOffsetThenCursor() = runBlocking {
        gql.enqueue(MockResponse().setBody(page))
        gql.enqueue(MockResponse().setBody(page))
        val api = TwitchInternal.videoComments(gql.url("/gql").toString())
        api.byOffset("999", 90)
        api.byCursor("999", "c2")
        val first = gql.takeRequest().body.readUtf8()
        assertTrue(first, first.contains("\"operationName\":\"VideoCommentsByOffsetOrCursor\""))
        assertTrue(first, first.contains("\"videoID\":\"999\"") && first.contains("\"contentOffsetSeconds\":90"))
        assertTrue(first, first.contains("\"sha256Hash\":\"b70a3591ff0f4e0313d126c6a1502d79a1c02baebb288227c582044aa76adf6a\""))
        val second = gql.takeRequest().body.readUtf8()
        assertTrue(second, second.contains("\"cursor\":\"c2\"") && !second.contains("contentOffsetSeconds"))
    }
}
