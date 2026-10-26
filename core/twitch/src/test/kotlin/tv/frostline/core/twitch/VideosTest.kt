package tv.frostline.core.twitch

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.frostline.core.data.TokenSet
import java.time.Instant

class VideosTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var repo: HelixRepository

    @Before fun setUp() = runBlocking {
        server = MockWebServer().also { it.start() }
        val vault = Fixtures.vault(tmp.root)
        vault.saveTokens(TokenSet("tok", "r", System.currentTimeMillis() + 3_600_000L, userId = "7"))
        val cfg = Fixtures.config(server)
        repo = HelixRepository(createHelixApi(cfg, TokenManager(vault, TwitchOAuth(okhttp3.OkHttpClient(), cfg))))
    }

    @After fun tearDown() = server.shutdown()

    @Test fun missingThumbnailsAreRecognised() {
        assertTrue(Thumbnails.isMissing(null))
        assertTrue(Thumbnails.isMissing(""))
        assertTrue(Thumbnails.isMissing("https://vod-secure.twitch.tv/_404/404_processing_%{width}x%{height}.png"))
        assertTrue(Thumbnails.isMissing("https://x/cf_vods/_404/thumb.jpg"))
        assertTrue(Thumbnails.isMissing("https://static-cdn.jtvnw.net/jtv_user_pictures/abc-profile_image-300x300.png"))
        assertNull(Thumbnails.videoOrOffline("https://static-cdn.jtvnw.net/jtv_user_pictures/abc-profile_image-300x300.png", "", 320, 180))
        assertEquals("https://off/o.png", Thumbnails.videoOrOffline("https://static-cdn.jtvnw.net/jtv_user_pictures/abc-profile_image-300x300.png", "https://off/o.png", 320, 180))
        assertTrue(!Thumbnails.isMissing("https://x/cf_vods/abc/thumb/thumb0-%{width}x%{height}.jpg"))
    }

    @Test fun videoThumbnailsAreSizedAndFallBackToTheOfflineImage() {
        assertEquals("https://x/t-320x180.jpg", Thumbnails.video("https://x/t-%{width}x%{height}.jpg", 320, 180))
        assertEquals("https://x/t-320x180.jpg", Thumbnails.video("https://x/t-{width}x{height}.jpg", 320, 180))
        assertNull(Thumbnails.video("", 320, 180))
        assertEquals("https://off/o.png", Thumbnails.videoOrOffline("", "https://off/o.png", 320, 180))
        assertNull(Thumbnails.videoOrOffline("https://x/_404/a.jpg", "", 320, 180))
        assertNull(Thumbnails.videoOrOffline("https://x/404_processing.jpg", "https://x/_404/o.png", 320, 180))
    }

    private val previewUrl = "https://static-cdn.jtvnw.net/previews-ttv/live_user_caedrel-{width}x{height}.jpg"

    @Test fun livePreviewIsSizedAndCarriesTheFiveMinuteKey() {
        assertEquals(
            "https://static-cdn.jtvnw.net/previews-ttv/live_user_caedrel-440x248.jpg?t=5917234",
            Thumbnails.livePreview(previewUrl, 440, 248, 5917234),
        )
        // A new bucket is a new URL (so the image is fetched again); the same bucket is the same URL (cached).
        assertNotEquals(Thumbnails.livePreview(previewUrl, 440, 248, 1), Thumbnails.livePreview(previewUrl, 440, 248, 2))
        assertEquals(Thumbnails.livePreview(previewUrl, 440, 248, 7), Thumbnails.livePreview(previewUrl, 440, 248, 7))
        assertTrue(Thumbnails.livePreview("https://x/p.jpg?a=1", 1, 1, 3)!!.endsWith("?a=1&t=3"))
        assertNull(Thumbnails.livePreview("", 440, 248, 1))
        assertNull(Thumbnails.livePreview(null, 440, 248, 1))
    }

    @Test fun liveBroadcastFallsBackFromPreviewToOfflineBannerToPlaceholder() {
        assertEquals(
            listOf("https://static-cdn.jtvnw.net/previews-ttv/live_user_caedrel-440x248.jpg?t=9", "https://off/o-440x248.png"),
            Thumbnails.liveOrOffline(previewUrl, "https://off/o-{width}x{height}.png", 440, 248, 9),
        )
        assertEquals(listOf("https://off/o.png"), Thumbnails.liveOrOffline("", "https://off/o.png", 440, 248, 9))
        assertEquals(emptyList<String>(), Thumbnails.liveOrOffline(null, "", 440, 248, 9)) // the UI draws the placeholder
    }

    @Test fun theInProgressArchiveIsTheOneWhoseStreamIdMatchesTheLiveStream() {
        val live = HelixStream("S1", "1", "caedrel", "Caedrel", startedAt = "2026-10-26T10:00:00Z")
        val now = HelixVideo("v1", streamId = "S1", type = "archive", createdAt = "2026-10-26T10:00:05Z")
        val old = HelixVideo("v0", streamId = "S0", type = "archive", createdAt = "2026-10-25T10:00:00Z")
        assertTrue(Thumbnails.isInProgress(now, live))
        assertFalse(Thumbnails.isInProgress(old, live))
        assertFalse(Thumbnails.isInProgress(now, null))
        assertFalse(Thumbnails.isInProgress(HelixVideo("h1", streamId = "S1", type = "highlight"), live))
        // No stream id on the video: matched by the start time.
        assertTrue(Thumbnails.isInProgress(HelixVideo("v2", type = "archive", createdAt = "2026-10-26T10:00:00Z"), live))
    }

    @Test fun parsesHelixDurations() {
        assertEquals(3_723_000L, Durations.parseHelix("1h2m3s"))
        assertEquals(2_710_000L, Durations.parseHelix("45m10s"))
        assertEquals(12_000L, Durations.parseHelix("12s"))
        assertEquals(0L, Durations.parseHelix(""))
        assertEquals("1:02:03", Durations.clock(3_723_000))
        assertEquals("2:05", Durations.clock(125_000))
        assertEquals("75:00", Durations.minutesSeconds(4_500_000))
    }

    @Test fun countsAndDatesFormat() {
        assertEquals("950", Counts.compact(950))
        assertEquals("1.2K", Counts.compact(1_234))
        assertEquals("15K", Counts.compact(15_300))
        assertEquals("2.4M", Counts.compact(2_400_000))
        assertEquals("Oct 20, 2026", Counts.date("2026-10-20T23:59:59Z"))
        assertEquals("not a date", Counts.date("not a date"))
    }

    @Test fun periodStartsRelativeToNow() {
        val now = Instant.parse("2026-10-22T12:00:00Z")
        assertEquals("2026-10-15T12:00:00Z", ClipPeriod.Week.startedAt(now))
        assertEquals("2026-09-22T12:00:00Z", ClipPeriod.Month.startedAt(now))
        assertNull(ClipPeriod.All.startedAt(now))
    }

    @Test fun videosAreRequestedByTypeAndPaged() = runBlocking {
        server.enqueue(Fixtures.json("""{"data":[{"id":"v1","user_id":"10","title":"T","type":"archive","duration":"1h0m0s","view_count":4,"thumbnail_url":"","created_at":"2026-10-20T00:00:00Z","extra":1}],"pagination":{"cursor":"C1"}}"""))
        val p = repo.videos("10", VideoKind.Broadcasts)
        assertEquals("v1", p.items.single().id)
        assertEquals(3_600_000L, p.items.single().durationMs)
        assertEquals("C1", p.cursor)
        val path = server.takeRequest().path!!
        assertTrue(path, path.contains("user_id=10") && path.contains("type=archive") && path.contains("sort=time"))
        server.enqueue(Fixtures.json("""{"data":[],"pagination":{}}"""))
        assertNull(repo.videos("10", VideoKind.Highlights, "C1").cursor)
        val p2 = server.takeRequest().path!!
        assertTrue(p2, p2.contains("type=highlight") && p2.contains("after=C1"))
    }

    @Test fun clipsCarryThePeriod() = runBlocking {
        server.enqueue(Fixtures.json("""{"data":[{"id":"Slug-1","title":"c","duration":29.9,"view_count":3,"thumbnail_url":"https://x/c-preview-480x272.jpg","broadcaster_id":"10"}],"pagination":{}}"""))
        val c = repo.clips("10", ClipPeriod.Week, now = Instant.parse("2026-10-22T12:00:00Z")).items.single()
        assertEquals("Slug-1", c.id)
        assertEquals(29.9, c.duration, 0.001)
        val path = server.takeRequest().path!!
        assertTrue(path, path.contains("broadcaster_id=10") && path.contains("started_at=2026-10-15T12%3A00%3A00Z"))
        server.enqueue(Fixtures.json("""{"data":[],"pagination":{}}"""))
        repo.clips("10", ClipPeriod.All)
        assertTrue(!server.takeRequest().path!!.contains("started_at"))
    }

    @Test fun userParsingKeepsAboutFields() = runBlocking {
        server.enqueue(Fixtures.json("""{"data":[{"id":"10","login":"a","display_name":"A","profile_image_url":"p","description":"bio","offline_image_url":"o","broadcaster_type":"partner","created_at":"2015-01-02T00:00:00Z"}]}"""))
        val u = repo.userByLogin("a")!!
        assertEquals("bio", u.description)
        assertEquals("o", u.offlineImageUrl)
        assertEquals("partner", u.broadcasterType)
    }
}
