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

    private fun refreshFault() =
        """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>""" +
            """<faultcode>s:Client.TokenRefreshRequired</faultcode>""" +
            """<faultstring>Token has expired</faultstring><detail>""" +
            """<refreshAuthTokenResult xmlns="http://www.sonos.com/Services/1.1">""" +
            """<authToken>fresh-token</authToken><privateKey>fresh-key</privateKey>""" +
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
}
