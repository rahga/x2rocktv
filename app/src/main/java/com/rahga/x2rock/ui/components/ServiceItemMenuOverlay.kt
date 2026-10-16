package com.rahga.x2rock.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import com.rahga.x2rock.smapi.ItemDetails
import com.rahga.x2rock.smapi.LinkedService
import com.rahga.x2rock.smapi.ServiceContent
import com.rahga.x2rock.viewmodel.ServiceItemMenu

/**
 * [ServiceItemMenu]'s menu, drawn: Sonos's own actions, then the service's under its name, as the
 * Sonos app lays them out. [onOpen] opens a container the menu is for; [onOpenRelated] its artist
 * or album; [here] is the container showing, which is not offered as a place to go.
 */
@Composable
fun ServiceItemMenuOverlay(
    menu: ServiceItemMenu,
    target: ServiceItemMenu.Target,
    room: String,
    fromQueue: Boolean,
    details: ItemDetails?,
    here: String?,
    onOpen: (() -> Unit)?,
    onOpenRelated: (id: String, title: String, album: Boolean) -> Unit,
    onPlayed: () -> Unit,
    /** List an audiobook's chapters; null where the screen cannot, as Search's results. */
    onChapters: (() -> Unit)? = null,
) {
    val service = (target as? ServiceItemMenu.Target.Service)
    val playlists by menu.playlists.collectAsState()
    val sonosFavorite by menu.sonosFavorite.collectAsState()
    val beta by menu.beta.collectAsState()
    ItemMenu(
        title = target.title,
        subtitle = target.subtitle,
        artUrl = target.artUrl,
        sections = listOf(
            MenuSection(null, playActions(menu, target, room, fromQueue, onOpen, onPlayed, onChapters) + favoriteActions(menu, service.takeIf { beta }, sonosFavorite)),
            MenuSection(
                service?.linked?.service?.name,
                if (service != null && details != null) {
                    serviceActions(menu, service, details, here, onOpenRelated, onPlayed) +
                        // A track, into one of the account's own playlists, where it has any to add to.
                        listOfNotNull(
                            MenuAction("Add to playlist…") { menu.pickPlaylist(service) }
                                .takeIf { playlists.isNotEmpty() && !service.item.container }
                        )
                } else emptyList(),
            ),
        ),
        onDismiss = menu::close,
        note = if (service != null && details == null) "Asking ${service.linked.service.name}…" else null,
    )
}

/**
 * Sonos's own half, as the Sonos app words it. Play next only while the room plays from its queue,
 * as there; an artist, which no queue can hold, is only opened.
 */
private fun playActions(
    menu: ServiceItemMenu, target: ServiceItemMenu.Target, room: String, fromQueue: Boolean,
    onOpen: (() -> Unit)?, onPlayed: () -> Unit, onChapters: (() -> Unit)?,
): List<MenuAction> = buildList {
    val item = (target as? ServiceItemMenu.Target.Service)?.item
    val playable = target !is ServiceItemMenu.Target.Service || target.canPlay
    if (playable) {
        val label = if (item != null && ServiceContent.isResumable(item)) "Resume" else "Play now"
        add(MenuAction(label, room) { menu.playNow(target, onPlayed) })
    }
    if (target.canQueue && fromQueue) add(MenuAction("Play next", room) { menu.queue(target, next = true) })
    if (target.canQueue) add(MenuAction("Add to end of queue", room) { menu.queue(target) })
    if (item != null && ServiceContent.opens(item) && onOpen != null) add(MenuAction("Open", onClick = onOpen))
    // A book resumes on a press; picking a chapter is the hold's.
    if (item != null && ServiceContent.isResumable(item) && onChapters != null) add(MenuAction("Chapters", onClick = onChapters))
}

/**
 * The household's Sonos Favorites, for what a queue could hold — a track, an album, a playlist: Add,
 * or Remove once it is known to be one. Sonos's own, so in Sonos's half, under the queue. Beta until
 * pressed on a TV: the save was proved on the office One SL from the JVM, never from a screen.
 */
private fun favoriteActions(menu: ServiceItemMenu, target: ServiceItemMenu.Target.Service?, favoriteId: String?): List<MenuAction> =
    when {
        target == null || !target.canQueue -> emptyList()
        favoriteId != null -> listOf(MenuAction("Remove from Sonos Favorites") { menu.removeSonosFavorite(target, favoriteId) })
        else -> listOf(MenuAction("Add to Sonos Favorites") { menu.saveSonosFavorite(target) })
    }

/**
 * The service's half: what it says about the item beyond itself. Where it does not say whether the
 * item is already a favourite (Qobuz), both directions are offered, as the Sonos app does there too,
 * rather than guessing.
 */
private fun serviceActions(
    menu: ServiceItemMenu, target: ServiceItemMenu.Target.Service, details: ItemDetails, here: String?,
    onOpenRelated: (id: String, title: String, album: Boolean) -> Unit, onPlayed: () -> Unit,
): List<MenuAction> = buildList {
    val linked: LinkedService = target.linked
    val name = linked.service.name
    details.radio?.let { radio -> add(MenuAction("Start radio", radio.title.ifEmpty { null }) { menu.startRadio(linked, radio, onPlayed) }) }
    details.artistId?.takeIf { it != here }?.let { id ->
        add(MenuAction("Browse artist", details.artist) { onOpenRelated(id, details.artist ?: "Artist", false) })
    }
    details.albumId?.takeIf { it != target.item.id && it != here }?.let { id ->
        add(MenuAction("Open album", details.album) { onOpenRelated(id, details.album ?: "Album", true) })
    }
    if (details.canFavorite) {
        if (details.favorite != true) add(MenuAction("Add to your $name favourites") { menu.setFavorite(linked, target.item, true) })
        if (details.favorite != false) add(MenuAction("Remove from your $name favourites") { menu.setFavorite(linked, target.item, false) })
    }
}

/**
 * The account's playlists, after "Add to playlist…": one press puts the track at the end of one.
 * Its own overlay rather than a page of the item menu, which closes on any action as it is sent.
 * Composed by every screen that hosts [ServiceItemMenuOverlay].
 */
@Composable
fun PlaylistPicker(menu: ServiceItemMenu) {
    val picker by menu.picker.collectAsState()
    val open = picker ?: return
    ItemMenu(
        title = "Add to playlist",
        subtitle = open.target.item.title,
        artUrl = open.target.item.artUrl,
        sections = listOf(
            MenuSection(
                open.target.linked.service.name,
                open.playlists.map { playlist -> MenuAction(playlist.title) { menu.addToPlaylist(open.target, playlist) } },
            ),
        ),
        onDismiss = menu::closePicker,
    )
}
