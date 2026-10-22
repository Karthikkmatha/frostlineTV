package tv.frostline.feature.channel

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.frostline.core.twitch.ClipPeriod
import tv.frostline.core.twitch.HelixClip
import tv.frostline.core.twitch.HelixStream
import tv.frostline.core.twitch.HelixUser
import tv.frostline.core.twitch.HelixVideo
import tv.frostline.core.twitch.PagedResult
import tv.frostline.core.twitch.VideoKind
import java.io.IOException

private class FakeSource(
    var user: HelixUser? = HelixUser("10", "chan", "Chan", "https://img/p.png", offlineImageUrl = "https://img/off.png"),
    var live: HelixStream? = null,
    var liveFails: Boolean = false,
) : ChannelSource {
    val videoCalls = mutableListOf<Triple<VideoKind, String?, Int>>()
    val clipCalls = mutableListOf<Pair<ClipPeriod, String?>>()
    var failVideos = 0
    val total = 50

    override suspend fun user(login: String) = user
    override suspend fun live(login: String): HelixStream? {
        if (liveFails) throw IOException("no live")
        return live
    }

    override suspend fun videos(userId: String, kind: VideoKind, after: String?): PagedResult<HelixVideo> {
        videoCalls += Triple(kind, after, videoCalls.size)
        if (failVideos > 0) { failVideos--; throw IOException("boom") }
        val start = after?.toInt() ?: 0
        val items = (start until minOf(start + 24, total)).map { HelixVideo("${kind.name}-$it", type = kind.helixType, title = "t$it") }
        return PagedResult(items, if (start + 24 < total) (start + 24).toString() else null)
    }

    override suspend fun clips(userId: String, period: ClipPeriod, after: String?): PagedResult<HelixClip> {
        clipCalls += period to after
        val start = after?.toInt() ?: 0
        val items = (start until minOf(start + 24, 30)).map { HelixClip("clip-${period.name}-$it", title = "c$it") }
        return PagedResult(items, if (start + 24 < 30) (start + 24).toString() else null)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelViewModelTest {
    private fun kotlinx.coroutines.test.TestScope.vm(src: FakeSource) = ChannelViewModel("Chan", src, this)

    @Test fun loadsTheHeaderAndTheFirstPageOfPastBroadcasts() = runTest {
        val src = FakeSource()
        val vm = vm(src)
        advanceUntilIdle()
        val s = vm.state.value
        assertFalse(s.loading)
        assertEquals("Chan", s.displayName)
        assertNull(s.live)
        assertEquals(VideoKind.Broadcasts, s.videoKind)
        assertEquals(24, s.currentVideos.items.size)
        assertEquals(1, src.videoCalls.size)
        assertEquals(VideoKind.Broadcasts, src.videoCalls[0].first)
    }

    @Test fun liveChannelShowsLiveStatus() = runTest {
        val src = FakeSource(live = HelixStream("1", "10", "chan", "Chan", title = "Playing", viewerCount = 5))
        val vm = vm(src)
        advanceUntilIdle()
        assertNotNull(vm.state.value.live)
    }

    @Test fun aFailedLiveLookupDegradesToOfflineAndTheRestStillLoads() = runTest {
        val src = FakeSource(liveFails = true)
        val vm = vm(src)
        advanceUntilIdle()
        assertNull(vm.state.value.live)
        assertEquals(24, vm.state.value.currentVideos.items.size)
        assertNull(vm.state.value.error)
    }

    @Test fun unknownChannelIsAnError() = runTest {
        val vm = vm(FakeSource(user = null))
        advanceUntilIdle()
        assertEquals("Channel not found", vm.state.value.error)
    }

    @Test fun loadMorePagesByCursorUntilTheEnd() = runTest {
        val src = FakeSource()
        val vm = vm(src)
        advanceUntilIdle()
        vm.loadMore(); advanceUntilIdle()
        assertEquals(48, vm.state.value.currentVideos.items.size)
        vm.loadMore(); advanceUntilIdle()
        assertEquals(50, vm.state.value.currentVideos.items.size)
        assertNull(vm.state.value.currentVideos.cursor)
        val calls = src.videoCalls.size
        vm.loadMore(); advanceUntilIdle()
        assertEquals("no request past the end", calls, src.videoCalls.size)
        assertEquals(listOf(null, "24", "48"), src.videoCalls.map { it.second })
    }

    @Test fun loadMoreIgnoresRepeatedCallsWhileLoading() = runTest {
        val src = FakeSource()
        val vm = vm(src)
        advanceUntilIdle()
        vm.loadMore(); vm.loadMore(); vm.loadMore()
        advanceUntilIdle()
        assertEquals(2, src.videoCalls.size)
    }

    @Test fun eachKindHasItsOwnListNewestFirstAsServed() = runTest {
        val src = FakeSource()
        val vm = vm(src)
        advanceUntilIdle()
        vm.selectVideoKind(VideoKind.Highlights); advanceUntilIdle()
        vm.selectVideoKind(VideoKind.Uploads); advanceUntilIdle()
        assertTrue(vm.state.value.currentVideos.items.all { it.type == "upload" })
        vm.selectVideoKind(VideoKind.Broadcasts); advanceUntilIdle()
        assertEquals("kept, not refetched", 24, vm.state.value.currentVideos.items.size)
        assertEquals(3, src.videoCalls.size)
        assertEquals(VideoKind.entries.toSet(), vm.state.value.videos.keys)
    }

    @Test fun clipsLoadPerPeriodAndPage() = runTest {
        val src = FakeSource()
        val vm = vm(src)
        advanceUntilIdle()
        vm.selectTab(ChannelTab.Clips); advanceUntilIdle()
        assertEquals(ClipPeriod.Week, vm.state.value.clipPeriod)
        assertEquals(24, vm.state.value.currentClips.items.size)
        vm.loadMore(); advanceUntilIdle()
        assertEquals(30, vm.state.value.currentClips.items.size)
        vm.selectClipPeriod(ClipPeriod.Month); advanceUntilIdle()
        vm.selectClipPeriod(ClipPeriod.All); advanceUntilIdle()
        assertEquals(listOf(ClipPeriod.Week, ClipPeriod.Week, ClipPeriod.Month, ClipPeriod.All), src.clipCalls.map { it.first })
    }

    @Test fun aboutDoesNotFetchAList() = runTest {
        val src = FakeSource()
        val vm = vm(src)
        advanceUntilIdle()
        val before = src.videoCalls.size
        vm.selectTab(ChannelTab.About); advanceUntilIdle()
        vm.loadMore(); advanceUntilIdle()
        assertEquals(before, src.videoCalls.size)
        assertTrue(src.clipCalls.isEmpty())
    }

    @Test fun aFailedPageKeepsWhatIsThereAndRetryContinues() = runTest {
        val src = FakeSource()
        val vm = vm(src)
        advanceUntilIdle()
        src.failVideos = 1
        vm.loadMore(); advanceUntilIdle()
        assertEquals("boom", vm.state.value.currentVideos.error)
        assertEquals(24, vm.state.value.currentVideos.items.size)
        vm.loadMore(); advanceUntilIdle() // paging stops until Retry
        assertEquals(2, src.videoCalls.size)
        vm.retry(); advanceUntilIdle()
        assertNull(vm.state.value.currentVideos.error)
        assertEquals(48, vm.state.value.currentVideos.items.size)
    }

    @Test fun changingTheListForgetsTheRememberedFocus() = runTest {
        val vm = vm(FakeSource())
        advanceUntilIdle()
        vm.memory.key = "v:Broadcasts-3"
        vm.selectVideoKind(VideoKind.Highlights)
        assertNull(vm.memory.key)
    }
}
