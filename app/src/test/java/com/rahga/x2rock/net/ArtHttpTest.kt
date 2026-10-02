package com.rahga.x2rock.net

import com.rahga.x2rock.lan.PlayerAddressBook
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress

/**
 * Cover art as a player serves it, `http://sonos-<MAC>.local:1400/getaa`, from a fake on
 * loopback that the address book names as a player.
 */
class ArtHttpTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val host = "sonos-aa00bbccddee.local"

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(1_000)

    @Before fun setUp() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        val book = PlayerAddressBook().apply { register(host, InetAddress.getByName("127.0.0.1")) }
        client = ArtHttp.bounded(OkHttpClient.Builder().dns(book).build()) { ArtHttp.isPlayer(it, server.port) }
    }

    @After fun tearDown() = server.shutdown()

    private fun art(path: String = "/getaa") = "http://$host:${server.port}$path"

    private fun fetch(url: String = art()): ByteArray =
        client.newCall(Request.Builder().url(url).build()).execute().use { it.body!!.bytes() }

    private fun refused(url: String = art()) {
        try {
            fetch(url)
            fail("fetched art the rules refuse")
        } catch (e: IOException) {
            assertTrue(e.message, e.message.orEmpty().startsWith("art refused"))
        }
    }

    @Test fun `a player's image is fetched`() {
        server.enqueue(MockResponse().setBody(Buffer().write(jpeg)))
        assertEquals(jpeg.size, fetch().size)
    }

    /** Counted as it arrives: a chunked body declares no length to refuse up front. */
    @Test fun `an image past the cap is refused as it arrives`() {
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(jpeg + ByteArray(ArtHttp.MAX_BYTES.toInt())), 64 * 1024))
        refused()
    }

    /** Refused on the header alone, rather than after reading 2 MB of it to find out. */
    @Test fun `a declared length past the cap is refused before the body`() {
        val size = jpeg.size + ArtHttp.MAX_BYTES.toInt()
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(size).also { jpeg.copyInto(it) })))
        try {
            fetch()
            fail("fetched art past the cap")
        } catch (e: IOException) {
            assertEquals("refused by its Content-Length, not by counting", "art refused: $size bytes", e.message)
        }
    }

    /** Asked for `identity`, so OkHttp cannot inflate a body after it has been counted. */
    @Test fun `a compressed body is refused, and none is asked for`() {
        server.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setBody(Buffer().write(jpeg)))
        refused()
        assertEquals("identity", server.takeRequest().getHeader("Accept-Encoding"))
    }

    @Test fun `a body that is not an image is refused`() {
        server.enqueue(MockResponse().setBody("<html>not art</html>"))
        refused()
    }

    /** Judged before it is fetched: the request never goes, so nothing is asked of that host. */
    @Test fun `a redirect off the player is refused before it is followed`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://cdn.example/art.jpg"))
        refused()
        assertEquals(1, server.requestCount)
    }

    @Test fun `a redirect on the player is followed`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/getaa?s=1"))
        server.enqueue(MockResponse().setBody(Buffer().write(jpeg)))
        assertEquals(jpeg.size, fetch().size)
        assertEquals(2, server.requestCount)
    }

    @Test fun `redirects are followed only so far`() {
        repeat(ArtHttp.MAX_REDIRECTS + 2) {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/getaa?hop=$it"))
        }
        refused()
        assertEquals(ArtHttp.MAX_REDIRECTS + 1, server.requestCount)
    }

    @Test fun `a player is cleartext on its port, by name or private address`() {
        assertTrue(ArtHttp.isPlayer("http://sonos-aa00bbccddee.local:1400/getaa".toHttpUrl()))
        assertTrue(ArtHttp.isPlayer("http://192.168.1.20:1400/getaa".toHttpUrl()))
        assertTrue(ArtHttp.isPlayer("http://10.0.0.5:1400/getaa".toHttpUrl()))
        assertFalse("public address", ArtHttp.isPlayer("http://93.184.216.34:1400/getaa".toHttpUrl()))
        assertFalse("not a player's port", ArtHttp.isPlayer("http://192.168.1.20:8080/getaa".toHttpUrl()))
        assertFalse("https is a CDN's, not a player's", ArtHttp.isPlayer("https://sonos-aa00bbccddee.local:1400/getaa".toHttpUrl()))
        assertFalse("a name that only looks local", ArtHttp.isPlayer("http://cdn.example:1400/x".toHttpUrl()))
    }

    @Test fun `every image format art comes in is recognised`() {
        fun bytes(vararg b: Int) = ByteArray(12).also { b.forEachIndexed { i, v -> it[i] = v.toByte() } }
        assertTrue(ArtHttp.looksLikeAnImage(bytes(0xFF, 0xD8, 0xFF)))
        assertTrue(ArtHttp.looksLikeAnImage(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertTrue(ArtHttp.looksLikeAnImage("GIF89a".toByteArray() + ByteArray(6)))
        assertTrue(ArtHttp.looksLikeAnImage("RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray()))
        assertFalse(ArtHttp.looksLikeAnImage("RIFF".toByteArray() + ByteArray(4) + "WAVE".toByteArray()))
    }
}
