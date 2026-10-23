package tv.frostline.feature.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.onRoot
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.frostline.core.data.ResumeDao
import tv.frostline.core.data.ResumeEntity
import tv.frostline.core.twitch.HelixApi
import tv.frostline.core.twitch.HelixGame
import tv.frostline.core.twitch.HelixRepository
import tv.frostline.core.twitch.HelixStream
import tv.frostline.core.twitch.HelixUser
import tv.frostline.core.twitch.Page
import tv.frostline.core.twitch.FollowedChannel
import tv.frostline.core.twitch.SearchChannel
import tv.frostline.core.ui.FrostlineTheme

private class FakeApi(val live: List<HelixStream> = emptyList()) : HelixApi {
    override suspend fun users() = Page<HelixUser>()
    override suspend fun usersByIds(ids: List<String>) = Page(ids.map { HelixUser(it, "u$it", "U$it", "https://img/$it.png") })
    override suspend fun usersByLogin(logins: List<String>) = Page<HelixUser>()
    override suspend fun followedStreams(userId: String, first: Int, after: String?) = Page(live)
    override suspend fun followedChannels(userId: String, first: Int, after: String?) = Page<FollowedChannel>()
    override suspend fun streams(first: Int, after: String?, gameId: String?, userLogin: String?) = Page<HelixStream>()
    override suspend fun topGames(first: Int, after: String?) = Page<HelixGame>()
    override suspend fun searchChannels(query: String, first: Int) = Page<SearchChannel>()
    override suspend fun searchCategories(query: String, first: Int) = Page<HelixGame>()
    override suspend fun globalBadges() = Page<tv.frostline.core.twitch.BadgeSet>()
    override suspend fun channelBadges(broadcasterId: String) = Page<tv.frostline.core.twitch.BadgeSet>()
    override suspend fun globalEmotes() = Page<tv.frostline.core.twitch.HelixEmote>()
    override suspend fun userEmotes(userId: String, after: String?) = Page<tv.frostline.core.twitch.HelixEmote>()
    override suspend fun videos(userId: String, type: String, first: Int, after: String?, sort: String) = Page<tv.frostline.core.twitch.HelixVideo>()
    override suspend fun videoById(id: String) = Page<tv.frostline.core.twitch.HelixVideo>()
    override suspend fun clips(broadcasterId: String, first: Int, after: String?, startedAt: String?, endedAt: String?) = Page<tv.frostline.core.twitch.HelixClip>()
    override suspend fun clipById(id: String) = Page<tv.frostline.core.twitch.HelixClip>()
}

private class FakeResume(items: List<ResumeEntity> = emptyList()) : ResumeDao {
    private val state = MutableStateFlow(items)
    override fun observeAll() = state
    override suspend fun get(videoId: String): ResumeEntity? = state.value.firstOrNull { it.videoId == videoId }
    override suspend fun upsert(entity: ResumeEntity) {}
    override suspend fun delete(videoId: String) {}
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class FollowingUiTest {
    @get:Rule val rule = createComposeRule()

    private val stream = HelixStream(
        id = "1", userId = "10", userLogin = "someone", userName = "Someone",
        gameName = "Just Chatting", type = "live", title = "A very long title that should be ellipsized on one line",
        viewerCount = 12_345, thumbnailUrl = "https://img/{width}x{height}.jpg",
    )

    @Test fun wideCardShowsNameTitleCategoryAndViewers() {
        var opened = 0
        rule.setContent { FrostlineTheme { WideStreamCard(stream, null, androidx.compose.ui.Modifier) { opened++ } } }
        rule.onNodeWithText("Someone").assertIsDisplayed()
        rule.onNodeWithText("Just Chatting").assertIsDisplayed()
        rule.onNodeWithText("12K viewers").assertIsDisplayed()
        rule.onNodeWithText(stream.title).requestFocus()
        rule.waitForIdle()
        rule.onNodeWithText(stream.title).performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(1, opened)
    }

    @Test fun guestSeesSignInPromptInsteadOfFollows() {
        val vm = HomeViewModel(HelixRepository(FakeApi()), FakeResume()) { null }
        rule.setContent { FrostlineTheme { FollowingContent(vm, {}, {}, {}) } }
        rule.waitForIdle()
        rule.onNodeWithText("Sign in to see your follows").assertIsDisplayed()
    }

    @Test fun signedInSeesLiveCardsAsAVerticalList() {
        val vm = HomeViewModel(HelixRepository(FakeApi(listOf(stream))), FakeResume()) { "99" }
        rule.setContent { FrostlineTheme { FollowingContent(vm, {}, {}, {}) } }
        rule.waitForIdle()
        rule.onNodeWithText("Live now").assertIsDisplayed()
        rule.onNodeWithText("Someone").assertIsDisplayed()
    }

    @Test fun comingBackToTheScreenRestoresFocusToTheCardThatWasOpened() {
        val vm = HomeViewModel(HelixRepository(FakeApi(listOf(stream))), FakeResume()) { "99" }
        var show by mutableStateOf(true)
        rule.setContent { FrostlineTheme { if (show) FollowingContent(vm, {}, {}, {}) } }
        rule.waitForIdle()
        rule.onNodeWithText(stream.title).requestFocus()
        rule.waitForIdle()
        vm.onLeaving() // what a card click does before navigating to the player
        rule.waitForIdle()
        rule.runOnUiThread { show = false } // the player is on top: the grid leaves the composition
        rule.waitForIdle()
        rule.runOnUiThread { show = true } // Back
        rule.waitForIdle()
        rule.onNodeWithText(stream.title).assertIsFocused()
    }

    @Test fun continueWatchingOpensTheVideo() {
        val resume = ResumeEntity("555", 600_000, 3_600_000, "Last night", "", "someone", 1L)
        val vm = HomeViewModel(HelixRepository(FakeApi(listOf(stream))), FakeResume(listOf(resume))) { "99" }
        var opened: String? = null
        rule.setContent { FrostlineTheme { FollowingContent(vm, {}, {}, { opened = it }) } }
        rule.waitForIdle()
        rule.onNodeWithText("Continue watching").assertIsDisplayed()
        rule.onNodeWithText("10:00 of 1:00:00").assertIsDisplayed()
        rule.onNodeWithText("Last night").requestFocus()
        rule.waitForIdle()
        rule.onNodeWithText("Last night").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals("555", opened)
    }
}
