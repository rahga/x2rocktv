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

    /**
     * A player, before anything has been connected to.
     *
     * This carries everything needed to open a properly verified socket: [id] gives the
     * certificate hostname via [PlayerNames], [address] is where to point it, and
     * [householdId] saves an extra round trip. Discovery answering with all three is what
     * lets the first connection be made by name rather than by address — there is no
     * moment where hostname verification has to be relaxed.
     */
    data class DiscoveredPlayer(
        val id: String,
        val address: InetAddress,
        val householdId: String?,
    ) {
        /** Null only for a player id shaped unlike any this was verified against. */
        val hostname: String? get() = PlayerNames.localHostname(id)
    }

    private fun mSearch(mx: Int) =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: $mx\r\n" +
            "ST: $ZONE_PLAYER\r\n\r\n"

    /**
     * Players that answered, in reply order, one entry per player id.
     *
     * Returns more hosts than there are rooms: surrounds and subs answer too, and they are
     * `Invisible` in group topology. Any of them is a usable entry point.
     */
    suspend fun findPlayers(timeoutMillis: Int = 3_000): List<DiscoveredPlayer> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, DiscoveredPlayer>()
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
                parse(String(packet.data, 0, packet.length), packet.address)
                    ?.let { found.putIfAbsent(it.id, it) }
            }
        }
        found.values.toList()
    }

    /**
     * Reads one M-SEARCH response.
     *
     * Three headers matter, and a Sonos player sends all three:
     * ```
     * USN: uuid:RINCON_48A6B8332C2E01400::urn:schemas-upnp-org:device:ZonePlayer:1
     * LOCATION: http://192.168.86.31:1400/xml/device_description.xml
     * HOUSEHOLD.SMARTSPEAKER.AUDIO: Sonos_Bgzk….Zv1x…
     * ```
     * Note it is `HOUSEHOLD.SMARTSPEAKER.AUDIO` that carries the household id the
     * WebSocket wants — `X-RINCON-HOUSEHOLD` is a truncated form and will not do.
     *
     * `LOCATION` is preferred over the packet's source address because it is what the
     * player says about itself, but the source address is a fine fallback.
     */
    internal fun parse(response: String, source: InetAddress?): DiscoveredPlayer? {
        val headers = response.lineSequence()
            .mapNotNull { line ->
                val colon = line.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
                line.substring(0, colon).trim().uppercase() to line.substring(colon + 1).trim()
            }
            .toMap()

        val id = headers["USN"]
            ?.substringAfter("uuid:", "")
            ?.substringBefore("::")
            ?.takeIf { it.startsWith("RINCON_") }
            ?: return null

        val address = headers["LOCATION"]
            ?.let { runCatching { URI(it).host }.getOrNull() }
            ?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
            ?: source
            ?: return null

        return DiscoveredPlayer(id, address, headers["HOUSEHOLD.SMARTSPEAKER.AUDIO"])
    }
}
