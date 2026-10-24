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
 *
 * And the route carries the speakers too ([RoomRoute]), so a screen opened while its group id was
 * already gone — the topology empty through a reconnect — still knows its room (P-88), and a screen
 * opened on a group the chosen room was only following follows that room, not the group's host, by
 * the same anchor as the home screen and the shared player (P-71). See [SavedRoom].
 */
class RoomTarget(private val household: SonosHousehold, private val saved: SavedStateHandle) {

    private val openedOn: String get() = checkNotNull(saved[GROUP]) { "a room screen opened with no group" }
    private val room = SavedRoom(saved, "room")

    init {
        // From the route, the first time only: on a restore what was learned since is already saved.
        if (room.coordinator == null) {
            RoomRoute.speakersOf(saved.get<String>(SPEAKERS))?.let { (coordinator, players) -> room.adopt(coordinator, players) }
        }
        learn()
    }

    /** Note the opened group's speakers while it exists, so they can be followed once it does not. */
    private fun learn() {
        val state = household.state.value
        state.groups.firstOrNull { it.id == openedOn }?.let { if (room.refresh(it)) saved[NAME] = it.name }
        // Opened on a party the room only joined, the group's name is the host's and is never kept:
        // the room's own speaker names it, or every press said "That room is no longer…" (P-106).
        if (saved.get<String>(NAME) == null) state.players.firstOrNull { it.id == room.coordinator }?.let { saved[NAME] = it.name }
    }

    /**
     * The viewer's room is now the one holding [playerId]: something this screen started formed it,
     * and the screen stays open on it. Nothing if no group holds it.
     */
    fun choose(playerId: String) {
        val group = household.state.value.groups.firstOrNull { playerId in it.playerIds } ?: return
        saved[GROUP] = group.id
        room.remember(group)
        saved[NAME] = group.name
    }

    /** The room's group now, or `null` when it is no longer in the household at all. */
    fun currentOrNull(): String? {
        val groups = household.state.value.groups
        val followed = room.findIn(groups)
        if (groups.any { it.id == openedOn } && (followed == null || followed.id == openedOn)) return openedOn.also { learn() }
        // Kept up to date as the room is followed, as the home screen and the player keep theirs:
        // frozen at what the screen opened on, a room that grew and then lost the speaker the
        // screen opened with was "no longer in this system" here while the pane beside it had
        // followed it to the speaker left (P-106).
        followed?.let { if (room.refresh(it)) saved[NAME] = it.name }
        return followed?.id
    }

    /** The room's group now; fails, saying so by name, when the room has left the household. */
    fun current(): String = currentOrNull()
        ?: error("${saved.get<String>(NAME) ?: "That room"} is no longer in this system")

    /**
     * For handing on to the next screen: the current group, or the one opened with if none, and the
     * speakers the room is followed by, so the next screen can find it either way (P-88).
     */
    fun forNavigation(): RoomRoute = RoomRoute(currentOrNull() ?: openedOn, room.coordinator, room.players)

    private companion object {
        const val GROUP = "groupId"
        const val SPEAKERS = RoomRoute.SPEAKERS
        const val NAME = "room.name"
    }
}

/**
 * A room as one screen hands it to the next: its group now, and the speakers of the room the viewer
 * chose — its coordinator then all of them — by which the next screen follows it once that id is
 * gone (P-88). A route's query carries both: `groupId` and [SPEAKERS], as [speakers] writes it.
 */
data class RoomRoute(
    val groupId: String,
    val coordinatorId: String? = null,
    val playerIds: List<String> = emptyList(),
) {
    /** `coordinator;player,player`, or empty with none known. Player ids carry neither mark. */
    val speakers: String get() = coordinatorId?.let { "$it;${playerIds.joinToString(",")}" }.orEmpty()

    companion object {
        /** The route argument [speakers] travels in. */
        const val SPEAKERS = "speakers"

        /** [speakers] read back: the coordinator and the players, or `null` for none. */
        fun speakersOf(text: String?): Pair<String, List<String>>? {
            val coordinator = text?.substringBefore(';')?.takeIf { it.isNotBlank() } ?: return null
            return coordinator to text.substringAfter(';', "").split(',').filter { it.isNotBlank() }
        }
    }
}

/**
 * The room the viewer chose, kept by its speakers in [saved] under [key]: the one anchor the home
 * screen's selection, the shared player and every screen's [RoomTarget] follow a room by.
 *
 * It is set by a choice ([remember]) and kept up to date only by its own room ([refresh]): when the
 * room is followed into a group another speaker coordinates — another controller added it to a party —
 * the anchor stays on the chosen room rather than moving onto the host. It used to move, so when the
 * party split, the host keeping its group id, the home screen and the player stayed with the host while
 * Queue, opened on the room, went with it: Kitchen's queue under a Living Room header, and the panel's
 * Sleep setting Living Room's timer (P-71, 2026-10-23).
 *
 * Saved state outlives the process, so a view model that outlives no process finds its room again when
 * Android restores it (P-51). Found by the rule every screen follows a room by, coordinator first, since
 * the group ids may have moved meanwhile.
 */
internal class SavedRoom(private val saved: SavedStateHandle, private val key: String) {
    val coordinator: String? get() = saved["$key.coordinator"]
    val players: List<String> get() = saved.get<ArrayList<String>>("$key.players").orEmpty()

    /** A choice: this group is the room now. */
    fun remember(group: Group) = adopt(group.coordinatorId, group.playerIds)

    fun adopt(coordinator: String, players: List<String>) {
        saved["$key.coordinator"] = coordinator
        saved["$key.players"] = ArrayList(players)
    }

    /**
     * [group] is where the room is now: kept as the room if it is the room's own — coordinated by the
     * same speaker, or by another of the very speakers kept — or nothing is kept yet. True if it was.
     *
     * The second because coordination can move inside a room: a grouped soundbar taking the TV takes
     * the group with it. Refused, the anchor kept the old coordinator, and a later split went with
     * that speaker rather than the soundbar's room the viewer was sitting at (P-117). A party is never
     * that: its host is not among the speakers kept.
     */
    fun refresh(group: Group): Boolean {
        val kept = coordinator
        if (kept != null && kept != group.coordinatorId && group.playerIds.toSet() != players.toSet()) return false
        // Unchanged is not written again: this runs on every event in the house.
        if (kept == group.coordinatorId && group.playerIds == players) return true
        remember(group)
        return true
    }

    /** The group holding the remembered room now, or `null` with nothing remembered or none found. */
    fun findIn(groups: List<Group>): Group? {
        val coordinator = coordinator ?: return null
        return groups.followingRoom(coordinator, players)
    }
}
