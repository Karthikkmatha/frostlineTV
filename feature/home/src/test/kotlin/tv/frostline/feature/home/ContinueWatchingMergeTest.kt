package tv.frostline.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.frostline.core.data.ResumeEntity
import tv.frostline.core.twitchinternal.HistoryItem

class ContinueWatchingMergeTest {
    private fun local(id: String, posSec: Long, at: Long, durSec: Long = 3_600) =
        ResumeEntity(id, posSec * 1000, durSec * 1000, "local $id", "https://t/$id.jpg", "chan", at)

    private fun remote(id: String, posSec: Int, at: Long, len: Int = 3_600) =
        HistoryItem(id, posSec, at, title = "twitch $id", lengthSec = len, thumbnailUrl = "https://tw/$id.jpg", channelLogin = "ch", channelName = "Chan")

    @Test fun withoutTwitchTheLocalRowIsExactlyWhatItWas() {
        val l = listOf(local("1", 600, 5), local("2", 900, 3))
        assertTrue(mergeResume(l, null) === l)
    }

    @Test fun whatWasStartedOnThePhoneAppearsAtTheRightSpot() {
        val m = mergeResume(listOf(local("1", 600, 5)), listOf(remote("9", 1_234, 9)))
        assertEquals(listOf("9", "1"), m.map { it.videoId })
        val phone = m.first()
        assertEquals(1_234_000L, phone.position)
        assertEquals(3_600_000L, phone.duration)
        assertEquals("Chan", phone.channel)
        assertEquals("https://tw/9.jpg", phone.thumbnail)
    }

    @Test fun theNewestUpdatedAtWinsForTheSameVideo() {
        val l = listOf(local("1", 600, at = 5_000))
        assertEquals(600_000L, mergeResume(l, listOf(remote("1", 100, at = 1_000))).single().position)
        assertEquals(100_000L, mergeResume(l, listOf(remote("1", 100, at = 9_000))).single().position)
    }

    @Test fun aVideoFinishedOnAnotherDeviceDisappears() {
        assertTrue(mergeResume(listOf(local("1", 600, 5)), listOf(remote("1", 3_550, 9))).isEmpty())
    }

    @Test fun anEmptyTwitchListKeepsTheLocalEntries() {
        assertEquals(listOf("1"), mergeResume(listOf(local("1", 600, 5)), emptyList()).map { it.videoId })
    }
}
