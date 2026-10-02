package com.rahga.x2rock.net

import android.content.Context
import android.net.ConnectivityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which network the device is on now, as a key to remember a player under.
 *
 * x2rock keys by the default gateway's MAC. Android has not let an app read the ARP table
 * since 10, so this keys by what `LinkProperties` does say: the default routes' gateways and
 * the DHCP search domain. That is weaker in one way only — two networks that both hand out
 * `192.168.1.1` and no domain share a key — and the cost of that is today's behaviour, a
 * remembered player that does not answer and a discovery after it, never a wrong connect:
 * a player is reached by its certificate's name, which another network's speaker cannot hold.
 * Where the router speaks IPv6 its gateway is a link-local address, commonly derived from its
 * MAC, which is close to x2rock's fingerprint.
 *
 * Wi-Fi and Ethernet into the same router share a key, as they should: same speakers.
 */
@Singleton
class NetworkIdentity @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Null when there is no network, or it has no default route to tell it by. */
    fun current(): String? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = manager.activeNetwork ?: return null
        val link = manager.getLinkProperties(network) ?: return null
        val gateways = link.routes
            .filter { it.isDefaultRoute }
            .mapNotNull { route -> route.gateway?.takeUnless { it.isAnyLocalAddress }?.hostAddress }
        return keyOf(gateways, link.domains)
    }

    companion object {
        /**
         * The key for a network with these default gateways and search domain. An IPv6
         * gateway's `%scope` is dropped: it names this device's interface, which differs
         * between Wi-Fi and Ethernet into the same router.
         */
        fun keyOf(gateways: List<String>, domains: String?): String? {
            val routers = gateways.map { it.substringBefore('%').lowercase() }.distinct().sorted()
            if (routers.isEmpty()) return null
            val identity = (routers + domains.orEmpty().trim().lowercase()).joinToString("|")
            return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
                .take(8).joinToString("") { "%02x".format(it) }
        }
    }
}
