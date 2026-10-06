package com.rahga.x2rock.smapi

import com.rahga.x2rock.lan.Xml

/**
 * Turning a service search/browse [Item] into what `AddURIToQueue` takes — the URI the player
 * fetches or expands, and the DIDL that names whose item it is. A direct port of x2rock's
 * `bookmarks.rs`; every form here was read off a player or verified against one there.
 *
 * **The URI is synthesised, not fetched.** `getMediaURI` resolves a *stream* to play now, but a
 * queue row is built from the item's own id, scheme and the account's cdudn — which is why an
 * append needs no extra round trip and no stream the service might not offer.
 *
 * A *track* is fetched (`x-sonosapi-hls-static:<id>?…`); a *container that holds tracks* (an
 * album, a playlist) is handed to the player as `x-rincon-cpcontainer:…` and expanded into its
 * rows. A *container of containers* (an artist, a genre) holds no tracks, so a queue can do
 * nothing with it — [canEnqueue] says which is which, and the caller offers the action only
 * where it would work.
 */
object ServiceContent {

    /** Spotify's own ids need its native scheme; its generic-scheme rows add but never play. */
    private const val SPOTIFY_SERVICE_ID = "12"

    /** Whether [item] can go in a queue at all — a track, or a container that holds tracks. */
    fun canEnqueue(item: Item): Boolean =
        !item.container || containerHoldsTracks(item.itemType)

    /**
     * The cdudn a queued item must carry for [serviceType] and [selector] — `SA_RINCON<type>_X_#
     * Svc<type>-<selector>-Token`, `-0-` where the selector is absent or the literal `0` that
     * names the service rather than an account. `null` when the service has no type (not in the
     * player's `AvailableServiceTypeList`), which leaves no cdudn to build.
     */
    fun cdudn(serviceType: Long?, selector: String): String? {
        val t = serviceType ?: return null
        val key = selector.ifEmpty { "0" }
        return "SA_RINCON${t}_X_#Svc${t}-${key}-Token"
    }

    /** The URI `AddURIToQueue` takes for [item]: a container is expanded, a track is fetched. */
    fun enqueueUri(item: Item, serviceId: String, serial: String?): String {
        val sn = serial?.takeIf { it.isNotEmpty() }?.let { "&sn=$it" }.orEmpty()
        val id = encodeObjectId(item.id)
        return if (item.container) {
            "x-rincon-cpcontainer:1004206c$id?sid=$serviceId&flags=8300$sn"
        } else {
            "${nativeScheme(serviceId)}:$id?sid=$serviceId&flags=65544$sn"
        }
    }

    /** The DIDL-Lite that travels with [enqueueUri], carrying the [cdudn] that names the account. */
    fun enqueueDidl(item: Item, cdudn: String): String {
        val id = encodeObjectId(item.id)
        return if (item.container) {
            didl(
                itemId = "1004206c$id", parent = "0",
                inner = "<dc:title>${Xml.escape(item.title)}</dc:title>" +
                    "<upnp:class>${containerClass(item.itemType)}</upnp:class>",
                cdudn = cdudn,
            )
        } else {
            didl(
                itemId = "00032020$id", parent = "-1",
                inner = "<dc:title>${Xml.escape(item.title)}</dc:title>" +
                    "<upnp:class>object.item.audioItem.musicTrack</upnp:class>",
                cdudn = cdudn,
            )
        }
    }

    /**
     * Whether a container of this kind holds *tracks* (so a queue can take it). Album and playlist
     * are lists of tracks; an artist is a list of sub-containers, which a player refuses with UPnP
     * 804. The conservative default is false — an unknown kind is not offered.
     */
    fun containerHoldsTracks(itemType: String): Boolean =
        itemType.lowercase() in setOf("album", "playlist", "tracklist", "artisttracklist", "audiobook")

    private fun containerClass(itemType: String): String = when (itemType.lowercase()) {
        "album" -> "object.container.album.musicAlbum"
        "playlist", "audiobook" -> "object.container.playlistContainer"
        else -> "object.container"
    }

    /** The scheme a service's object ids are wrapped in; Spotify's own, HLS-static for the rest. */
    private fun nativeScheme(serviceId: String): String =
        if (serviceId == SPOTIFY_SERVICE_ID) "x-sonos-spotify" else "x-sonosapi-hls-static"

    private fun didl(itemId: String, parent: String, inner: String, cdudn: String): String =
        """<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" """ +
            """xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" """ +
            """xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" """ +
            """xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">""" +
            """<item id="${Xml.escape(itemId)}" parentID="$parent" restricted="true">""" +
            inner +
            """<desc id="cdudn" nameSpace="urn:schemas-rinconnetworks-com:metadata-1-0/">""" +
            "${Xml.escape(cdudn)}</desc></item></DIDL-Lite>"

    /**
     * Percent-encode an object id for a playback URI (RFC 3986 unreserved, lowercase hex), as the
     * player does. A colon is what UPnP 800 was: Mixcloud's `cloudcast:…` ids were refused until
     * it was escaped, while YouTube Music's alphanumeric ids made its absence invisible.
     */
    internal fun encodeObjectId(id: String): String = buildString {
        for (b in id.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            if (c.toChar() in 'A'..'Z' || c.toChar() in 'a'..'z' || c.toChar() in '0'..'9' ||
                c.toChar() == '-' || c.toChar() == '_' || c.toChar() == '.' || c.toChar() == '~'
            ) {
                append(c.toChar())
            } else {
                append('%').append("%02x".format(c))
            }
        }
    }
}
