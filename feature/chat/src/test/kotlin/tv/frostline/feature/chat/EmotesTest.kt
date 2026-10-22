package tv.frostline.feature.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmoteParsersTest {
    @Test fun sevenTvChannelSet() {
        val json = """{"id":"u","emote_set":{"emotes":[
          {"id":"A1","name":"PogU","flags":0,"data":{"flags":0,"animated":true,"host":{"url":"//cdn.7tv.app/emote/A1","files":[{"name":"1x.webp","width":32,"height":32},{"name":"2x.webp","width":64,"height":32}]}}},
          {"id":"B2","name":"cvHazmat","flags":1,"data":{"flags":0,"host":{"url":"//cdn.7tv.app/emote/B2","files":[]}}},
          {"id":"C3","name":"Overlay","flags":0,"data":{"flags":256,"host":{"url":"//cdn.7tv.app/emote/C3"}}},
          {"name":"noid"}
        ]}}"""
        val e = EmoteParsers.sevenTv(json)
        assertEquals(listOf("PogU", "cvHazmat", "Overlay"), e.map { it.code })
        assertEquals("https://cdn.7tv.app/emote/A1/2x.webp", e[0].url)
        assertEquals(2f, e[0].aspect, 0.001f)
        assertFalse(e[0].zeroWidth)
        assertTrue(e[1].zeroWidth)
        assertTrue(e[2].zeroWidth)
        assertEquals(EmoteProvider.SevenTv, e[0].provider)
    }

    @Test fun sevenTvGlobalShape() {
        val e = EmoteParsers.sevenTv("""{"emotes":[{"id":"G","name":"Global","data":{"host":{"url":"//cdn.7tv.app/emote/G"}}}]}""")
        assertEquals(1, e.size)
        assertEquals("https://cdn.7tv.app/emote/G/2x.webp", e[0].url)
    }

    @Test fun bttvGlobalArrayAndChannelObject() {
        val g = EmoteParsers.bttv("""[{"id":"1","code":"OMEGALUL","imageType":"png"},{"id":"2","code":"monkaS"}]""")
        assertEquals(listOf("OMEGALUL", "monkaS"), g.map { it.code })
        assertEquals("https://cdn.betterttv.net/emote/1/2x.webp", g[0].url)
        val c = EmoteParsers.bttv("""{"channelEmotes":[{"id":"3","code":"chanE"}],"sharedEmotes":[{"id":"4","code":"sharedE","width":56,"height":28}]}""")
        assertEquals(listOf("chanE", "sharedE"), c.map { it.code })
        assertEquals(2f, c[1].aspect, 0.001f)
    }

    @Test fun ffzGlobalUsesDefaultSetsOnly() {
        val json = """{"default_sets":[3],"sets":{
          "3":{"emoticons":[{"id":10,"name":"ZrehplaR","width":28,"height":28,"urls":{"1":"//cdn.frankerfacez.com/emote/10/1","2":"//cdn.frankerfacez.com/emote/10/2"}}]},
          "9":{"emoticons":[{"id":11,"name":"NotDefault","urls":{"1":"//x/1"}}]}}}"""
        val e = EmoteParsers.ffz(json)
        assertEquals(listOf("ZrehplaR"), e.map { it.code })
        assertEquals("https://cdn.frankerfacez.com/emote/10/2", e[0].url)
    }

    @Test fun ffzRoomShapeAndFallbackToOneX() {
        val json = """{"room":{"set":55},"sets":{"55":{"emoticons":[{"id":1,"name":"RoomE","urls":{"1":"//cdn/1"}},{"id":2,"name":"NoUrls","urls":{}}]}}}"""
        val e = EmoteParsers.ffz(json)
        assertEquals(listOf("RoomE"), e.map { it.code })
        assertEquals("https://cdn/1", e[0].url)
    }

    @Test fun garbageYieldsNothing() {
        assertEquals(0, EmoteParsers.sevenTv("not json").size)
        assertEquals(0, EmoteParsers.bttv("{}").size)
        assertEquals(0, EmoteParsers.ffz("[]").size)
    }

    @Test fun indexPrecedenceSevenTvOverBttvOverFfzAndChannelOverGlobal() {
        fun em(p: EmoteProvider, id: String) = Emote(id, "same", "u", p)
        val sets = EmoteSets(
            sevenTv = listOf(em(EmoteProvider.SevenTv, "chan7"), em(EmoteProvider.SevenTv, "glob7")),
            bttv = listOf(em(EmoteProvider.Bttv, "bttv")),
            ffz = listOf(em(EmoteProvider.Ffz, "ffz")),
        )
        assertEquals("chan7", sets.index()["same"]!!.id)
        assertEquals("bttv", sets.index(use7tv = false)["same"]!!.id)
        assertEquals("ffz", sets.index(use7tv = false, useBttv = false)["same"]!!.id)
        assertNull(sets.index(false, false, false)["same"])
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class EmoteRepositoryTest {
    private class Counting(val bodies: Map<String, String>) : TextFetcher {
        val calls = mutableListOf<String>()
        override suspend fun fetch(url: String): String? {
            calls += url
            return bodies.entries.firstOrNull { url.contains(it.key) }?.value
        }
    }

    private val bodies = mapOf(
        "7tv.io/v3/emote-sets/global" to """{"emotes":[{"id":"g7","name":"G7","data":{"host":{"url":"//c/g7"}}}]}""",
        "7tv.io/v3/users/twitch/42" to """{"emote_set":{"emotes":[{"id":"c7","name":"C7","data":{"host":{"url":"//c/c7"}}}]}}""",
        "betterttv.net/3/cached/emotes/global" to """[{"id":"gb","code":"GB"}]""",
        "betterttv.net/3/cached/users/twitch/42" to """{"channelEmotes":[{"id":"cb","code":"CB"}],"sharedEmotes":[]}""",
        "frankerfacez.com/v1/set/global" to """{"default_sets":[3],"sets":{"3":{"emoticons":[{"id":1,"name":"GF","urls":{"1":"//f/1"}}]}}}""",
        "frankerfacez.com/v1/room/id/42" to """{"sets":{"1":{"emoticons":[{"id":2,"name":"CF","urls":{"1":"//f/2"}}]}}}""",
    )

    @Test fun mergesChannelBeforeGlobalPerProvider() = runTest {
        val s = EmoteRepository(Counting(bodies)).forChannel("42")
        assertEquals(listOf("C7", "G7"), s.sevenTv.map { it.code })
        assertEquals(listOf("CB", "GB"), s.bttv.map { it.code })
        assertEquals(listOf("CF", "GF"), s.ffz.map { it.code })
    }

    @Test fun secondRequestForTheSameChannelIsServedFromCache() = runTest {
        val f = Counting(bodies)
        val r = EmoteRepository(f)
        r.forChannel("42")
        val n = f.calls.size
        assertEquals(6, n)
        r.forChannel("42")
        assertEquals(n, f.calls.size)
    }

    @Test fun globalsAreFetchedOnceAcrossChannels() = runTest {
        val f = Counting(bodies)
        val r = EmoteRepository(f)
        r.forChannel("42")
        r.forChannel("99")
        assertEquals(1, f.calls.count { it.contains("emote-sets/global") })
        assertEquals(1, f.calls.count { it.contains("7tv.io/v3/users/twitch/99") })
    }

    @Test fun failedChannelLookupIsNotCachedSoItRetries() = runTest {
        val f = Counting(bodies.filterKeys { it.contains("global") })
        val r = EmoteRepository(f)
        val first = r.forChannel("42")
        assertEquals(listOf("G7"), first.sevenTv.map { it.code }) // globals still show
        r.forChannel("42")
        assertEquals(2, f.calls.count { it.contains("7tv.io/v3/users/twitch/42") })
    }

    @Test fun lruEvictsTheOldestChannel() = runTest {
        val f = Counting(bodies + mapOf("users/twitch/7" to """{"emote_set":{"emotes":[{"id":"x","name":"X","data":{"host":{"url":"//c/x"}}}]}}""",
            "users/twitch/8" to """{"emote_set":{"emotes":[{"id":"y","name":"Y","data":{"host":{"url":"//c/y"}}}]}}"""))
        val r = EmoteRepository(f, maxChannels = 2)
        r.forChannel("42"); r.forChannel("7"); r.forChannel("8")
        val before = f.calls.count { it.contains("7tv.io/v3/users/twitch/42") }
        r.forChannel("42") // was evicted
        assertEquals(before + 1, f.calls.count { it.contains("7tv.io/v3/users/twitch/42") })
    }

    @Test fun noChannelIdGivesGlobalsOnly() = runTest {
        val s = EmoteRepository(Counting(bodies)).forChannel(null)
        assertEquals(listOf("G7"), s.sevenTv.map { it.code })
    }
}
