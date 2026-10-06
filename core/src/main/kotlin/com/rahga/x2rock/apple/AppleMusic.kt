package com.rahga.x2rock.apple

import com.rahga.x2rock.lan.Xml
import com.rahga.x2rock.model.HistoryItem
import com.rahga.x2rock.model.MusicObjectId
import com.rahga.x2rock.model.Track

/**
 * A song or an album from Apple Music's catalogue, as the iTunes Search API names it.
 *
 * Its `trackId` or `collectionId` is the very number behind the `song:` and `album:` ids the
 * player uses, so a search result plays with nothing looked up in between: `song:1485581309`,
 * never played in the home household, played at once (2026-10-06, Dining Room). Not reached:
 * the listener's own library, Apple's editorial playlists, and artists — none is in the public
 * API, and the Apple Music API that has them refuses the household's credential (x2rock).
 */
data class AppleMusicItem(
    val kind: Kind,
    val catalogId: Long,
    val title: String,
    val artist: String?,
    val artworkUrl: String?,
) {
    /** [prefix] is the object id's, [loadType] what `playback:1 loadContent` calls it. */
    enum class Kind(val prefix: String, val loadType: String) { SONG("song", "track"), ALBUM("album", "album") }

    val objectId: String get() = "${kind.prefix}:$catalogId"

    /** The item as `loadContent` takes it — the shape Recently Played is replayed from. */
    fun asLoadable(accountId: String) =
        HistoryItem(title, kind.loadType, MusicObjectId(objectId, AppleMusic.SERVICE_ID, accountId))
}

/**
 * Apple Music, as far as this app reaches it: searched through iTunes, played by the household's
 * own Apple Music account.
 *
 * Playing goes through `playback:1 loadContent`, which replaces the queue. Adding to the queue
 * cannot — `loadContent` ignores every append it was offered (x2rock) — so that is UPnP's
 * `AddURIToQueue`, with the URI and metadata the player writes for itself. Both read off the home
 * household and appended by hand to Dining Room before this was written (2026-10-06): the song
 * form `x-sonos-http:song%3a<id>.mp4?sid=204&flags=8232&sn=28` is exactly what the room was
 * playing, and the album's container form expanded into its four tracks.
 */
object AppleMusic {
    const val SERVICE_ID = "204"

    /**
     * Whose item it is, to the player. Apple Music's service type is 52231 (204 × 256 + 7), and
     * the `-0-` account selector resolves to the household's account; without a cdudn the player
     * takes the item and then has nothing to show, for the whole queue (x2rock).
     */
    private const val CDUDN = "SA_RINCON52231_X_#Svc52231-0-Token"

    /**
     * The household's Apple Music account (`sn_28`), from anything it has played: Recently Played
     * first, then whatever a room is playing now. Favourites name no account. None means the
     * household does not use Apple Music, or has played nothing from it that is still known —
     * either way there is nothing for a search result to play with.
     */
    fun accountIn(history: List<HistoryItem>, playing: Collection<Track?>): String? =
        (history.map { it.id } + playing.mapNotNull { it?.id })
            .firstOrNull { it.serviceId == SERVICE_ID && !it.accountId.isNullOrEmpty() }
            ?.accountId

    /** The URI `AddURIToQueue` takes: a song is fetched, an album expanded by the player. */
    fun queueUri(item: AppleMusicItem, accountId: String): String {
        val id = encode(item.objectId)
        val sn = accountId.removePrefix("sn_")
        return when (item.kind) {
            AppleMusicItem.Kind.SONG -> "x-sonos-http:$id.mp4?sid=$SERVICE_ID&flags=8232&sn=$sn"
            AppleMusicItem.Kind.ALBUM -> "x-rincon-cpcontainer:1004206c$id?sid=$SERVICE_ID&flags=8300&sn=$sn"
        }
    }

    /** The DIDL-Lite that travels with [queueUri]: x2rock's, which the player accepted for both. */
    fun queueMetadata(item: AppleMusicItem): String {
        val id = encode(item.objectId)
        val (itemId, parent, upnpClass) = when (item.kind) {
            AppleMusicItem.Kind.SONG -> Triple("00032020$id", "-1", "object.item.audioItem.musicTrack")
            AppleMusicItem.Kind.ALBUM -> Triple("1004206c$id", "0", "object.container.album.musicAlbum")
        }
        val creator = item.artist?.let { "<dc:creator>${Xml.escape(it)}</dc:creator>" }.orEmpty()
        return """<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" """ +
            """xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" """ +
            """xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" """ +
            """xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">""" +
            """<item id="${Xml.escape(itemId)}" parentID="$parent" restricted="true">""" +
            "<dc:title>${Xml.escape(item.title)}</dc:title>$creator<upnp:class>$upnpClass</upnp:class>" +
            """<desc id="cdudn" nameSpace="urn:schemas-rinconnetworks-com:metadata-1-0/">${Xml.escape(CDUDN)}</desc>""" +
            "</item></DIDL-Lite>"
    }

    /** `song:123` as the player writes it into a URI: the colon percent-encoded, lowercase. */
    private fun encode(objectId: String) = objectId.replace(":", "%3a")
}
