package com.rahga.x2rock.viewmodel

import com.rahga.x2rock.apple.AppleMusicItem
import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.smapi.Item
import com.rahga.x2rock.smapi.ItemDetails
import com.rahga.x2rock.smapi.LinkedService
import com.rahga.x2rock.smapi.ServiceContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * An item's menu — a hold or Menu on its row — and everything it can do, for the screens that list
 * a service's items: Music Services and Search. The remote's version of the Sonos app's "⋯": Play
 * now, Play next (only while the room plays from its queue, as there), Add to end of queue, then
 * what the service itself says about the item — its artist, its album, a radio built from it, and
 * the account's favourites.
 *
 * [starting] is the screen's own "Starting…" marker, set to [keyOf] the item while it starts.
 */
class ServiceItemMenu(
    private val household: SonosHousehold,
    private val groupId: String,
    private val scope: CoroutineScope,
    private val notice: TransientNotice,
    private val starting: MutableStateFlow<String?>,
    private val keyOf: (Target) -> String,
) {
    /** What a menu is for: a service's item, or an Apple Music search result. */
    sealed interface Target {
        val title: String
        val subtitle: String?
        val artUrl: String?
        val canQueue: Boolean

        data class Service(val linked: LinkedService, val item: Item) : Target {
            override val title get() = item.title
            override val subtitle get() = item.summary
            override val artUrl get() = item.artUrl
            override val canQueue get() = ServiceContent.canEnqueue(item)
            /** A track, a station, a book to resume — or an album or playlist, played whole. */
            val canPlay get() = !ServiceContent.opens(item) || canQueue
        }

        data class Apple(val item: AppleMusicItem) : Target {
            override val title get() = item.title
            override val subtitle get() = item.artist
            override val artUrl get() = item.artworkUrl
            override val canQueue get() = true
        }
    }

    private val _open = MutableStateFlow<Target?>(null)
    val open: StateFlow<Target?> = _open.asStateFlow()

    /**
     * Whether the room plays from its queue, read as a menu opens. Play Next is offered only then,
     * as the Sonos app does: on a station there is no "next" in a queue nobody is playing.
     */
    private val _fromQueue = MutableStateFlow(false)
    val fromQueue: StateFlow<Boolean> = _fromQueue.asStateFlow()

    /** What the service says about the item — its artist, album, radio — or `null` while asked. */
    private val _details = MutableStateFlow<ItemDetails?>(null)
    val details: StateFlow<ItemDetails?> = _details.asStateFlow()
    private var detailsJob: Job? = null

    fun show(target: Target) {
        _open.value = target
        _fromQueue.value = false
        _details.value = null
        scope.launch {
            _fromQueue.value = runCatching { household.playingFromQueue(groupId) }.getOrDefault(false)
        }
        detailsJob?.cancel()
        detailsJob = scope.launch {
            // A service that answers nothing more about an item still leaves Sonos's own actions;
            // an Apple result has no service of its own to ask.
            _details.value = (target as? Target.Service)
                ?.let { runCatching { household.serviceItemDetails(it.linked, it.item) }.getOrNull() }
                ?: ItemDetails()
        }
    }

    fun close() {
        detailsJob?.cancel()
        _open.value = null
    }

    /** Play [target] now, in place of what the room plays — a track, or a whole album or playlist. */
    fun playNow(target: Target, onPlayed: () -> Unit) {
        if (starting.value != null) return
        scope.launch {
            starting.value = keyOf(target)
            val started = runCatching {
                when (target) {
                    is Target.Apple -> household.playAppleMusic(groupId, target.item, appleAccount())
                    is Target.Service ->
                        if (ServiceContent.opens(target.item)) household.playServiceItem(groupId, target.linked, target.item)
                        else household.startServiceItem(groupId, target.linked, target.item)
                }
            }
            starting.value = null
            started.onSuccess { onPlayed() }.onFailure { notice.failure("play ${target.title}", it) }
        }
    }

    /**
     * Add [target] to the queue — at its end, or with [next] after the track playing — leaving what
     * plays alone. Only what a queue can hold.
     */
    fun queue(target: Target, next: Boolean = false) {
        if (!target.canQueue) return notice.post("\"${target.title}\" isn't something a queue can hold")
        scope.launch {
            runCatching {
                when (target) {
                    is Target.Apple -> household.queueAppleMusic(groupId, target.item, appleAccount(), next)
                    is Target.Service -> household.queueServiceItem(groupId, target.linked, target.item, next)
                }
            }.onSuccess { notice.post(if (next) "\"${target.title}\" plays next" else "Added \"${target.title}\" to the queue") }
                .onFailure { notice.failure("add ${target.title}", it) }
        }
    }

    /** Start the radio the service builds from an item — its `relatedPlay`. */
    fun startRadio(linked: LinkedService, radio: Item, onPlayed: () -> Unit) =
        playNow(Target.Service(linked, radio), onPlayed)

    /** Add [item] to the account's favourites in its service, or take it out. */
    fun setFavorite(linked: LinkedService, item: Item, favorite: Boolean) {
        scope.launch {
            val name = linked.service.name
            runCatching { household.setServiceFavorite(linked, item, favorite) }
                .onSuccess { notice.post(if (favorite) "Added to your $name favourites" else "Removed from your $name favourites") }
                .onFailure { notice.failure(if (favorite) "add to $name favourites" else "remove from $name favourites", it) }
        }
    }

    private suspend fun appleAccount(): String = household.appleMusicAccount() ?: error(NO_ACCOUNT)
}
