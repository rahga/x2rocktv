package com.rahga.x2rock.channel

import com.rahga.x2rock.lan.PlayerAddressBook
import com.rahga.x2rock.lan.PlayerNames
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A tile's poster as the launcher can fetch it, or null to leave the tile without one.
 *
 * The launcher fetches posters itself, with its own resolver, and a player's art is at
 * `http://sonos-<MAC>.local:1400/getaa` — a name Android cannot resolve, so every such poster
 * was a blank. The player's address is already in the address book, so the name is replaced
 * with it. A service's https art is fetchable as it is. Anything else, or a player not in the
 * book, is dropped rather than published as a poster that cannot load.
 */
object PosterArt {
    fun forLauncher(url: String?, addresses: PlayerAddressBook): String? {
        val parsed = url?.toHttpUrlOrNull() ?: return null
        if (parsed.scheme == "https") return url
        if (!PlayerNames.isLocalName(parsed.host)) return null
        val address = runCatching { addresses.lookup(parsed.host).first() }.getOrNull() ?: return null
        return parsed.newBuilder().host(address.hostAddress ?: return null).build().toString()
    }
}
