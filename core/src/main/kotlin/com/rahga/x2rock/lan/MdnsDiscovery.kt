package com.rahga.x2rock.lan

/**
 * Finding a player by mDNS, `_sonos._tcp`, when SSDP finds nothing.
 *
 * Not hypothetical: on an office LAN an M-SEARCH drew zero replies while the speaker answered
 * mDNS at once, so a first run there found nothing and never connected though the speaker was
 * perfectly usable. The TXT record carries the player id, its address and the long household
 * id — a complete [Discovery.DiscoveredPlayer], with no scanning. See [Discovery.fromSonosTxt].
 *
 * `:core` cannot browse mDNS on Android itself — that is `NsdManager` — so the platform
 * supplies one of these, as it does [MulticastGate].
 */
fun interface MdnsDiscovery {

    /**
     * Every player found within [timeoutMillis], not the first: a network can hold two
     * households, and only the whole window shows that. Empty when none answered.
     */
    suspend fun find(timeoutMillis: Long): List<Discovery.DiscoveredPlayer>

    /** For desktops and tests: nothing to browse with. */
    object None : MdnsDiscovery {
        override suspend fun find(timeoutMillis: Long): List<Discovery.DiscoveredPlayer> = emptyList()
    }
}
