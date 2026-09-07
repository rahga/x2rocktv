package com.rahga.x2rock.net

import android.content.Context
import com.rahga.x2rock.lan.Discovery
import com.rahga.x2rock.lan.SeedStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers the last reachable player in shared preferences.
 *
 * This is what makes a warm start immediate instead of waiting out a multicast sweep. It
 * stores nothing sensitive — a player id, a LAN address and a household id, all of which
 * any device on the network can ask for.
 */
@Singleton
class PrefsSeedStore @Inject constructor(
    @ApplicationContext context: Context,
) : SeedStore {

    private val prefs = context.getSharedPreferences("x2rock.seed", Context.MODE_PRIVATE)

    override fun load(): Discovery.DiscoveredPlayer? {
        val id = prefs.getString(KEY_ID, null) ?: return null
        val host = prefs.getString(KEY_HOST, null) ?: return null
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
        return Discovery.DiscoveredPlayer(id, address, prefs.getString(KEY_HOUSEHOLD, null))
    }

    override fun save(player: Discovery.DiscoveredPlayer) {
        prefs.edit()
            .putString(KEY_ID, player.id)
            .putString(KEY_HOST, player.address.hostAddress)
            .putString(KEY_HOUSEHOLD, player.householdId)
            .apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_ID = "playerId"
        const val KEY_HOST = "address"
        const val KEY_HOUSEHOLD = "householdId"
    }
}
