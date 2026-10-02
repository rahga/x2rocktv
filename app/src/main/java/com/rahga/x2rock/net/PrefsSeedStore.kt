package com.rahga.x2rock.net

import com.rahga.x2rock.lan.Discovery
import com.rahga.x2rock.lan.SeedStore
import com.rahga.x2rock.store.Preferences
import java.net.InetAddress

/**
 * Remembers the last reachable player for each network the device has been on.
 *
 * This is what makes a warm start immediate instead of waiting out a multicast sweep. Keyed
 * by [NetworkIdentity], so a device carried between home and an office warm-starts in both
 * rather than probing the other one's speaker for 3s and then forgetting it. It stores nothing
 * sensitive — a player id, a LAN address and a household id, all of which any device on the
 * network can ask for.
 *
 * A network with no key (no default route) has a slot of its own, so it still remembers.
 * Built in `AppModule`, which hands it [NetworkIdentity.current] as [network].
 */
class PrefsSeedStore(
    private val prefs: Preferences,
    private val network: () -> String?,
) : SeedStore {

    override val keyedByNetwork: Boolean get() = true

    override fun load(): Discovery.DiscoveredPlayer? {
        val slot = slot()
        val id = prefs.getString("$slot.$KEY_ID") ?: return null
        val host = prefs.getString("$slot.$KEY_HOST") ?: return null
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
        return Discovery.DiscoveredPlayer(id, address, prefs.getString("$slot.$KEY_HOUSEHOLD"))
    }

    override fun save(player: Discovery.DiscoveredPlayer) {
        val slot = slot()
        prefs.putString("$slot.$KEY_ID", player.id)
        prefs.putString("$slot.$KEY_HOST", player.address.hostAddress)
        prefs.putString("$slot.$KEY_HOUSEHOLD", player.householdId)
    }

    /** This network's memory only: a household gone here says nothing about another network's. */
    override fun clear() {
        val slot = slot()
        listOf(KEY_ID, KEY_HOST, KEY_HOUSEHOLD).forEach { prefs.putString("$slot.$it", null) }
    }

    private fun slot() = "seed." + (network() ?: "unkeyed")

    private companion object {
        const val KEY_ID = "playerId"
        const val KEY_HOST = "address"
        const val KEY_HOUSEHOLD = "householdId"
    }
}
