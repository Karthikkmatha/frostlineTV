package tv.frostline.core.twitch

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.frostline.core.data.TokenSet

class HelixParsingTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var repo: HelixRepository

    @Before fun setUp() = runBlocking {
        server = MockWebServer().also { it.start() }
        val vault = Fixtures.vault(tmp.root)
        vault.saveTokens(TokenSet("tok", "r", System.currentTimeMillis() + 3_600_000L, userId = "7"))
        val cfg = Fixtures.config(server)
        val mgr = TokenManager(vault, TwitchOAuth(okhttp3.OkHttpClient(), cfg))
        repo = HelixRepository(createHelixApi(cfg, mgr))
    }

    @After fun tearDown() = server.shutdown()

    @Test fun parsesFollowedStreamsAndSortsByViewers() = runBlocking {
        server.enqueue(Fixtures.json("""{"data":[
          {"id":"1","user_id":"10","user_login":"small","user_name":"Small","game_id":"g","game_name":"Chess","type":"live","title":"Hi","viewer_count":5,"started_at":"2026-10-22T00:00:00Z","thumbnail_url":"https://x/{width}x{height}.jpg","tag_ids":[],"unknown":1},
          {"id":"2","user_id":"11","user_login":"big","user_name":"Big","game_name":"Go","type":"live","title":"Yo","viewer_count":900,"thumbnail_url":"https://y/{width}x{height}.jpg"}
        ],"pagination":{}}"""))
        val live = repo.followedLive("7")
        assertEquals(listOf("Big", "Small"), live.map { it.userName })
        assertEquals("https://y/440x248.jpg", live.first().thumbnailUrl.sized(440, 248))
        val req = server.takeRequest()
        assertTrue(req.path!!.startsWith("/helix/streams/followed?user_id=7"))
        assertEquals("Bearer tok", req.getHeader("Authorization"))
    }

    @Test fun followedChannelsFollowsEveryPage() = runBlocking {
        server.enqueue(Fixtures.json("""{"total":3,"data":[{"broadcaster_id":"1","broadcaster_login":"a","broadcaster_name":"A","followed_at":"x"},{"broadcaster_id":"2","broadcaster_login":"b","broadcaster_name":"B"}],"pagination":{"cursor":"CUR1"}}"""))
        server.enqueue(Fixtures.json("""{"total":3,"data":[{"broadcaster_id":"3","broadcaster_login":"c","broadcaster_name":"C"}],"pagination":{}}"""))
        val all = repo.followedChannels("7")
        assertEquals(listOf("a", "b", "c"), all.map { it.login })
        server.takeRequest()
        assertTrue(server.takeRequest().path!!.contains("after=CUR1"))
    }

    @Test fun parsesTopGamesAndSearch() = runBlocking {
        server.enqueue(Fixtures.json("""{"data":[{"id":"1","name":"Just Chatting","box_art_url":"https://b/{width}x{height}.jpg","igdb_id":"9"}]}"""))
        assertEquals("Just Chatting", repo.topCategories().single().name)
        server.enqueue(Fixtures.json("""{"data":[{"id":"5","broadcaster_login":"ninja","display_name":"Ninja","game_name":"Fortnite","is_live":true,"title":"t","thumbnail_url":"u","tags":["a"]}]}"""))
        val ch = repo.searchChannels("nin").single()
        assertTrue(ch.isLive)
        assertEquals("Ninja", ch.displayName)
    }

    @Test fun offlineIsFollowedMinusLiveSortedAz() {
        fun f(id: String, name: String) = FollowedChannel(id, name.lowercase(), name)
        fun s(user: String) = HelixStream(id = "s$user", userId = user, userLogin = "l", userName = "n")
        val all = listOf(f("1", "zed"), f("2", "Alpha"), f("3", "beta"), f("4", "Charlie"), f("5", "alpine"))
        val off = offlineChannels(all, listOf(s("3")))
        assertEquals(listOf("Alpha", "alpine", "Charlie", "zed"), off.map { it.displayName })
    }
}
