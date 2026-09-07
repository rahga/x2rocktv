package com.rahga.x2rock.lan

/**
 * Turning a player id into the hostname its certificate is actually issued for.
 *
 * A player presents a leaf certificate whose SAN carries `DNS:sonos-<MAC>.local` and
 * **no IP address**, so a connection made to `wss://<ip>:1443` can never pass hostname
 * verification and would need that check disabled. It doesn't have to be: the MAC is
 * embedded in the RINCON player id that `groups:1 getGroups` already returns, so the
 * name can be derived with no mDNS lookup at all — which matters, because Android's
 * platform resolver has no mDNS and `InetAddress.getByName(".local")` throws.
 *
 *     RINCON_48A6B818D138 01400   ->   sonos-48A6B818D138.local
 *            ^^^^^^^^^^^^
 *
 * Connect to that name with a [PlayerAddressBook] supplying the address and OkHttp's
 * default hostname verifier passes honestly. Verified against every player in a
 * five-speaker household; see `docs/lan-transport.md`.
 */
object PlayerNames {

    private val RINCON = Regex("^RINCON_([0-9A-Fa-f]{12})01400$")

    /**
     * Null when the id doesn't match the shape this was verified against, rather than a
     * guess. A caller that gets null should fall back to the address and accept that it
     * must relax hostname verification for that player — better than inventing a name
     * the certificate will not contain.
     */
    fun localHostname(playerId: String): String? =
        RINCON.find(playerId)?.let { "sonos-${it.groupValues[1].uppercase()}.local" }

    /** True for hostnames this project is willing to point at a LAN address. */
    fun isLocalName(hostname: String): Boolean =
        hostname.endsWith(".local", ignoreCase = true)
}
