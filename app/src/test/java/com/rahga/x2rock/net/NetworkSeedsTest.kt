package com.rahga.x2rock.net

import com.rahga.x2rock.lan.Discovery
import com.rahga.x2rock.viewmodel.FakePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

/** One remembered player per network, and what counts as the same network. */
class NetworkSeedsTest {

    // ---------------------------------------------------------------- the key

    @Test fun `Wi-Fi and Ethernet into one router are one network`() {
        assertEquals(
            NetworkIdentity.keyOf(listOf("192.168.1.1", "fe80::1a2b:3cff:fe4d:5e6f%wlan0"), "lan"),
            NetworkIdentity.keyOf(listOf("fe80::1a2b:3cff:fe4d:5e6f%eth0", "192.168.1.1"), "lan"),
        )
    }

    @Test fun `a different router or domain is a different network`() {
        val home = NetworkIdentity.keyOf(listOf("192.168.1.1", "fe80::1a2b:3cff:fe4d:5e6f"), null)
        assertNotEquals(home, NetworkIdentity.keyOf(listOf("192.168.1.1", "fe80::99"), null))
        assertNotEquals(home, NetworkIdentity.keyOf(listOf("192.168.1.1", "fe80::1a2b:3cff:fe4d:5e6f"), "corp.example"))
    }

    @Test fun `a network with no default route has no key`() {
        assertNull(NetworkIdentity.keyOf(emptyList(), "lan"))
    }

    // ---------------------------------------------------------------- the store

    private var network: String? = "home"
    private val prefs = FakePreferences()
    private val store = PrefsSeedStore(prefs) { network }
    private fun player(id: String, ip: String) = Discovery.DiscoveredPlayer(id, InetAddress.getByName(ip), "Sonos_A.B")

    /** The point of 4.3: carried to the office and back, each network still knows its own. */
    @Test fun `each network keeps its own player`() {
        store.save(player("RINCON_HOME01400", "192.168.1.20"))
        network = "office"
        assertNull("the office was handed home's speaker", store.load())
        store.save(player("RINCON_OFFICE01400", "10.0.0.5"))
        network = "home"
        assertEquals("RINCON_HOME01400", store.load()?.id)
        network = "office"
        assertEquals("10.0.0.5", store.load()?.address?.hostAddress)
    }

    /** Forgetting a household gone from one network says nothing about another's. */
    @Test fun `clearing forgets only this network`() {
        store.save(player("RINCON_HOME01400", "192.168.1.20"))
        network = "office"
        store.save(player("RINCON_OFFICE01400", "10.0.0.5"))
        store.clear()
        assertNull(store.load())
        network = "home"
        assertEquals("RINCON_HOME01400", store.load()?.id)
    }

    @Test fun `a network with no key still remembers`() {
        network = null
        store.save(player("RINCON_HOME01400", "192.168.1.20"))
        assertEquals("RINCON_HOME01400", store.load()?.id)
    }
}
