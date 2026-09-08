package com.rahga.x2rock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import com.rahga.x2rock.ui.theme.requestFocusSafely
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.rahga.x2rock.model.Group
import com.rahga.x2rock.model.PlaybackStates
import com.rahga.x2rock.model.toPlaybackLabel
import com.rahga.x2rock.ui.theme.AppButton
import com.rahga.x2rock.ui.theme.rememberAutoFocusRequester
import com.rahga.x2rock.viewmodel.HomeViewModel

/**
 * Everything one room can be told to do, on one surface.
 *
 * This replaces a menu that opened two further dialogs — "Join Group…" and "Separate a
 * Room…" each led somewhere else — with the shape the desktop widget settled on: the rooms
 * playing together with this one, then the rooms that could, and Enter does whatever the
 * row it is on says. Grouping is the same decision at two sizes, so party mode sits at the
 * top of the same list rather than in a button of its own.
 *
 * Order is deliberate. Party is first because it is the one press that answers "put this
 * everywhere", which is what the remote is usually reached for. TV Input is last and
 * appears only for a room with a soundbar in it: it is the one row here that changes what
 * the room is *playing* rather than which rooms are listening, and it is a way out of
 * whatever music the house is on rather than a thing to do to the house.
 *
 * **Every room row carries a level, on left and right.** Nothing else in this panel uses
 * those directions, so they were free to take, and it means a level is reachable for any
 * room the panel names rather than only for the ones already grouped. A member row adjusts
 * that *speaker* (`playerVolume:1`); a joinable row adjusts that whole *group*
 * (`groupVolume:1`), because a row in that list stands for a group and may be several rooms.
 *
 * The two scopes stay in step without this app doing any arithmetic: Sonos scales a group's
 * members proportionally when the group's level moves, and moves the group's average when a
 * member's does. Both come back as pushed events, so the sliders simply follow.
 *
 * The panel opens focused on **the room itself**, not on Party — the row a viewer most
 * likely came to touch, and one press away from either.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RoomPanel(
    group: Group,
    info: HomeViewModel.RoomInfo,
    /** Every other group in the household, in the sidebar's order. */
    otherGroups: List<Group>,
    rooms: Map<String, HomeViewModel.RoomInfo>,
    /** This group's speakers, id to name, coordinator first. */
    playerNames: List<Pair<String, String>>,
    playerVolumes: Map<String, Int>,
    /** Each joinable group's own level, keyed by group id. */
    groupVolumes: Map<String, Int>,
    /** One group holding the whole household, which is what party mode leaves behind. */
    isPartying: Boolean,
    onParty: () -> Unit,
    onStopParty: () -> Unit,
    onRemovePlayer: (playerId: String) -> Unit,
    onAdjustPlayerVolume: (playerId: String, delta: Int) -> Unit,
    onAdjustGroupVolume: (groupId: String, delta: Int) -> Unit,
    onJoin: (Group) -> Unit,
    onSetTvRoom: () -> Unit,
    onUseTvInput: () -> Unit,
) {
    val firstFocus = rememberAutoFocusRequester()
    val members = playerNames.size

    // Rescue focus when the row holding it leaves the composition. The panel deliberately
    // stays open while grouping — building a group is several presses — so the section under
    // the cursor can vanish beneath it: take the last other member out and "Playing
    // together" goes with it, join the last remaining room and "Add another" does. The
    // requester from `rememberAutoFocusRequester` has already fired and will not fire again,
    // and `Overlay`'s trap refuses focus *exit*, so nothing would hold it and the remote
    // would be dead but for Back.
    //
    // Keyed on having no focus rather than on the structure, so ordinary navigation between
    // rows is never yanked back to the top.
    var panelHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(panelHasFocus) {
        if (!panelHasFocus) firstFocus.requestFocusSafely()
    }

    Column(
        modifier = Modifier
            .width(420.dp)
            .heightIn(max = 520.dp)
            .focusGroup()
            .onFocusChanged { panelHasFocus = it.hasFocus }
            .background(MaterialTheme.colorScheme.surface)
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // One line, not two. The panel opens focused on the room's own row rather than the
        // top, so anything above it is scrolled past — and a two-line header pushed that row
        // far enough down to clip the title on open. The room is named again just below.
        Text(
            text = group.name + " · " + (
                if (info.onTvInput) info.source ?: "TV Audio"
                else (group.playbackState ?: PlaybackStates.IDLE).toPlaybackLabel()
                ),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        // Party mode. Nothing to gather in a one-room household, and nothing to stop
        // unless the house is already in one group.
        if (otherGroups.isNotEmpty() || isPartying) {
            PanelSection("Everywhere")
            AppButton(
                onClick = if (isPartying) onStopParty else onParty,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isPartying) "Stop party" else "Party — play this in every room")
            }
        }

        // This room, and whatever is grouped with it. Drawn even when it is alone, because
        // its level belongs here either way and it is what the panel opens onto.
        PanelSection(if (members > 1) "Playing together" else "This room")
        playerNames.forEachIndexed { index, (playerId, name) ->
            val coordinator = playerId == group.coordinatorId
            RoomRow(
                // Coordinator first, so this is the room the panel is named after.
                modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                name = name,
                volume = playerVolumes[playerId],
                // The coordinator *is* the group; removing it would dissolve the group
                // rather than free the room, so it has nothing to leave.
                action = if (coordinator) null else "leave",
                onActivate = { if (!coordinator) onRemovePlayer(playerId) },
                onAdjust = { delta -> onAdjustPlayerVolume(playerId, delta) },
            )
        }

        // And the rooms that could join it.
        if (otherGroups.isNotEmpty()) {
            PanelSection(if (members > 1) "Add another" else "Play together with")
            otherGroups.forEach { other ->
                RoomRow(
                    modifier = Modifier,
                    name = if (other.playerIds.size > 1) "${other.name} · ${other.playerIds.size} rooms"
                    else other.name,
                    subtitle = rooms[other.id]?.track?.name
                        ?: (other.playbackState ?: PlaybackStates.IDLE).toPlaybackLabel(),
                    // A row here stands for a whole group, so its level is the group's.
                    volume = groupVolumes[other.id],
                    action = "join",
                    onActivate = { onJoin(other) },
                    onAdjust = { delta -> onAdjustGroupVolume(other.id, delta) },
                )
            }
        } else if (members > 1) {
            Text(
                "Every room is in this group.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }

        // Both of these need an HDMI socket to mean anything, so a room without one ends at
        // the lists above. The source comes first: it is a thing to *do*, and the setting
        // below it is a thing to state once and never touch again.
        if (info.hasTvInput) {
            PanelSection("Source")
            AppButton(
                onClick = onUseTvInput,
                // Deliberately *not* disabled while it is already the source. A disabled
                // button here still takes focus and draws no highlight, so pressing down
                // onto it left the remote sitting on an invisible row — seen on the
                // Streamer. Re-selecting the input it is already on is harmless anyway, and
                // is the obvious thing to press when the TV audio has dropped out.
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (info.onTvInput) "TV Input (current source)" else "TV Input")
            }

            // Detection fills this in on its own but has been seen to answer wrongly and
            // then keep the answer, so it has to be correctable by hand. There is no
            // un-naming: the crown simply moves to whichever room is named next.
            PanelSection("Room settings")
            AppButton(onClick = onSetTvRoom, modifier = Modifier.fillMaxWidth()) {
                Text("This is my TV")
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PanelSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        modifier = Modifier.padding(top = 8.dp),
    )
}

/**
 * One room, in either list: its level on left and right, and Enter for what [action] says.
 *
 * The level is a read-out rather than something to aim at, because a remote has no drag —
 * left and right are the whole gesture, and the bar is there to be watched while they are
 * held. Both lists use this so a level is never somewhere a room is not.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RoomRow(
    modifier: Modifier,
    name: String,
    volume: Int?,
    /** "join", "leave", or none — the coordinator has nothing to leave. */
    action: String?,
    onActivate: () -> Unit,
    onAdjust: (Int) -> Unit,
    subtitle: String? = null,
) {
    AppButton(
        onClick = onActivate,
        modifier = modifier
            .fillMaxWidth()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val delta = when (event.key) {
                    Key.DirectionLeft -> -VOLUME_STEP
                    Key.DirectionRight -> +VOLUME_STEP
                    else -> return@onKeyEvent false
                }
                // Only once the speaker has said where it is: adjusting from a level we do
                // not know would aim from zero and turn it down. Still consumed either way,
                // so a press does not wander out of the panel while it is unknown.
                if (volume != null) onAdjust(delta)
                true
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // The level sits at the end of the last line of text, so it is always
                // directly above the right end of its own bar rather than off beside the
                // action — a number next to "join" read as part of the action.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (subtitle == null) VolumeLevel(volume)
                }
                if (subtitle != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        VolumeLevel(volume)
                    }
                }
                VolumeBar(volume)
            }
            if (action != null) {
                Spacer(Modifier.width(16.dp))
                Text(action, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun VolumeLevel(volume: Int?) {
    Text(
        text = volume?.toString() ?: "—",
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * Painted in the row's *content* colour rather than a fixed one.
 *
 * A focused button's container is already the primary colour, so a primary fill on it was
 * very nearly invisible — on the one row a viewer is actually adjusting. Taking the content
 * colour makes the bar invert along with the label.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun VolumeBar(volume: Int?) {
    val ink = LocalContentColor.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(ink.copy(alpha = 0.25f)),
    ) {
        if (volume != null && volume > 0) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(volume / 100f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(ink),
            )
        }
    }
}

/** The same step the player pane's volume uses. */
private const val VOLUME_STEP = 5
