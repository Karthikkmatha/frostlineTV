package tv.frostline.core.twitchinternal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

private fun item(id: String, pos: Int, at: Long, len: Int = 3_600, title: String = "t$id") =
    HistoryItem(id, pos, at, title = title, lengthSec = len, channelLogin = "ch")

class HistoryMergeTest {
    @Test fun theNewestUpdatedAtWinsPerVideo() {
        val local = listOf(item("1", 600, at = 2_000), item("2", 900, at = 1_000))
        val remote = listOf(item("1", 100, at = 1_000), item("2", 1_800, at = 5_000), item("3", 300, at = 3_000))
        val m = HistoryMerge.merge(local, remote)
        assertEquals(listOf("2", "3", "1"), m.map { it.videoId })
        assertEquals(600, m.first { it.videoId == "1" }.positionSec)   // local was newer
        assertEquals(1_800, m.first { it.videoId == "2" }.positionSec) // Twitch was newer
    }

    @Test fun withoutTwitchTheLocalListStandsUntouched() {
        val local = listOf(item("1", 600, 1), item("2", 900, 2))
        assertTrue(HistoryMerge.merge(local, null) === local)
    }

    @Test fun aVideoFinishedElsewhereLeavesTheShelf() {
        val local = listOf(item("1", 600, at = 1_000))
        val remote = listOf(item("1", 3_500, at = 9_000)) // 97 % watched on the phone
        assertTrue(HistoryMerge.merge(local, remote).isEmpty())
    }

    @Test fun veryEarlyPositionsAreNotOffered() {
        assertTrue(HistoryMerge.merge(emptyList(), listOf(item("1", 20, 1))).isEmpty())
    }

    @Test fun theWinnerKeepsWhatItLacksFromTheLoser() {
        val local = listOf(item("1", 600, at = 9_000, title = ""))
        val remote = listOf(item("1", 100, at = 1_000, title = "From Twitch").copy(thumbnailUrl = "https://t/x.jpg"))
        val m = HistoryMerge.merge(local, remote).single()
        assertEquals(600, m.positionSec)
        assertEquals("From Twitch", m.title)
        assertEquals("https://t/x.jpg", m.thumbnailUrl)
    }

    @Test fun pickPrefersTheNewerAndIgnoresAnUndatedTwitchPositionWhenLocalExists() {
        val l = item("1", 600, at = 2_000)
        assertEquals(600, HistoryMerge.pick(l, item("1", 100, at = 1_000))!!.positionSec)
        assertEquals(100, HistoryMerge.pick(l, item("1", 100, at = 3_000))!!.positionSec)
        assertEquals(600, HistoryMerge.pick(l, item("1", 100, at = 0))!!.positionSec)   // position(videoId) has no time
        assertEquals(100, HistoryMerge.pick(null, item("1", 100, at = 0))!!.positionSec)
        assertEquals(600, HistoryMerge.pick(l, null)!!.positionSec)
        assertNull(HistoryMerge.pick(null, null))
    }
}

class HistoryParserTest {
    private val list = """
        {"data":{"currentUser":{"viewedVideos":{"edges":[
          {"history":{"position":1234,"updatedAt":"2026-11-03T10:00:00Z"},
           "node":{"id":"2501","title":"Long stream","lengthSeconds":7200,"previewThumbnailURL":"https://cdn/x-%{width}x%{height}.jpg","publishedAt":"2026-11-01T00:00:00Z",
                   "game":{"id":"1","name":"Chess","displayName":"Chess!","slug":"chess"},
                   "owner":{"id":"7","login":"someone","displayName":"Someone","profileImageURL":"https://p"},
                   "self":{"isRestricted":false,"viewingHistory":{"position":1234,"updatedAt":"2026-11-03T10:00:00Z"}}}},
          {"history":{"position":50},"node":{"id":"3","title":"Gone","lengthSeconds":10,"self":{"isRestricted":true}}},
          {"history":{"position":5},"node":{"title":"no id"}},
          {"history":null,"node":{"id":"4","lengthSeconds":100,"self":{"viewingHistory":{"position":40,"updatedAt":"2026-11-02T00:00:00Z"}}}},
          "garbage", null
        ]}}}}
    """.trimIndent()

    @Test fun readsTheContinueWatchingShelfDefensively() {
        val items = HistoryParser.list(J.parse(list)!!)
        assertEquals(listOf("2501", "4"), items.map { it.videoId })
        val a = items[0]
        assertEquals(1234, a.positionSec)
        assertEquals(java.time.Instant.parse("2026-11-03T10:00:00Z").toEpochMilli(), a.updatedAtMs)
        assertEquals("Long stream", a.title); assertEquals(7200, a.lengthSec)
        assertEquals("https://cdn/x-320x180.jpg", a.thumbnailUrl)
        assertEquals("someone", a.channelLogin); assertEquals("Someone", a.channelName); assertEquals("Chess!", a.game)
        assertEquals(40, items[1].positionSec) // position and time from self.viewingHistory
    }

    @Test fun thumbnailTemplatesAreFilledFor320x180() {
        assertEquals("https://cdn/x-320x180.jpg", HistoryParser.thumbnail("https://cdn/x-%{width}x%{height}.jpg", null, null))
        assertEquals("https://cdn/x-320x180.jpg", HistoryParser.thumbnail("https://cdn/x-{width}x{height}.jpg", "https://off", "https://av"))
    }

    @Test fun anEmptyOrProcessingThumbnailFallsBackToTheOfflineBannerThenTheAvatar() {
        val proc = "https://vod-secure.twitch.tv/_404/404_processing_%{width}x%{height}.png"
        assertEquals("https://off", HistoryParser.thumbnail(proc, "https://off", "https://av"))
        assertEquals("https://off", HistoryParser.thumbnail("", "https://off", "https://av"))
        assertEquals("https://av", HistoryParser.thumbnail("", "", "https://av"))
        assertEquals("https://av", HistoryParser.thumbnail(proc, null, "https://av"))
        assertEquals("", HistoryParser.thumbnail("", null, null))
        val json = """{"data":{"currentUser":{"viewedVideos":{"edges":[{"history":{"position":50,"updatedAt":"2026-11-03T10:00:00Z"},
            "node":{"id":"9","lengthSeconds":999999,"previewThumbnailURL":"","owner":{"login":"caedrel247","offlineImageURL":"https://off","profileImageURL":"https://av"}}}]}}}}"""
        assertEquals("https://off", HistoryParser.list(J.parse(json)!!)[0].thumbnailUrl)
    }

    @Test fun emptyOrWrongShapesGiveNothing() {
        for (t in listOf("""{}""", """{"data":null}""", """{"data":{"currentUser":null}}""", """{"data":{"currentUser":{"viewedVideos":{"edges":[]}}}}""")) {
            assertTrue(t, HistoryParser.list(J.parse(t)!!).isEmpty())
        }
    }

    @Test fun readsOneVideosPosition() {
        assertEquals(321, HistoryParser.position(J.parse("""{"data":{"video":{"id":"1","self":{"viewingHistory":{"position":321}}}}}""")!!))
        assertNull(HistoryParser.position(J.parse("""{"data":{"video":{"id":"1","self":{"viewingHistory":null}}}}""")!!))
        assertNull(HistoryParser.position(J.parse("""{"data":{"video":null}}""")!!))
    }
}

class HistoryReporterTest {
    private var now = 1_000_000L
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val history = InMemoryTwitchHistory()
    private fun reporter() = HistoryReporter(history, scope, { now })

    @Test fun periodicReportsAreThrottledToOncePerFortyFiveSeconds() {
        val r = reporter()
        r.tick("9", 60_000)
        now += 10_000; r.tick("9", 70_000)
        now += 20_000; r.tick("9", 90_000)
        assertEquals(listOf("9" to 60), history.reports)
        now += 20_000; r.tick("9", 110_000)
        assertEquals(listOf("9" to 60, "9" to 110), history.reports)
    }

    @Test fun pauseSeekAndLeavingSendAtOnceButNotTheSamePositionTwice() {
        val r = reporter()
        r.tick("9", 60_000)
        now += 1_000; r.flush("9", 61_000)
        r.flush("9", 61_400)  // same second just sent
        now += 1_000; r.flush("9", 500_000) // a seek commit
        assertEquals(listOf("9" to 60, "9" to 61, "9" to 500), history.reports)
    }

    @Test fun noHistoryOrNoSessionMeansNothingHappensAndNothingBreaks() {
        HistoryReporter(null, scope, { now }).apply { tick("9", 60_000); flush("9", 60_000) }
        history.available = false
        reporter().apply { tick("9", 60_000); flush("9", 70_000) }
        assertTrue(history.reports.isEmpty())
    }

    @Test fun aFailingReportNeverThrowsIntoPlayback() {
        val boom = object : TwitchHistory {
            override suspend fun continueWatching() = emptyList<HistoryItem>()
            override suspend fun position(videoId: String): Int? = null
            override suspend fun report(videoId: String, positionSec: Int): Boolean = throw RuntimeException("x")
        }
        HistoryReporter(boom, scope, { now }).flush("9", 60_000)
    }
}

class CachingHistoryTest {
    @Test fun oneRequestServesHomeAndTheResumePromptAndAReportDropsIt() = runBlocking {
        var t = 0L
        val inner = InMemoryTwitchHistory(listOf(item("1", 600, 5)))
        val c = CachingTwitchHistory(inner, { t }, 30_000)
        c.continueWatching(); c.continueWatching()
        assertEquals(600, c.position("1"))
        assertEquals(1, inner.listCalls)
        t = 31_000; c.continueWatching()
        assertEquals(2, inner.listCalls)
        c.report("1", 700)
        c.continueWatching()
        assertEquals(3, inner.listCalls)
    }
}

class GqlHistoryTest {
    private lateinit var gql: MockWebServer
    @Before fun setUp() { gql = MockWebServer().also { it.start() } }
    @After fun tearDown() { gql.shutdown() }

    private fun history(session: String? = "auth-token=SECRET;") =
        TwitchInternal.history(gql.url("/gql").toString(), IntegrityProvider.None) { session }

    @Test fun theShelfIsAskedWithThePersistedQueryAndTheWebSession() = runBlocking {
        gql.enqueue(MockResponse().setBody("""{"data":{"currentUser":{"viewedVideos":{"edges":[{"history":{"position":100,"updatedAt":"2026-11-03T10:00:00Z"},"node":{"id":"5","lengthSeconds":1000}}]}}}}"""))
        val items = history().continueWatching()
        assertEquals(listOf("5"), items.map { it.videoId })
        val r = gql.takeRequest()
        val body = r.body.readUtf8()
        assertEquals("OAuth SECRET", r.getHeader("Authorization"))
        assertTrue(body, body.contains("\"operationName\":\"FollowedStreamsContinueWatching\"") && body.contains("f072a74b5a454d1971b4650adfdfb5d42b69d31af7fac58f64b5a09494f7ad53") &&
            body.contains("\"limit\":20") && body.contains("\"includePreviewBlur\":false") && body.contains("persistedQuery") && !body.contains("\"query\""))
    }

    @Test fun persistedQueryNotFoundFallsBackToTheRawQuery() = runBlocking {
        gql.enqueue(MockResponse().setBody("""{"errors":[{"message":"PersistedQueryNotFound"}]}"""))
        gql.enqueue(MockResponse().setBody("""{"data":{"currentUser":{"viewedVideos":{"edges":[]}}}}"""))
        assertTrue(history().continueWatching().isEmpty())
        gql.takeRequest()
        assertTrue(gql.takeRequest().body.readUtf8().contains("viewedVideos"))
    }

    @Test fun onePositionIsAskedByVideoId() = runBlocking {
        gql.enqueue(MockResponse().setBody("""{"data":{"video":{"id":"7","self":{"viewingHistory":{"position":42}}}}}"""))
        assertEquals(42, history().position("7"))
        val body = gql.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("queryUserViewedVideo") && body.contains("86bf57f2a04e2f4705ce456d082bcffd80a372b6641a9c9120c409b10959a7a5") && body.contains("\"videoId\":\"7\""))
    }

    @Test fun reportSendsTheMutationWithTheViewerIdAsAVod() = runBlocking {
        gql.enqueue(MockResponse().setBody("""{"data":{"currentUser":{"id":"555"}}}"""))
        gql.enqueue(MockResponse().setBody("""{"data":{"updateUserViewedVideo":{"video":null}}}"""))
        assertTrue(history().report("2501", 321))
        gql.takeRequest()
        val body = gql.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("updateUserViewedVideo") && body.contains("bb58b1bd08a4ca0c61f2b8d323381a5f4cd39d763da8698f680ef1dfaea89ca1") &&
            body.contains("\"userID\":\"555\"") && body.contains("\"position\":321") && body.contains("\"videoID\":\"2501\"") && body.contains("\"videoType\":\"VOD\""))
    }

    @Test fun reportRefusesAnythingButAVodIdAndAZeroPosition() = runBlocking {
        assertFalse(history().report("clip-slug", 30))
        assertFalse(history().report("2501", 0))
        assertEquals(0, gql.requestCount)
    }

    @Test fun withoutAWebSessionNothingIsSentAndTheCallerFallsBack() = runBlocking {
        for (s in listOf<String?>(null, "", "  ")) {
            try { history(s).continueWatching(); fail() } catch (e: HistoryUnavailableException) { }
            assertFalse(history(s).report("1", 50))
        }
        assertEquals(0, gql.requestCount)
    }

    @Test fun httpAndGqlErrorsAreUnavailableNotCrashes() = runBlocking {
        gql.enqueue(MockResponse().setResponseCode(500))
        try { history().continueWatching(); fail() } catch (e: HistoryUnavailableException) { }
        gql.enqueue(MockResponse().setBody("""{"errors":[{"message":"service error"}],"data":null}"""))
        try { history().position("1"); fail() } catch (e: HistoryUnavailableException) { }
    }
}
