package com.rahga.x2rock.lan

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress

/**
 * The one guard on a UPnP reply, kept by hand because Android's parser cannot be asked for it.
 * The JVM's can, which is why a device parsing nothing at all went unseen for a month.
 */
class UpnpParseTest {

    private lateinit var server: MockWebServer
    private lateinit var upnp: Upnp
    private val host = "sonos-aa00bbccddee.local"

    @Before fun setUp() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        val book = PlayerAddressBook().apply { register(host, InetAddress.getByName("127.0.0.1")) }
        upnp = Upnp(LanHttp.client(book), port = server.port)
    }

    @After fun tearDown() = server.shutdown()

    private val reply = "<s:Envelope><s:Body><u:GetMediaInfoResponse><CurrentURI>x-rincon-queue:X#0</CurrentURI></u:GetMediaInfoResponse></s:Body></s:Envelope>"

    @Test fun `a reply parses`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(reply))
        assertTrue(upnp.mediaInfo(host).playingFromQueue)
    }

    @Test fun `a reply that declares a DOCTYPE is refused before it is parsed`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("<?xml version=\"1.0\"?><!DOCTYPE s [<!ENTITY x SYSTEM \"file:///etc/hostname\">]>$reply"))
        try {
            upnp.mediaInfo(host)
            fail("parsed a reply with a DOCTYPE")
        } catch (e: IOException) {
            assertEquals("refusing a UPnP reply that declares a DOCTYPE", e.message)
        }
    }
}
