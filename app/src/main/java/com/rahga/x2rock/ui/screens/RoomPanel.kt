package com.rahga.x2rock.ui.screens

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import com.rahga.x2rock.ui.components.StepMark
import com.rahga.x2rock.ui.components.mutedInk
import com.rahga.x2rock.ui.components.stepMarksShown
import com.rahga.x2rock.ui.theme.requestFocusSafely
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
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
import com.rahga.x2rock.ui.theme.AppButton
import com.rahga.x2rock.ui.theme.IconLabelButton
import com.rahga.x2rock.ui.theme.rememberAutoFocusRequester
import com.rahga.x2rock.viewmodel.HomeViewModel
import com.rahga.x2rock.viewmodel.hasSource
import com.rahga.x2rock.viewmodel.roomActivity
import com.rahga.x2rock.viewmodel.roomRowLines
import com.rahga.x2rock.viewmodel.label
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.tv.material3.Icon
import kotlin.math.roundToInt

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
    /** One group holding the whole household, which is what party mode leaves behind. */
    isPartying: Boolean,
    onParty: () -> Unit,
    onStopParty: () -> Unit,
    onRemovePlayer: (playerId: String) -> Unit,
    onJoin: (Group) -> Unit,
    onSetTvRoom: () -> Unit,
    onUseTvInput: () -> Unit,
    /** Play a speaker's line-in here: this room's own, or another's. */
    onUseLineIn: (playerId: String) -> Unit,
    /** UPnP is off for the household, so the TV input cannot be switched to. */
    upnpOff: Boolean,
    /** This room's tone, or `null` while it is read. */
    tone: HomeViewModel.ToneUi?,
    onStepBass: (Int) -> Unit,
    onStepTreble: (Int) -> Unit,
    onToggleLoudness: () -> Unit,
    onToggleTrueplay: () -> Unit,
    onToggleSub: () -> Unit,
    onStepSub: (Int) -> Unit,
    onToggleSurrounds: () -> Unit,
    onStepSurround: (Int) -> Unit,
    onStepMusicSurround: (Int) -> Unit,
    onToggleStatusLight: () -> Unit,
    onToggleRemoteFeedback: () -> Unit,
    onToggleIrRepeater: () -> Unit,
    onToggleTouchControls: () -> Unit,
    onToggleCrossfade: () -> Unit,
    /** Keep this room's speakers, levels and favourite as a preset in Browse. */
    onSavePreset: () -> Unit,
    /**
     * Which half to show: false for grouping (Everywhere, the two room lists, Presets), true for
     * Configure (Source, Sleep timer, Sound, Room settings), each opened by its own pane button.
     */
    configure: Boolean = false,
    /** When a running sleep timer ends, as a clock time, or null for none. */
    sleepEndsAt: String? = null,
    onSetSleep: (minutes: Int) -> Unit = {},
    onCancelSleep: () -> Unit = {},
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
    //
    // A room moved from one list to the other hands focus to the row that takes its place — the
    // next in its list, else the one before — rather than to the top: moving several used to
    // mean walking back down from the room's own row after each (2026-10-17).
    val rowFocus = remember { mutableMapOf<String, FocusRequester>() }
    fun rowFocusFor(key: String) = rowFocus.getOrPut(key) { FocusRequester() }
    var focusNext by remember { mutableStateOf<String?>(null) }
    fun neighbour(keys: List<String>, key: String): String? {
        val at = keys.indexOf(key)
        return keys.getOrNull(at + 1) ?: keys.getOrNull(at - 1)?.takeIf { at > 0 }
    }
    var panelHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(panelHasFocus) {
        if (panelHasFocus) return@LaunchedEffect
        val next = focusNext?.let { rowFocus[it] }
        focusNext = null
        if (next?.requestFocusSafely() != true) firstFocus.requestFocusSafely()
    }

    Column(
        modifier = Modifier
            .width(480.dp)
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
                else roomActivity(group.playbackState, info.hasSource).label
                ),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (!configure) {
            // Party mode. Nothing to gather in a one-room household, and nothing to stop
            // unless the house is already in one group.
            if (otherGroups.isNotEmpty() || isPartying) {
                PanelSection("Everywhere")
                IconLabelButton(
                    if (isPartying) Icons.AutoMirrored.Filled.CallSplit else Icons.Default.SpeakerGroup,
                    if (isPartying) "Split every room apart" else "Play everywhere",
                    if (isPartying) onStopParty else onParty,
                    Modifier.fillMaxWidth(),
                )
            }

            // This room, and whatever is grouped with it. Drawn even when it is alone: it is what the
            // panel opens onto.
            PanelSection("Rooms in this group")
            playerNames.forEachIndexed { index, (playerId, name) ->
                val coordinator = playerId == group.coordinatorId
                RoomRow(
                    // Coordinator first, so this is the room the panel is named after.
                    modifier = Modifier.focusRequester(rowFocusFor(playerId))
                        .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                    name = name,
                    // The coordinator *is* the group; removing it would dissolve the group
                    // rather than free the room, so it has nothing to leave.
                    action = if (coordinator) null else "leave",
                    onActivate = {
                        if (!coordinator) {
                            focusNext = neighbour(playerNames.map { it.first }, playerId)
                            onRemovePlayer(playerId)
                        }
                    },
                )
            }

            // And the rooms that could join it.
            if (otherGroups.isNotEmpty()) {
                PanelSection("Rooms not in this group")
                otherGroups.forEach { other ->
                    RoomRow(
                        name = other.label,
                        subtitle = rooms[other.id]?.let { roomRowLines(it, other.playbackState ?: PlaybackStates.IDLE).firstOrNull() }
                            ?: roomActivity(other.playbackState, false).label,
                        action = "join",
                        onActivate = {
                            focusNext = neighbour(otherGroups.map { it.id }, other.id)
                            onJoin(other)
                        },
                        modifier = Modifier.focusRequester(rowFocusFor(other.id)),
                    )
                }
            } else if (members > 1) {
                Text(
                    "Every room is in this group.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }

            // A scene to bring back with one press, from Browse: these speakers at these levels, and
            // the favourite playing if what plays is one. Here because this is where a room's
            // speakers and levels are set up; Browse is where it is played from.
            PanelSection("Presets")
            IconLabelButton(Icons.Default.BookmarkAdd, "Save as preset", onSavePreset, Modifier.fillMaxWidth())

        } else {
        Column(Modifier.focusRequester(firstFocus).focusGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // TV Input is UPnP, so with UPnP off it is not offered; Line-In is the Control API's own
                // `loadLineIn` and needs nothing else. Either makes a Source section.
                val tvSwitchable = info.hasTvInput && !upnpOff
                if (tvSwitchable || info.lineIns.isNotEmpty()) PanelSection("Source")
                if (tvSwitchable) {
                    AppButton(
                        onClick = onUseTvInput,
                        // Deliberately *not* disabled while it is already the source. A disabled
                        // button here still takes focus and draws no highlight, so pressing down
                        // onto it left the remote sitting on an invisible row — seen on the
                        // Streamer. Re-selecting the input it is already on is harmless anyway,
                        // and is the obvious thing to press when the TV audio has dropped out.
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (info.onTvInput) "TV Input (current source)" else "TV Input")
                    }
                }
                // Beta, untested on hardware: no player here has a line-in (see `LINE_IN`), and present only
                // with Beta features on. Nothing says when a room is already on one — what such a room
                // reports has never been seen — so these rows never claim "current source". Another room's
                // input is offered too, as Sonos's own steps imply (T-21).
                info.lineIns.forEach { input ->
                    AppButton(onClick = { onUseLineIn(input.playerId) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (input.own) "Line-In" else "Line-In on ${input.room}", modifier = Modifier.weight(1f))
                        BetaMark()
                    }
                }

                // The room's sleep timer, Sonos's own on AVTransport: with UPnP off there is none to set.
                if (!upnpOff) {
                    PanelSection("Sleep timer")
                    if (sleepEndsAt != null) {
                        AppButton(onClick = onCancelSleep, modifier = Modifier.fillMaxWidth()) {
                            Text("Cancel · stops at $sleepEndsAt")
                        }
                    }
                    // In pairs, as the picker had them: six lengths fit with the rest of the panel below.
                    SLEEP_CHOICES.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { minutes ->
                                AppButton(onClick = { onSetSleep(minutes) }, modifier = Modifier.weight(1f)) {
                                    Text(sleepChoiceLabel(minutes), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                }

                // Both of these need an HDMI socket to mean anything, so a room without one ends at
                // the lists above. The source comes first: it is a thing to *do*, and the setting
                // below it is a thing to state once and never touch again.
                // Tone, for every room: it is the speaker's, not the soundbar's. Written over UPnP, so
                // not offered with UPnP off; and not until it has been read, since a row that guessed
                // a level would step from the wrong one.
                // Crossfade is the group's, over the Control API, so it needs neither the tone nor UPnP;
                // it moved here from the pane's button row, which needed the room for Group.
                val shownTone = tone?.takeIf { !upnpOff }
                if (shownTone != null || info.crossfade != null) PanelSection("Sound")
                if (shownTone != null) {
                    ToneRow("Bass", shownTone.bass, onStepBass)
                    ToneRow("Treble", shownTone.treble, onStepTreble)
                    AppButton(onClick = onToggleLoudness, modifier = Modifier.fillMaxWidth()) {
                        Text(if (shownTone.loudness) "Loudness   On" else "Loudness   Off")
                    }
                    // Only where the speaker has a calibration to turn back on; measuring is the app's.
                    shownTone.trueplay?.takeIf { it.available }?.let { trueplay ->
                        AppButton(onClick = onToggleTrueplay, modifier = Modifier.fillMaxWidth()) {
                            Text(if (trueplay.enabled) "TruePlay   On" else "TruePlay   Off")
                        }
                    }
                    // The Sonos app's Sub audio and Surround audio, only where the room has the speakers:
                    // its zone names them, which an EQ probe cannot say (SonosHousehold.satellites).
                    shownTone.satellites?.let { s ->
                        s.subOn?.let { on ->
                            AppButton(onClick = onToggleSub, modifier = Modifier.fillMaxWidth()) { Text(if (on) "Sub   On" else "Sub   Off") }
                        }
                        s.subLevel?.let { ToneRow("Sub level", it, onStepSub) }
                        s.surroundsOn?.let { on ->
                            AppButton(onClick = onToggleSurrounds, modifier = Modifier.fillMaxWidth()) {
                                Text(if (on) "Surrounds   On" else "Surrounds   Off")
                            }
                        }
                        s.tvLevel?.let { ToneRow("Surrounds for TV", it, onStepSurround) }
                        s.musicLevel?.let { ToneRow("Surrounds for music", it, onStepMusicSurround) }
                    }
                }
                info.crossfade?.let { on ->
                    AppButton(onClick = onToggleCrossfade, modifier = Modifier.fillMaxWidth()) {
                        Text(if (on) "Crossfade   On" else "Crossfade   Off")
                    }
                }

                // The room's own speaker's switches, as the Sonos app keeps them under its settings. UPnP,
                // so with it off they go with the Sound rows; read with them, so absent until read.
                val light = shownTone?.statusLight
                val locked = shownTone?.buttonsLocked
                val limit = shownTone?.volumeLimit?.takeIf { it < 100 }
                if (light != null || locked != null || limit != null || shownTone?.battery != null || info.hasTvInput) PanelSection("Room settings")
                // A portable's pack, as a line: it is read, not set. Health only where it is not the usual GREEN.
                shownTone?.battery?.let { pack ->
                    val parts = listOfNotNull(
                        pack.level?.let { "Battery $it%" } ?: "Battery",
                        when (pack.charging) { true -> "charging"; false -> "on battery"; null -> null },
                        pack.health?.takeUnless { it.equals("GREEN", ignoreCase = true) },
                        // Read only with Beta features on: never seen on a portable (T-21).
                        "Beta",
                    )
                    Text(
                        parts.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                // Said only where one is set, since it explains a room that seems too quiet; not a focus stop,
                // because this connection may not change it (setPlayerSettings is refused without a sign-in).
                limit?.let {
                    Text(
                        "Volume limit $it% · set in the Sonos app",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                light?.let { on ->
                    AppButton(onClick = onToggleStatusLight, modifier = Modifier.fillMaxWidth()) {
                        Text(if (on) "Status light   On" else "Status light   Off")
                    }
                }
                // Worded as the Sonos app's Button Control: "On" means the buttons work, which is unlocked.
                locked?.let { isLocked ->
                    AppButton(onClick = onToggleTouchControls, modifier = Modifier.fillMaxWidth()) {
                        Text(if (isLocked) "Touch controls   Off" else "Touch controls   On")
                    }
                }
                // The soundbar's own TV-remote settings, written to the soundbar wherever it sits in the group.
                shownTone?.remote?.let { remote ->
                    AppButton(onClick = onToggleRemoteFeedback, modifier = Modifier.fillMaxWidth()) {
                        Text(if (remote.feedback) "Remote feedback light   On" else "Remote feedback light   Off")
                    }
                    // A third word, which the service documents and nobody here has seen, is said and not toggled.
                    when (remote.irRepeaterOn) {
                        null -> Text(
                            "IR repeater: ${remote.irRepeater}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                        else -> AppButton(onClick = onToggleIrRepeater, modifier = Modifier.fillMaxWidth()) {
                            Text(if (remote.irRepeaterOn == true) "IR repeater   On" else "IR repeater   Off")
                        }
                    }
                }
                if (info.hasTvInput) {
                    // Detection fills this in on its own but has been seen to answer wrongly and
                    // then keep the answer, so it has to be correctable by hand. There is no
                    // un-naming: the crown simply moves to whichever room is named next.
                    AppButton(onClick = onSetTvRoom, modifier = Modifier.fillMaxWidth()) {
                        Text("This is my TV")
                    }
                }
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
 * One room, in either list, as a pill: Select moves it to the other list — a member leaves the
 * group, an outside room joins it — and the mark at its end says which. No level any more
 * (2026-10-17, the owner's call): a speaker's level is the player pane's, and this panel is for
 * which rooms play together.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RoomRow(
    name: String,
    /** "join", "leave", or none — the coordinator has nothing to leave. */
    action: String?,
    onActivate: () -> Unit,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    AppButton(onClick = onActivate, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // What Select does to this row, as a mark that reads across a room: the 12sp words
            // "join" and "leave" at the row's far end were the smallest text in the panel.
            if (action != null) {
                Spacer(Modifier.width(16.dp))
                Icon(
                    imageVector = if (action == "join") Icons.Default.AddCircleOutline else Icons.Default.RemoveCircleOutline,
                    contentDescription = if (action == "join") "Join" else "Leave",
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

/**
 * Bass or treble: left and right step it, as a level row does, since nothing else in the
 * panel uses those directions, and − and + say so while it has focus. By touch those are
 * the two targets. Select does nothing, and nor does a tap between them; the value is the
 * whole row.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ToneRow(label: String, level: Int, onStep: (Int) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shown = stepMarksShown(focused)
    AppButton(
        onClick = {},
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { onStep(-1); true }
                    Key.DirectionRight -> { onStep(+1); true }
                    else -> false
                }
            },
    ) {
        // The marks bracket the value at the end rather than the row, so the label stays in
        // line with Loudness and TruePlay below whether they are drawn or not.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f))
            StepMark("\u2212", shown) { onStep(-1) }
            Text(
                text = if (level > 0) "+$level" else "$level",
                textAlign = TextAlign.Center,
                // Wide enough for "+10", so the marks stay put as the value moves.
                modifier = Modifier.widthIn(min = 36.dp),
            )
            StepMark("+", shown) { onStep(+1) }
        }
    }
}

/** Says a row is a beta feature, at its end: in the content colour, so it shows on a focused row too. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun BetaMark() {
    Text("Beta", style = MaterialTheme.typography.labelLarge, color = LocalContentColor.current.copy(alpha = 0.7f))
}
