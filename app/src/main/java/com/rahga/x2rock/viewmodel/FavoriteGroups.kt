package com.rahga.x2rock.viewmodel

import com.rahga.x2rock.model.Favorite
import com.rahga.x2rock.smapi.ServiceContent

/**
 * The household's favourites in the groups the Sonos app lists them under (seen 2026-10-08):
 * playlists, albums, songs, stations, and anything else after. One flat list mixed a song, a
 * station and an album with nothing to tell them apart. Each keeps the household's own order.
 */
fun favoriteGroups(items: List<Favorite>): List<Pair<String, List<Favorite>>> {
    val byGroup = items.groupBy { groupOf(it.resource?.type) }
    return GROUPS.mapNotNull { title -> byGroup[title]?.let { title to it } }
}

private val GROUPS = listOf("Favorite playlists", "Favorite albums", "Favorite songs", "Favorite stations", "Favorites")

private fun groupOf(type: String?): String = when (type?.uppercase()) {
    "PLAYLIST" -> "Favorite playlists"
    "ALBUM" -> "Favorite albums"
    "TRACK" -> "Favorite songs"
    "STREAM", "PROGRAM" -> "Favorite stations"
    else -> "Favorites"
}

/**
 * A favourite's second line: what it is and whose — "Album · Saavn". The player's own
 * `description` was the service for some and the artist for others, so the same list read
 * "By Travis Scott" on one row and "Saavn" on the next.
 */
fun favoriteLine(favorite: Favorite): String? =
    listOfNotNull(favorite.resource?.type?.lowercase()?.let(::kindLabel), favorite.service?.name ?: favorite.description)
        .joinToString(" · ").ifEmpty { null }

/**
 * The service key and container id an album or playlist favourite opens on, when the household
 * can browse that service here; `null` for anything else, which plays on a press as before.
 */
fun favoritePage(favorite: Favorite, browsable: Set<String>): Pair<String, String>? {
    val type = favorite.resource?.type?.uppercase()
    if (type != "ALBUM" && type != "PLAYLIST") return null
    val id = favorite.resource?.id ?: return null
    val serviceId = id.serviceId ?: return null
    val key = "$serviceId:${id.accountId ?: "anon"}"
    return (key to ServiceContent.serviceId(id.objectId)).takeIf { key in browsable && id.objectId.isNotEmpty() }
}
