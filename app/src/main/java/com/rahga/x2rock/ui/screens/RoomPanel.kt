package com.rahga.x2rock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
 * Volume lives on the member rows, on left and right, because that is where a level belongs
 * once a group has more than one speaker in it — the room view's slider stays the group's.
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
    isPrimary: Boolean,
    isFavorite: Boolean,
    /** One group holding the whole household, which is what party mode leaves behind. */
    isPartying: Boolean,
    onParty: () -> Unit,
    onStopParty: () -> Unit,
    onRemovePlayer: (playerId: String) -> Unit,
    onAdjustPlayerVolume: (playerId: String, delta: Int) -> Unit,
    onJoin: (Group) -> Unit,
    onSetPrimary: () -> Unit,
    onToggleFavorite: () -> Unit,
    onUseTvInput: () -> Unit,
) {
    val firstFocus = rememberAutoFocusRequester()
    val members = playerNames.size

    // Whichever row is drawn first takes the focus, rather than a fixed one: every section
    // below is conditional, and a panel that opened with nothing focused would leave the
    // remote dead but for Back. Reset on each composition, claimed in draw order.
    var focusClaimed = false
    fun Modifier.claimFirstFocus(): Modifier =
        if (focusClaimed) this else this.also { focusClaimed = true }.focusRequester(firstFocus)

    Column(
        modifier = Modifier
            .width(420.dp)
            .heightIn(max = 520.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(group.name, style = MaterialTheme.typography.titleMedium)
        Text(
            text = if (info.onTvInput) info.source ?: "TV Audio"
            else (group.playbackState ?: PlaybackStates.IDLE).toPlaybackLabel(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )

        // Party mode. Nothing to gather in a one-room household, and nothing to stop
        // unless the house is already in one group.
        if (otherGroups.isNotEmpty() || isPartying) {
            PanelSection("Everywhere")
            AppButton(
                onClick = if (isPartying) onStopParty else onParty,
                modifier = Modifier.fillMaxWidth().claimFirstFocus(),
            ) {
                Text(if (isPartying) "Stop party" else "Party — play this in every room")
            }
        }

        // The rooms already playing with this one. A room on its own is not a group to
        // list the members of, so nothing is drawn for it.
        if (members > 1) {
            PanelSection("Playing together")
            playerNames.forEach { (playerId, name) ->
                val coordinator = playerId == group.coordinatorId
                MemberRow(
                    modifier = Modifier.claimFirstFocus(),
                    name = name,
                    volume = playerVolumes[playerId],
                    // The coordinator *is* the group; removing it would dissolve the group
                    // rather than free the room, so it has nothing to leave.
                    canLeave = !coordinator,
                    onLeave = { onRemovePlayer(playerId) },
                    onAdjust = { delta -> onAdjustPlayerVolume(playerId, delta) },
                )
            }
        }

        // And the rooms that could join it.
        if (otherGroups.isNotEmpty()) {
            PanelSection(if (members > 1) "Add another" else "Play together with")
            otherGroups.forEach { other ->
                AppButton(
                    onClick = { onJoin(other) },
                    modifier = Modifier.fillMaxWidth().claimFirstFocus(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(other.name, style = MaterialTheme.typography.bodyLarge)
                            val subtitle = rooms[other.id]?.track?.name
                                ?: (other.playbackState ?: PlaybackStates.IDLE).toPlaybackLabel()
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text("join", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else if (members > 1) {
            Text(
                "Every room is in this group.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }

        PanelSection("This room")
        AppButton(onClick = onSetPrimary, modifier = Modifier.fillMaxWidth().claimFirstFocus()) {
            Text(if (isPrimary) "Remove as primary" else "Set as primary")
        }
        AppButton(onClick = onToggleFavorite, modifier = Modifier.fillMaxWidth()) {
            Text(if (isFavorite) "Remove from favorites" else "Add to favorites")
        }

        // Last, and only where there is an HDMI socket to switch to.
        if (info.hasTvInput) {
            PanelSection("Source")
            AppButton(
                onClick = onUseTvInput,
                // Already the source: the row stays visible so the state is legible, but
                // there is nothing left for it to do.
                enabled = !info.onTvInput,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (info.onTvInput) "TV Input (current source)" else "TV Input")
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
 * One speaker in the group: its level on left and right, and Enter to send it home.
 *
 * The volume is read from the row rather than shown as a slider because a remote has no
 * drag — left and right are the whole gesture, and the bar is there to be watched while
 * they are held rather than to be aimed at.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MemberRow(
    modifier: Modifier,
    name: String,
    volume: Int?,
    canLeave: Boolean,
    onLeave: () -> Unit,
    onAdjust: (Int) -> Unit,
) {
    AppButton(
        onClick = { if (canLeave) onLeave() },
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
                Text(name, style = MaterialTheme.typography.bodyLarge)
                VolumeBar(volume)
            }
            Text(
                text = if (canLeave) "leave  ${volume ?: "—"}" else "${volume ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun VolumeBar(volume: Int?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)),
    ) {
        if (volume != null && volume > 0) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(volume / 100f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/** The same step the player pane's volume uses. */
private const val VOLUME_STEP = 5
