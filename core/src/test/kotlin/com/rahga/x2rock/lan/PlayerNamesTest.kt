package com.rahga.x2rock.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * The five ids below are the real players of the household this was developed against,
 * and each expected name was confirmed against that speaker's certificate SAN.
 */
class PlayerNamesTest {

    @Test
    fun `every player id in the reference household derives its certificate name`() {
        val cases = mapOf(
            "RINCON_48A6B830668701400" to "sonos-48A6B8306687.local",   // Living Room
            "RINCON_48A6B818D13801400" to "sonos-48A6B818D138.local",   // Kitchen
            "RINCON_48A6B8332C2E01400" to "sonos-48A6B8332C2E.local",   // Guest TV
            "RINCON_542A1B83318001400" to "sonos-542A1B833180.local",   // Bedroom
            "RINCON_347E5C31AF7401400" to "sonos-347E5C31AF74.local",   // Dining Room
        )
        cases.forEach { (id, expected) -> assertEquals(expected, PlayerNames.localHostname(id)) }
    }

    /** Certificate names are upper-case hex; matching is case-insensitive but output is not. */
    @Test
    fun `lower-case ids still produce the upper-case name the certificate carries`() {
        assertEquals("sonos-48A6B8306687.local", PlayerNames.localHostname("RINCON_48a6b830668701400"))
    }

    /**
     * Null rather than a guess. A caller that gets null must fall back to the address and
     * accept relaxed hostname verification for that player — inventing a name the
     * certificate does not contain would fail the handshake in a far more confusing way.
     */
    @Test
    fun `an unfamiliar id shape yields null`() {
        assertNull(PlayerNames.localHostname(""))
        assertNull(PlayerNames.localHostname("RINCON_48A6B8306687"))          // no 01400 suffix
        assertNull(PlayerNames.localHostname("RINCON_48A6B830668702400"))     // different suffix
        assertNull(PlayerNames.localHostname("RINCON_ZZZZZZZZZZZZ01400"))     // not hex
        assertNull(PlayerNames.localHostname("192.168.86.25"))
    }

    @Test
    fun `local names are recognised regardless of case`() {
        assert(PlayerNames.isLocalName("sonos-48A6B8306687.local"))
        assert(PlayerNames.isLocalName("Sonos-48A6B8306687.LOCAL"))
        assert(!PlayerNames.isLocalName("api.ws.sonos.com"))
    }

    @Test
    fun `the address book maps a registered name and is case-insensitive`() {
        val book = PlayerAddressBook()
        book.register("sonos-48A6B8306687.local", "192.168.86.25")
        assertEquals(
            listOf(InetAddress.getByName("192.168.86.25")),
            book.lookup("SONOS-48a6b8306687.local"),
        )
    }

    /**
     * Android's resolver has no mDNS, so an unregistered .local name would otherwise fail
     * deep inside the connect with nothing pointing at the real cause.
     */
    @Test
    fun `an unregistered local name fails with a message naming the cause`() {
        val book = PlayerAddressBook()
        val e = assertThrows(UnknownHostException::class.java) {
            book.lookup("sonos-48A6B8306687.local")
        }
        assert(e.message!!.contains("address book"))
    }

    @Test
    fun `forgetting a player stops it resolving`() {
        val book = PlayerAddressBook()
        book.register("sonos-48A6B8306687.local", "192.168.86.25")
        book.forget("sonos-48A6B8306687.local")
        assertThrows(UnknownHostException::class.java) { book.lookup("sonos-48A6B8306687.local") }
    }
}
