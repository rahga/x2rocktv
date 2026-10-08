package com.rahga.x2rock.viewmodel

import com.rahga.x2rock.lan.SonosHousehold

/**
 * The room a screen was opened for, followed across regroups.
 *
 * A route carries a group id, and Sonos mints new ones whenever another controller joins or
 * separates rooms. A screen holding the id it opened with went on sending to a group that no
 * longer existed — Browse open across a regroup refused every favourite with "no group …", though
 * the room was there (outside review, 2026-10-08). So the room is remembered by its coordinator,
 * a speaker, which a regroup does not rename, as the home screen already follows speakers rather
 * than ids; and its group is looked up when there is something to send.
 */
class RoomTarget(private val household: SonosHousehold, private val openedOn: String) {

    private val opened = household.state.value.groups.firstOrNull { it.id == openedOn }
    private val anchor: String? = opened?.coordinatorId

    /** The room's group now, or `null` when it is no longer in the household at all. */
    fun currentOrNull(): String? {
        val groups = household.state.value.groups
        if (groups.any { it.id == openedOn }) return openedOn
        return anchor?.let { speaker -> groups.firstOrNull { speaker in it.playerIds }?.id }
    }

    /** The room's group now; fails, saying so by name, when the room has left the household. */
    fun current(): String = currentOrNull()
        ?: error("${opened?.name ?: "That room"} is no longer in this system")

    /** For handing on to the next screen: the current group, or the one opened with if none. */
    fun forNavigation(): String = currentOrNull() ?: openedOn
}
