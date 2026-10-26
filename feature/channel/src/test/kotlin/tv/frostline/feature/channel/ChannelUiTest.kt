package tv.frostline.feature.channel

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.frostline.core.twitch.ClipPeriod
import tv.frostline.core.twitch.HelixClip
import tv.frostline.core.twitch.HelixStream
import tv.frostline.core.twitch.HelixUser
import tv.frostline.core.twitch.HelixVideo
import tv.frostline.core.twitch.VideoKind
import tv.frostline.core.ui.FocusMemory
import tv.frostline.core.ui.FrostlineTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class ChannelUiTest {
    @get:Rule val rule = createComposeRule()

    private var watched = 0
    private val vods = mutableListOf<String>()
    private val clips = mutableListOf<String>()
    private var loadMores = 0
    private var tabs = mutableListOf<ChannelTab>()
    private val memory = FocusMemory()

    private val user = HelixUser("10", "chan", "Chan", "https://img/p.png", description = "Hello there", offlineImageUrl = "https://img/off-{width}x{height}.png", broadcasterType = "partner", createdAt = "2016-03-04T00:00:00Z")
    private val live = HelixStream("1", "10", "chan", "Chan", gameName = "Chess", title = "Playing", viewerCount = 1500)

    private fun video(i: Int, thumb: String = "https://img/v$i-%{width}x%{height}.jpg") =
        HelixVideo("v$i", title = "Video $i", thumbnailUrl = thumb, duration = "1h0m0s", createdAt = "2026-10-20T00:00:00Z", viewCount = 10, type = "archive")

    private fun show(state: ChannelState) {
        val actions = ChannelActions(
            onWatchLive = { watched++ },
            onOpenVod = { vods += it },
            onOpenClip = { clips += it },
            onTab = { tabs += it },
            onKind = {},
            onPeriod = {},
            onLoadMore = { loadMores++ },
            onRetry = {},
        )
        rule.setContent { FrostlineTheme { ChannelContent(state, actions, memory) } }
        rule.waitForIdle()
    }

    private fun base(items: List<HelixVideo> = emptyList(), live: HelixStream? = null, tab: ChannelTab = ChannelTab.Videos) = ChannelState(
        loading = false, login = "chan", user = user, live = live, tab = tab,
        videos = mapOf(VideoKind.Broadcasts to PagedList(items, loaded = true)),
        clips = mapOf(ClipPeriod.Week to PagedList(listOf(HelixClip("slug1", title = "Clip one", thumbnailUrl = "https://img/c.jpg", duration = 29.0)), loaded = true)),
    )

    private fun press(key: Key) {
        rule.onNodeWithTag("channel_grid").performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test fun offlineChannelHasNoWatchLiveButtonAndFocusStartsOnTheTab() {
        show(base(listOf(video(1))))
        rule.onNodeWithTag(ChannelTags.WatchLive).assertDoesNotExist()
        rule.onNodeWithTag(ChannelTags.Status).assertExists()
        rule.onNodeWithTag(ChannelTags.TabPrefix + "Videos").assertIsFocused()
    }

    @Test fun liveChannelHasWatchLiveFocusedFirst() {
        show(base(listOf(video(1)), live = live))
        rule.onNodeWithTag(ChannelTags.WatchLive).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(1, watched)
    }

    @Test fun tabsAndFiltersAreReachableWithTheDpad() {
        show(base(listOf(video(1), video(2))))
        press(Key.DirectionRight); rule.onNodeWithTag(ChannelTags.TabPrefix + "Clips").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf(ChannelTab.Clips), tabs)
        press(Key.DirectionRight); rule.onNodeWithTag(ChannelTags.TabPrefix + "About").assertIsFocused()
        press(Key.DirectionLeft); press(Key.DirectionLeft)
        rule.onNodeWithTag(ChannelTags.TabPrefix + "Videos").assertIsFocused()
        press(Key.DirectionDown); rule.onNodeWithTag(ChannelTags.KindPrefix + "Broadcasts").assertIsFocused()
        press(Key.DirectionRight); rule.onNodeWithTag(ChannelTags.KindPrefix + "Highlights").assertIsFocused()
        press(Key.DirectionRight); rule.onNodeWithTag(ChannelTags.KindPrefix + "Uploads").assertIsFocused()
        press(Key.DirectionLeft); press(Key.DirectionLeft)
        press(Key.DirectionDown); rule.onNodeWithTag(ChannelTags.VideoPrefix + "v1").assertIsFocused()
        press(Key.DirectionRight); rule.onNodeWithTag(ChannelTags.VideoPrefix + "v2").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("v2"), vods)
    }

    @Test fun theInProgressBroadcastCardIsMarkedLiveAndTriesTheLivePreview() {
        val onAir = live.copy(thumbnailUrl = "https://static-cdn.jtvnw.net/previews-ttv/live_user_chan-{width}x{height}.jpg", startedAt = "2026-10-26T10:00:00Z")
        val inProgress = video(9, thumb = "").copy(streamId = onAir.id, createdAt = onAir.startedAt)
        show(base(listOf(inProgress, video(1)), live = onAir).let { it.copy(user = it.user!!.copy(offlineImageUrl = "")) })
        rule.onNodeWithTag("live_badge_v9", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("live_badge_v1", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun anInProgressBroadcastWithNoPreviewAndNoBannerFallsBackToThePlaceholder() {
        val onAir = live.copy(thumbnailUrl = "", startedAt = "2026-10-26T10:00:00Z")
        val inProgress = video(9, thumb = "").copy(streamId = onAir.id)
        show(base(listOf(inProgress), live = onAir).let { it.copy(user = it.user!!.copy(offlineImageUrl = "")) })
        rule.onNodeWithTag(ChannelTags.Placeholder + "v9", useUnmergedTree = true).assertExists()
    }

    @Test fun missingThumbnailsGetAPlaceholderAndGoodOnesDoNot() {
        show(
            base(
                listOf(
                    video(1, thumb = ""),
                    video(2, thumb = "https://vod/_404/404_processing_%{width}x%{height}.png"),
                    video(3),
                ),
            ).let { it.copy(user = it.user!!.copy(offlineImageUrl = "")) },
        )
        rule.onNodeWithTag(ChannelTags.Placeholder + "v1", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(ChannelTags.Placeholder + "v2", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(ChannelTags.Placeholder + "v3", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun aLiveArchiveWithABlankThumbnailUsesTheOfflineImage() {
        show(base(listOf(video(1, thumb = ""))))
        rule.onNodeWithTag(ChannelTags.Placeholder + "v1", useUnmergedTree = true).assertDoesNotExist() // the offline image stands in
    }

    @Test fun reachingTheEndOfTheGridAsksForMore() {
        show(base((1..30).map { video(it) }))
        assertEquals("first screenful is not near the end", 0, loadMores)
        press(Key.DirectionDown); press(Key.DirectionDown)
        var guard = 0
        while (loadMores == 0 && guard++ < 20) press(Key.DirectionDown)
        assertTrue("paging was requested while walking down", loadMores > 0)
    }

    @Test fun clipsAreShownWithTheirOwnCards() {
        show(base(tab = ChannelTab.Clips))
        rule.onNodeWithTag(ChannelTags.PeriodPrefix + "Week").assertExists()
        rule.onNodeWithTag(ChannelTags.ClipPrefix + "slug1").requestFocus()
        rule.waitForIdle()
        press(Key.DirectionCenter)
        assertEquals(listOf("slug1"), clips)
    }

    @Test fun aboutShowsTheBioAndFacts() {
        show(base(tab = ChannelTab.About))
        rule.onNodeWithTag(ChannelTags.About).assertExists()
    }

    @Test fun emptyListsSaySo() {
        show(base(emptyList()))
        rule.onNodeWithTag(ChannelTags.Empty).assertExists()
    }

    @Test fun backFromAVideoRestoresFocusToThatCard() {
        memory.key = "v:v2"
        show(base(listOf(video(1), video(2), video(3))))
        rule.onNodeWithTag(ChannelTags.VideoPrefix + "v2").assertIsFocused()
    }

    @Test fun backRestoresACardFarDownTheListEvenWithoutASavedScroll() {
        memory.key = "v:v27" // scroll position lost (index 0): the card is brought into view first, then focused
        show(base((1..40).map { video(it) }))
        rule.onNodeWithTag(ChannelTags.VideoPrefix + "v27").assertIsFocused()
    }
}
