package com.rahga.x2rock.account

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/**
 * The authorization-code flow against a stand-in for Sonos's token endpoint. The reply is the shape
 * Sonos documents for `/login/v3/oauth/access` (access and refresh tokens, `expires_in`), not a
 * capture: no real token belongs in a fixture.
 */
class SonosAccountTest {

    private lateinit var server: MockWebServer
    private var clock = 1_000_000L
    private val store = object : TokenStore {
        var held: AccountToken? = null
        override fun load() = held
        override fun save(token: AccountToken?) { held = token }
    }
    private lateinit var account: SonosAccount

    private fun tokenReply(access: String, refresh: String? = "r-1") = MockResponse().setBody(
        """{"access_token":"$access","token_type":"Bearer",""" +
            (refresh?.let { """"refresh_token":"$it",""" } ?: "") +
            """"expires_in":86399,"scope":"playback-control-all"}"""
    )

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        account = SonosAccount(
            OkHttpClient(), Integration("the-key", "the-secret", "https://example.github.io/x/callback.html"),
            store, base = server.url("").toString().trimEnd('/'), now = { clock },
        )
    }

    @After fun tearDown() = server.shutdown()

    @Test fun `the consent page names the integration, the scope, the redirect and the state`() {
        val url = account.authorizeUrl("192-168-1-20-8765.abc")
        assertTrue(url, url.startsWith(server.url("/login/v3/oauth?").toString()))
        assertTrue(url, "client_id=the-key" in url && "response_type=code" in url && "scope=playback-control-all" in url)
        assertTrue(url, "redirect_uri=https%3A%2F%2Fexample.github.io%2Fx%2Fcallback.html" in url)
        assertTrue(url, "state=192-168-1-20-8765.abc" in url)
    }

    @Test fun `a code is redeemed with the integration's own credentials, and kept`() = runBlocking {
        server.enqueue(tokenReply("a-1"))
        account.finish("the-code")

        val sent = server.takeRequest()
        assertEquals("/login/v3/oauth/access", sent.path)
        assertEquals(okhttp3.Credentials.basic("the-key", "the-secret"), sent.getHeader("Authorization"))
        val form = sent.body.readUtf8()
        assertTrue(form, "grant_type=authorization_code" in form && "code=the-code" in form)
        assertTrue(form, "redirect_uri=https%3A%2F%2Fexample.github.io" in form)
        assertTrue(account.signedIn.value)
        assertEquals("a-1", account.bearer())
        assertEquals("a fresh token is used without asking again", 1, server.requestCount)
    }

    @Test fun `a token near its end is refreshed first, keeping the refresh token Sonos did not replace`() = runBlocking {
        store.held = AccountToken("a-1", "r-1", expiresIn = 86399, obtainedAt = clock)
        clock += 86399 - 300
        server.enqueue(tokenReply("a-2", refresh = null))

        assertEquals("a-2", account.bearer())
        val form = server.takeRequest().body.readUtf8()
        assertTrue(form, "grant_type=refresh_token" in form && "refresh_token=r-1" in form)
        assertEquals("r-1", store.held?.refreshToken)
    }

    @Test fun `a refresh Sonos refuses signs out`() = runBlocking {
        store.held = AccountToken("a-1", "r-1", expiresIn = 100, obtainedAt = clock - 1000)
        account = SonosAccount(OkHttpClient(), account.integration, store, server.url("").toString().trimEnd('/'), now = { clock })
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))

        assertNull(account.bearer())
        assertFalse(account.signedIn.value)
        assertNull(store.held)
    }

    @Test fun `Sonos out of reach leaves a token that has not yet expired`() = runBlocking {
        store.held = AccountToken("a-1", "r-1", expiresIn = 1000, obtainedAt = clock - 900)
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals("a-1", account.bearer())
        assertTrue("still signed in", store.held != null)
    }

    @Test fun `the state carries the TV's private address and port`() {
        val state = signInState(InetAddress.getByName("192.168.86.250") as Inet4Address, 8765)
        assertTrue(state, Regex("""^192-168-86-250-8765\.[0-9a-f]{24}$""").matches(state))
        assertTrue(runCatching { signInState(InetAddress.getByName("8.8.8.8") as Inet4Address, 8765) }.isFailure)
    }
}
