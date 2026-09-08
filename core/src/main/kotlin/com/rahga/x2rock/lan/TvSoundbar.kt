package com.rahga.x2rock.lan

import com.rahga.x2rock.model.Group

/**
 * Which soundbar this television is plugged into.
 *
 * There is no way to ask. Android will say an HDMI output exists but not what is on the
 * other end of it, and CEC details are not available to an ordinary app — so this is a
 * heuristic, and it is worth being honest about where it stops.
 *
 * The signal: among the rooms that *have* an HDMI input, the one currently *on* it. In a
 * household with three soundbars this picked out exactly the one fed by the Shield, and it
 * holds while nothing is playing, because a selected TV input still reports itself (as
 * "Silence 2.0") rather than disappearing.
 *
 * Where it stops:
 *
 * - The television is off, or that room switched to music, and nothing is on a TV input.
 * - Two televisions are on at once, and two soundbars are.
 *
 * Both answer null rather than guessing, which is why the answer is worth *remembering*
 * once found: the device is bolted to one television and does not move, so a detection made
 * while the TV was on stays true when it is off.
 *
 * It returns a **player** id, never a group id. A regroup mints new group ids, so a group
 * id remembered today may name nothing tomorrow; the soundbar itself is stable.
 */
object TvSoundbar {

    fun detect(state: HouseholdState, groupStates: Map<String, GroupState>): String? {
        val onTv = state.groups.filter { group ->
            state.hasTvInput(group) && groupStates[group.id]?.onTvInput == true
        }
        val only = onTv.singleOrNull() ?: return null
        return soundbarOf(only, state)
    }

    /**
     * The speaker holding the HDMI socket, which need not be the coordinator: a soundbar
     * that joined another room's group still has its input.
     *
     * **The coordinator wins when it has one.** A group can hold several soundbars — party
     * mode across a household with three Beams is enough — and "the first player with an
     * HDMI socket" is then whichever way the topology happened to list them. The coordinator
     * is the room the group is *named* after and the one a panel is titled with, so it is
     * the answer a viewer pressing "this room's TV" means. Only when it has no socket of its
     * own does this fall to a member, which is the Kitchen-coordinates-a-Beam case.
     */
    fun soundbarOf(group: Group, state: HouseholdState): String? {
        val byId = state.players.associateBy { it.id }
        fun hasHdmi(id: String) = HT_PLAYBACK in (byId[id]?.capabilities ?: emptyList())
        return group.coordinatorId.takeIf { hasHdmi(it) }
            ?: group.playerIds.firstOrNull { hasHdmi(it) }
    }

    /** Whether this particular player is the one holding an HDMI socket. */
    fun hasHdmi(playerId: String, state: HouseholdState): Boolean =
        state.players.firstOrNull { it.id == playerId }
            ?.capabilities?.contains(HT_PLAYBACK) == true

    /** The group that currently holds [playerId], if any still does. */
    fun groupOf(playerId: String?, groups: List<Group>): Group? =
        playerId?.let { id -> groups.firstOrNull { id in it.playerIds } }
}
