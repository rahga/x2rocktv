package com.rahga.x2rock.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.model.Group
import com.rahga.x2rock.model.followingRoom

/**
 * The room a screen was opened for, followed across regroups.
 *
 * A route carries a group id, and Sonos mints new ones whenever another controller joins or
 * separates rooms. A screen holding the id it opened with went on sending to a group that no
 * longer existed — Browse open across a regroup refused every favourite with "no group …", though
 * the room was there (outside review, 2026-10-08). So the room is remembered by its speakers,
 * which a regroup does not rename, as the home screen already follows speakers rather than ids;
 * and its group is looked up when there is something to send.
 *
 * The speakers are learned whenever the opened group is present, not only at construction, and kept
 * in the screen's [saved] state: a screen Android restores after reclaiming the app is built before
 * the household has reconnected, and with nothing learned then it could never follow a later regroup
 * (P-44). Saved state outlives the process, so the room is known again on restore.
 */
class RoomTarget(private val household: SonosHousehold, private val saved: SavedStateHandle) {

    private val openedOn: String = checkNotNull(saved[GROUP]) { "a room screen opened with no group" }

    init { learn() }

    /** Note the opened group's speakers while it exists, so they can be followed once it does not. */
    private fun learn() {
        val group = household.state.value.groups.firstOrNull { it.id == openedOn } ?: return
        saved[COORDINATOR] = group.coordinatorId
        saved[PLAYERS] = ArrayList(group.playerIds)
        saved[NAME] = group.name
    }

    /** The room's group now, or `null` when it is no longer in the household at all. */
    fun currentOrNull(): String? {
        val groups = household.state.value.groups
        if (groups.any { it.id == openedOn }) return openedOn.also { learn() }
        val coordinator = saved.get<String>(COORDINATOR) ?: return null
        return groups.followingRoom(coordinator, saved.get<ArrayList<String>>(PLAYERS).orEmpty())?.id
    }

    /** The room's group now; fails, saying so by name, when the room has left the household. */
    fun current(): String = currentOrNull()
        ?: error("${saved.get<String>(NAME) ?: "That room"} is no longer in this system")

    /** For handing on to the next screen: the current group, or the one opened with if none. */
    fun forNavigation(): String = currentOrNull() ?: openedOn

    private companion object {
        const val GROUP = "groupId"
        const val COORDINATOR = "room.coordinator"
        const val PLAYERS = "room.players"
        const val NAME = "room.name"
    }
}

/**
 * A room's speakers kept in [saved] under [key], so a view model that outlives no process can find its
 * room again when Android restores it (P-51): the shared player and the home screen's selection both
 * lived in memory only, so a screen restored after the app was reclaimed had no now-playing bar, no
 * room in its header and no room for the media keys, and Back opened on the default room. Found by the
 * rule every screen follows a room by, coordinator first, since the group ids may have moved meanwhile.
 */
internal class SavedRoom(private val saved: SavedStateHandle, private val key: String) {
    fun remember(group: Group) {
        saved["$key.coordinator"] = group.coordinatorId
        saved["$key.players"] = ArrayList(group.playerIds)
    }

    /** The group holding the remembered room now, or `null` with nothing remembered or none found. */
    fun findIn(groups: List<Group>): Group? {
        val coordinator = saved.get<String>("$key.coordinator") ?: return null
        return groups.followingRoom(coordinator, saved.get<ArrayList<String>>("$key.players").orEmpty())
    }
}
