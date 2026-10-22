package tv.frostline.feature.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tv.frostline.core.data.GcmKeyCipher
import tv.frostline.core.data.InMemoryPreferencesStore
import tv.frostline.core.data.TokenVault
import tv.frostline.core.twitch.HelixUser
import tv.frostline.core.twitch.TwitchConfig
import tv.frostline.core.twitch.TwitchOAuth
import tv.frostline.core.twitchinternal.ProbeResult
import tv.frostline.core.twitchinternal.WebSessionValidator
import javax.crypto.KeyGenerator

private class FakeCookies : WebCookies {
    @Volatile var token: String? = null
    @Volatile var cleared = 0
    override fun authToken() = token
    override fun clearAll() { cleared++ }
}

private class FakeValidator : WebSessionValidator {
    override suspend fun validate(authToken: String) = if (authToken == "good") ProbeResult.Valid else ProbeResult.Invalid
}

class SignInFlowTest {
    private lateinit var server: MockWebServer
    private lateinit var vault: TokenVault
    private lateinit var cookies: FakeCookies
    private lateinit var gate: Channel<Unit>
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        vault = TokenVault(InMemoryPreferencesStore(), GcmKeyCipher { key })
        cookies = FakeCookies()
        gate = Channel(Channel.UNLIMITED)
    }

    @After fun tearDown() {
        job.cancel()
        server.shutdown()
    }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)

    private fun device(expiresIn: Int = 1800, code: String = "ABCD1234") = json(
        """{"device_code":"dev-$code","user_code":"$code","verification_uri":"https://www.twitch.tv/activate?device-code=$code","expires_in":$expiresIn,"interval":5}""",
    )

    private fun tokens() = json("""{"access_token":"ACC","refresh_token":"REF","expires_in":14400,"scope":["chat:read"]}""")

    private fun flow(webOnly: Boolean = false): SignInFlow {
        val oauth = TwitchOAuth(
            OkHttpClient(),
            TwitchConfig("cid", "secret", idBaseUrl = server.url("/").toString(), helixBaseUrl = server.url("/helix/").toString()),
        )
        return SignInFlow(
            oauth, vault, me = { HelixUser("42", "tester") }, FakeValidator(), cookies, scope,
            webOnly = webOnly, sleep = { gate.receive() }, cookiePollMs = 20,
        )
    }

    private suspend fun SignInFlow.await(pred: (SignInUi) -> Boolean) = withTimeout(10_000) { ui.first(pred) }

    @Test fun webLoginThenInWebViewAuthorizeStoresBothTokens() = runBlocking {
        server.enqueue(device())
        val f = flow()
        f.startDeviceCode()
        f.await { it.codeStatus == CodeStatus.Ready }

        f.openWebLogin()
        assertEquals(SignInFlow.LOGIN_URL, f.ui.value.webUrl)
        cookies.token = "good"
        val ready = f.await { it.step == Step.WebAuthorize }
        assertEquals("https://www.twitch.tv/activate?device-code=ABCD1234", ready.webUrl)
        assertEquals("good", vault.currentWebSession())
        assertTrue(f.ui.value.webSessionLinked)

        // The user presses Authorize in the WebView; the app's polling completes.
        server.takeRequest() // device request
        server.enqueue(tokens())
        gate.send(Unit)
        f.await { it.step == Step.Done }
        val t = vault.currentTokens()!!
        assertEquals("ACC", t.accessToken)
        assertEquals("tester", t.login)
        assertEquals("good", vault.currentWebSession())
        assertTrue("web data cleared", cookies.cleared >= 1)
        assertNull(f.ui.value.webUrl)
    }

    @Test fun invalidCookieKeepsWaitingAndStoresNothing() = runBlocking {
        server.enqueue(device())
        val f = flow()
        f.startDeviceCode()
        f.await { it.codeStatus == CodeStatus.Ready }
        f.openWebLogin()
        cookies.token = "stale-or-anonymous"
        assertFalse(f.checkCookie())
        assertEquals(Step.WebLogin, f.ui.value.step)
        assertNull(vault.currentWebSession())
        f.closeWeb()
    }

    @Test fun skipLoginStillAuthorizesWithDeviceCodeAlone() = runBlocking {
        server.enqueue(device())
        val f = flow()
        f.startDeviceCode()
        f.await { it.codeStatus == CodeStatus.Ready }
        f.openWebLogin()
        f.skipToAuthorize()
        val s = f.await { it.step == Step.WebAuthorize }
        assertTrue(s.webUrl!!.contains("device-code=ABCD1234"))
        assertNull(vault.currentWebSession())
        server.enqueue(tokens())
        gate.send(Unit)
        f.await { it.step == Step.Done }
        assertNotNull(vault.currentTokens())
    }

    @Test fun nearlyExpiredCodeIsReplacedBeforeAuthorize() = runBlocking {
        server.enqueue(device(expiresIn = 60, code = "OLDCODE1"))
        server.enqueue(device(code = "NEWCODE2"))
        val f = flow()
        f.startDeviceCode()
        f.await { it.codeStatus == CodeStatus.Ready }
        f.skipToAuthorize()
        val s = f.await { it.step == Step.WebAuthorize }
        assertTrue(s.webUrl!!.contains("NEWCODE2"))
        assertEquals(2, server.requestCount)
    }

    @Test fun qrOnlyPathCompletesWithoutWebView() = runBlocking {
        server.enqueue(device())
        server.enqueue(tokens())
        val f = flow()
        f.startDeviceCode()
        f.await { it.codeStatus == CodeStatus.Ready }
        gate.send(Unit)
        f.await { it.step == Step.Done }
        assertEquals("ACC", vault.currentTokens()!!.accessToken)
        assertNull(vault.currentWebSession())
    }

    @Test fun webOnlyLinksSessionWithoutDeviceCode() = runBlocking {
        val f = flow(webOnly = true)
        f.startDeviceCode() // no-op in web-only mode
        f.openWebLogin()
        cookies.token = "good"
        f.await { it.step == Step.Done }
        assertEquals("good", vault.currentWebSession())
        assertNull(vault.currentTokens())
        assertEquals(0, server.requestCount)
        assertTrue(cookies.cleared >= 1)
    }

    @Test fun closingTheWebViewWipesItAndReturnsToLanding() = runBlocking {
        server.enqueue(device())
        val f = flow()
        f.startDeviceCode()
        f.openWebLogin()
        f.closeWeb()
        assertEquals(Step.Landing, f.ui.value.step)
        assertTrue(cookies.cleared >= 1)
    }

    @Test fun deniedCodeIsReported() = runBlocking {
        server.enqueue(device())
        server.enqueue(json("""{"status":400,"message":"access_denied"}""", 400))
        val f = flow()
        f.startDeviceCode()
        f.await { it.codeStatus == CodeStatus.Ready }
        gate.send(Unit)
        f.await { it.codeStatus == CodeStatus.Denied }
        Unit
    }
}
