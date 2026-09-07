package com.rahga.x2rock.lan

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI

/**
 * Finding a player on a cold start, when no address is known yet.
 *
 * SSDP works on Android TV — one M-SEARCH returned 11 replies in under 700ms on an
 * Ethernet-connected Shield, with no `MulticastLock` involved. The sibling Rust project
 * scans TCP port 1443 instead and its own notes say **not** to inherit that: the scan is a
 * workaround for a default-deny host firewall on a laptop, not a fact about Sonos.
 *
 * Reaching any *one* player is enough. `groups:1 getGroups` then reports every other
 * player's address, so this should run once and never again while an address still works.
 *
 * Two caveats carried over from that research, neither of which bites on a wired TV box:
 * on Wi-Fi a `WifiManager.MulticastLock` must be held across the query (an Android
 * concern, so the caller's job, not this module's), and if multicast is ever blocked
 * outright the documented fallback is an outbound connect-scan of port 1443 — not
 * implemented here, because nothing has needed it.
 */
object Discovery {

    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val ZONE_PLAYER = "urn:schemas-upnp-org:device:ZonePlayer:1"

    private fun mSearch(mx: Int) =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: $mx\r\n" +
            "ST: $ZONE_PLAYER\r\n\r\n"

    /**
     * Addresses that answered, in the order they replied, deduplicated.
     *
     * Returns more hosts than there are rooms: surrounds and subs answer too, and they are
     * `Invisible` in group topology. Any of them is a usable entry point.
     */
    suspend fun findPlayers(timeoutMillis: Int = 3_000): List<InetAddress> = withContext(Dispatchers.IO) {
        val found = LinkedHashSet<InetAddress>()
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMillis
            val payload = mSearch(mx = (timeoutMillis / 1000).coerceAtLeast(1)).toByteArray()
            socket.send(
                DatagramPacket(payload, payload.size, InetAddress.getByName(SSDP_ADDRESS), SSDP_PORT)
            )

            val deadline = System.currentTimeMillis() + timeoutMillis
            while (System.currentTimeMillis() < deadline) {
                val buffer = ByteArray(2048)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    break
                }
                // The LOCATION header is the authority on where the player is; the packet's
                // source address is the same host but this survives any relaying.
                val location = String(packet.data, 0, packet.length)
                    .lineSequence()
                    .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                    ?.substringAfter(':')
                    ?.trim()
                val host = location?.let { runCatching { URI(it).host }.getOrNull() }
                found += if (host != null) InetAddress.getByName(host) else packet.address
            }
        }
        found.toList()
    }
}
