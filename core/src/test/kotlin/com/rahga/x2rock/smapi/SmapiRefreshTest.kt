package com.rahga.x2rock.smapi

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * A `tokenRefreshRequired` fault carries a working replacement token in its detail; a call that
 * hits one retries once with it rather than failing. Seen on TIDAL and a second Amazon account
 * against a household whose stored token had aged (2026-10-06). The fault shape is SMAPI's, as
 * x2rock recorded it (`sonos/smapi.rs`).
 */
class SmapiRefreshTest {

    private lateinit var server: MockWebServer
    private lateinit var client: SmapiClient
    private lateinit var service: Service

    private fun refreshFault(fresh: String = "fresh-token") =
        """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>""" +
            """<faultcode>s:Client.TokenRefreshRequired</faultcode>""" +
            """<faultstring>Token has expired</faultstring><detail>""" +
            """<refreshAuthTokenResult xmlns="http://www.sonos.com/Services/1.1">""" +
            """<authToken>$fresh</authToken><privateKey>fresh-key</privateKey>""" +
            """<userInfo><userIdHashCode>abc</userIdHashCode></userInfo>""" +
            """</refreshAuthTokenResult></detail></s:Fault></s:Body></s:Envelope>"""

    private fun searchResult() =
        """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>""" +
            """<searchResponse><searchResult><total>1</total>""" +
            """<mediaMetadata><id>t:1</id><itemType>track</itemType><title>So What</title></mediaMetadata>""" +
            """</searchResult></searchResponse></s:Body></s:Envelope>"""

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        client = SmapiClient(OkHttpClient())
        service = Service(id = "174", name = "TIDAL", uri = server.url("/smapi").toString(), auth = Auth.APP_LINK, manifestUri = null)
    }

    @After fun tearDown() = server.shutdown()

    @Test fun `a refresh fault is retried once with the replacement token`() = runBlocking {
        server.enqueue(MockResponse().setBody(refreshFault()))
        server.enqueue(MockResponse().setBody(searchResult()))

        val page = client.search(service, Token("stale-token", "stale-key", "hh"), category = "tracks", term = "jazz")
        assertEquals("the retry's result is returned, not the fault", "So What", page.items.single().title)

        // Two calls: the first with the stale token, the retry with the fresh one.
        assertEquals(2, server.requestCount)
        assertTrue("stale-token" in server.takeRequest().body.readUtf8())
        val retry = server.takeRequest().body.readUtf8()
        assertTrue("the replacement token was used", "<token>fresh-token</token>" in retry && "<key>fresh-key</key>" in retry)
    }

    /**
     * The replacement is kept: callers go on passing the token the household stored, and every
     * later call used to send that stale one again, fault, and retry. Now only the first does.
     */
    @Test fun `a refreshed token is used for the calls after it`() = runBlocking {
        val stale = Token("stale-token", "stale-key", "hh")
        server.enqueue(MockResponse().setBody(refreshFault()))
        server.enqueue(MockResponse().setBody(searchResult()))
        server.enqueue(MockResponse().setBody(searchResult()))

        client.search(service, stale, category = "tracks", term = "jazz")
        client.search(service, stale, category = "albums", term = "jazz")

        assertEquals("fault, retry, then straight to the answer", 3, server.requestCount)
        server.takeRequest(); server.takeRequest()
        val next = server.takeRequest().body.readUtf8()
        assertTrue("the second search sent the fresh token", "<token>fresh-token</token>" in next)
    }

    /**
     * Search asks a service's two categories at once, and on an aged token each takes a refresh.
     * A service that keeps only its latest replacement answers the other's retry with a refresh
     * of its own, which used to be dropped: one of the two sections read as refused (P-112).
     */
    @Test fun `a retry answered with another refresh takes it`() = runBlocking {
        server.enqueue(MockResponse().setBody(refreshFault("fresh-1")))
        server.enqueue(MockResponse().setBody(refreshFault("fresh-2")))
        server.enqueue(MockResponse().setBody(searchResult()))

        val page = client.search(service, Token("stale-token", "stale-key", "hh"), category = "tracks", term = "jazz")

        assertEquals("So What", page.items.single().title)
        assertEquals(3, server.requestCount)
        server.takeRequest(); server.takeRequest()
        assertTrue("the second replacement was used", "<token>fresh-2</token>" in server.takeRequest().body.readUtf8())
    }

    /** And it gives up at the bound: a service refreshing every call is not retried forever. */
    @Test fun `a call stops taking refreshes at its bound`() = runBlocking {
        repeat(4) { server.enqueue(MockResponse().setBody(refreshFault("fresh-$it"))) }
        val failed = runCatching { client.search(service, Token("stale-token", "stale-key", "hh"), "tracks", "jazz") }.isFailure
        assertTrue(failed)
        assertEquals("the call and two refreshed retries", 3, server.requestCount)
    }

    /**
     * A captive portal or a CDN's error page answered with 200 went on to the parser, and reached
     * the viewer as "Content is not allowed in prolog" (P-113).
     */
    @Test fun `a success that is not an answer is said as such`() = runBlocking {
        server.enqueue(MockResponse().setBody("Sign in to this network to continue"))
        val failure = runCatching { client.search(service, Token("t", "k", "hh"), "tracks", "jazz") }.exceptionOrNull()
        assertEquals("TIDAL sent back something other than an answer", (failure as? SmapiException)?.message)
    }

    /** A JSON null in a manifest's endpoints used to throw the service's whole page away (P-114). */
    @Test fun `a manifest endpoint with a null type is passed over`() = runBlocking {
        val paged = service.copy(manifestUri = server.url("/manifest").toString())
        server.enqueue(
            MockResponse().setBody(
                """{"endpoints":[{"type":null,"uri":null},{"type":"browse","uri":"${server.url("/browse")}"}]}"""
            )
        )
        server.enqueue(MockResponse().setBody(com.rahga.x2rock.lan.FakePlayer.fixtureText("spotify.browse.json")))
        assertEquals("More like Tyler, The Creator", client.shelves(paged, null).first().title)
    }

    @Test fun `a fault with no refresh token is not retried`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>""" +
                    """<faultcode>s:Client.NOT_AUTHORIZED</faultcode><faultstring>nope</faultstring>""" +
                    """</s:Fault></s:Body></s:Envelope>"""
            )
        )
        val failed = runCatching { client.search(service, Token("t", "k", "hh"), "tracks", "jazz") }.isFailure
        assertTrue("an ordinary fault still fails", failed)
        assertEquals("and is not retried", 1, server.requestCount)
    }

    /**
     * A service's own page answers a stale bearer 401 rather than with a refresh fault: Spotify's
     * went from 200 to 401 within the hour (2026-10-11). One SMAPI call takes the refresh, and the
     * page is asked again with the token it handed back.
     */
    @Test fun `a page refused for a stale token is asked again with the refreshed one`() = runBlocking {
        val paged = service.copy(manifestUri = server.url("/manifest").toString())
        server.enqueue(MockResponse().setBody("""{"endpoints":[{"type":"browse","uri":"${server.url("/browse")}"}]}"""))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody(refreshFault()))
        server.enqueue(MockResponse().setBody(searchResult()))
        server.enqueue(MockResponse().setBody(com.rahga.x2rock.lan.FakePlayer.fixtureText("spotify.browse.json")))

        val shelves = client.shelves(paged, Token("stale-token", "stale-key", "hh"))

        assertEquals("More like Tyler, The Creator", shelves.first().title)
        server.takeRequest()
        assertEquals("Bearer stale-token", server.takeRequest().getHeader("Authorization"))
        server.takeRequest(); server.takeRequest()
        assertEquals("Bearer fresh-token", server.takeRequest().getHeader("Authorization"))
    }

    /**
     * Sonos Radio's page needs no token and refuses the household's with 401 (2026-10-11): with no
     * refresh to take, it is asked again without one, and its shelves come back.
     */
    @Test fun `a page that refuses a token is asked again without one`() = runBlocking {
        val paged = service.copy(manifestUri = server.url("/manifest").toString())
        server.enqueue(MockResponse().setBody("""{"endpoints":[{"type":"browse","uri":"${server.url("/browse")}"}]}"""))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody(searchResult()))
        server.enqueue(MockResponse().setBody(com.rahga.x2rock.lan.FakePlayer.fixtureText("sonosradio.browse.json")))

        val shelves = client.shelves(paged, Token("radio-token", "radio-key", "hh"))

        assertEquals("Trending Now", shelves.first().title)
        server.takeRequest()
        assertEquals("Bearer radio-token", server.takeRequest().getHeader("Authorization"))
        server.takeRequest()
        assertEquals("the last ask carries no token", null, server.takeRequest().getHeader("Authorization"))
    }
}
