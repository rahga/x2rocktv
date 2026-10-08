package com.rahga.x2rock.ui.components

import androidx.compose.runtime.Composable
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
) {
    val service = (target as? ServiceItemMenu.Target.Service)
    ItemMenu(
        title = target.title,
        subtitle = target.subtitle,
        artUrl = target.artUrl,
        sections = listOf(
            MenuSection(null, playActions(menu, target, room, fromQueue, onOpen, onPlayed)),
            MenuSection(
                service?.linked?.service?.name,
                if (service != null && details != null) serviceActions(menu, service, details, here, onOpenRelated, onPlayed) else emptyList(),
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
    onOpen: (() -> Unit)?, onPlayed: () -> Unit,
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
