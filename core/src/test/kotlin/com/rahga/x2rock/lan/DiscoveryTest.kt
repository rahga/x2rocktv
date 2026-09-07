package com.rahga.x2rock.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

/**
 * The response below is a real M-SEARCH reply, captured verbatim off a Sonos Beam on
 * 2026-09-07. The point of testing against it rather than a tidied-up sample is that the
 * two headers this depends on are easy to get subtly wrong: the id is buried inside a
 * compound `USN`, and there are *two* household headers of which only one is complete.
 */
class DiscoveryTest {

    private val realReply = """
        HTTP/1.1 200 OK
        CACHE-CONTROL: max-age = 1800
        EXT:
        LOCATION: http://192.168.86.31:1400/xml/device_description.xml
        SERVER: Linux UPnP/1.0 Sonos/96.1-79270 (ZPS14)
        ST: urn:schemas-upnp-org:device:ZonePlayer:1
        USN: uuid:RINCON_48A6B8332C2E01400::urn:schemas-upnp-org:device:ZonePlayer:1
        X-RINCON-HOUSEHOLD: Sonos_BgzkDDCeWajFguqqdHEXzFKe3x
        X-RINCON-BOOTSEQ: 253
        BOOTID.UPNP.ORG: 253
        X-RINCON-VARIANT: 2
        HOUSEHOLD.SMARTSPEAKER.AUDIO: Sonos_BgzkDDCeWajFguqqdHEXzFKe3x.Zv1xanSF--vUn91aMpBs
        LOCATION.SMARTSPEAKER.AUDIO: lc_bd39b75a013d4656a07c4bcbb5ca6506
        SECURELOCATION.UPNP.ORG: https://192.168.86.31:1443/xml/device_description.xml
    """.trimIndent().replace("\n", "\r\n")

    @Test
    fun `the player id comes out of the compound USN`() {
        assertEquals("RINCON_48A6B8332C2E01400", Discovery.parse(realReply, null)!!.id)
    }

    @Test
    fun `the address comes from LOCATION, not the packet source`() {
        val elsewhere = InetAddress.getByName("10.0.0.1")
        assertEquals(
            InetAddress.getByName("192.168.86.31"),
            Discovery.parse(realReply, elsewhere)!!.address,
        )
    }

    /**
     * `X-RINCON-HOUSEHOLD` is a truncated form and the WebSocket will not accept it. Only
     * `HOUSEHOLD.SMARTSPEAKER.AUDIO` carries the full id.
     */
    @Test
    fun `the household id is the full smartspeaker one`() {
        assertEquals(
            "Sonos_BgzkDDCeWajFguqqdHEXzFKe3x.Zv1xanSF--vUn91aMpBs",
            Discovery.parse(realReply, null)!!.householdId,
        )
    }

    /** The whole point of carrying the id: the first socket can be opened by name. */
    @Test
    fun `a discovered player yields its certificate hostname`() {
        assertEquals("sonos-48A6B8332C2E.local", Discovery.parse(realReply, null)!!.hostname)
    }

    @Test
    fun `the packet source is used when LOCATION is absent`() {
        val noLocation = realReply.lineSequence()
            .filterNot { it.startsWith("LOCATION:") }
            .joinToString("\r\n")
        assertEquals(
            InetAddress.getByName("192.168.86.31"),
            Discovery.parse(noLocation, InetAddress.getByName("192.168.86.31"))!!.address,
        )
    }

    @Test
    fun `something that is not a Sonos player is ignored`() {
        val printer = """
            HTTP/1.1 200 OK
            LOCATION: http://192.168.86.99:80/desc.xml
            USN: uuid:12345678-1234::urn:schemas-upnp-org:device:Printer:1
        """.trimIndent().replace("\n", "\r\n")
        assertNull(Discovery.parse(printer, null))
        assertNull(Discovery.parse("", null))
        assertNull(Discovery.parse("HTTP/1.1 200 OK", null))
    }

    /** A player with no household header is still usable; the id can be asked for later. */
    @Test
    fun `a missing household header is not fatal`() {
        val noHousehold = realReply.lineSequence()
            .filterNot { it.startsWith("HOUSEHOLD.SMARTSPEAKER.AUDIO:") }
            .joinToString("\r\n")
        val player = Discovery.parse(noHousehold, null)!!
        assertEquals("RINCON_48A6B8332C2E01400", player.id)
        assertNull(player.householdId)
    }
}
