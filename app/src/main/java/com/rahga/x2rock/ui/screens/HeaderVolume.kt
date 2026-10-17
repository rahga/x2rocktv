package com.rahga.x2rock.ui.screens

import com.rahga.x2rock.ui.components.SearchField
import com.rahga.x2rock.ui.components.dpadMenuKey
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import com.rahga.x2rock.viewmodel.roomActivity
import com.rahga.x2rock.viewmodel.RoomActivity
import com.rahga.x2rock.viewmodel.hasSource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.MutableState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.rahga.x2rock.model.isPlaying
import com.rahga.x2rock.ui.components.Overlay
import com.rahga.x2rock.ui.components.StepMark
import com.rahga.x2rock.ui.components.mutedInk
import com.rahga.x2rock.ui.components.stepMarksShown
import com.rahga.x2rock.ui.components.tapToClick
import com.rahga.x2rock.ui.theme.AppButton
import com.rahga.x2rock.ui.theme.rememberAutoFocusRequester
import com.rahga.x2rock.ui.theme.requestFocusSafely
import com.rahga.x2rock.viewmodel.PlayerUiState
import com.rahga.x2rock.viewmodel.PlayerViewModel

// The pane's header: the room's name and its volume, the wedge, and the overlay a hold on the
// volume opens to set a level outright. Split from PlayerScreen.kt.
/**
 * The room's name, and opposite it the group's level — identical on the music pane and the
 * TV one, because volume is the one control that means the same thing whatever the room is
 * playing, and it has to sit in the same place and answer the same presses when the source
 * changes.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun PaneHeader(state: PlayerUiState, viewModel: PlayerViewModel, exitLeftFocusRequester: FocusRequester, volumeEntry: VolumeEntry) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The room, and what it is doing in the word the room list and panel use too: a paused
        // stream and an idle room used to look the same here, and the panel called both "Idle".
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
            // The room's name is measured first and kept whole; the state word gives way when space is
            // short. The other way round, "Media Room · Nothing playing" read "Media… · Nothing
            // playing" (Streamer, 2026-10-07).
            Text(
                text = state.groupName,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val activity = roomActivity(state.playbackState, state.hasSource)
            // Not for an empty room: the pane's body already says "No Content", and the room list
            // "Nothing playing" — a third telling of one fact.
            if (activity != RoomActivity.EMPTY) {
                Text(
                    text = "  ·  " + if (state.onTvInput && activity.isPlaying) "TV" else activity.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (activity.isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        HeaderVolume(
            state,
            onStep = { viewModel.stepVolume(it) },
            onToggleMute = { viewModel.toggleMute() },
            onExitLeft = { exitLeftFocusRequester.requestFocusSafely() },
            entry = volumeEntry,
        )
    }
}

/**
 * Speaker, wedge, level: one control. Select mutes; left and right step the level while it
 * has focus, with − and + drawn either side to say so; a hold or Menu sets an exact level. Deliberately small — the remote's own
 * volume keys are the television's (see CLAUDE.md), and the system draws its own display
 * for those, so this is for the room rather than a second volume screen.
 *
 * Left steps down rather than leaving for the room list, as the room panel's levels do; up
 * and down move on. The − and + keep their space when hidden, so focusing the control
 * does not shift the room name. By touch they are always shown, and are their own targets;
 * a tap anywhere else on the control mutes.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HeaderVolume(
    state: PlayerUiState,
    onStep: (Int) -> Unit,
    onToggleMute: () -> Unit,
    onExitLeft: () -> Unit,
    entry: VolumeEntry,
) = VolumePill(
    volume = state.volume,
    muted = state.isMuted,
    fixed = state.volumeFixed,
    onStep = onStep,
    onToggleMute = onToggleMute,
    onExitLeft = onExitLeft,
    onSetExactly = entry.open,
    modifier = Modifier.focusRequester(entry.focus),
    onFocused = { entry.focused.value = it },
)

/**
 * The volume control as one pill, the room's in the header and each speaker's in a grouped room's
 * rows alike, so a level reads and answers the same wherever it is. See [HeaderVolume] for the
 * presses. [onSetExactly], where given, is a hold's or Menu's.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun VolumePill(
    volume: Int?,
    muted: Boolean,
    fixed: Boolean,
    /** One press, -1 or +1; the view model sizes it. */
    onStep: (Int) -> Unit,
    onToggleMute: () -> Unit,
    onExitLeft: () -> Unit,
    modifier: Modifier = Modifier,
    onSetExactly: (() -> Unit)? = null,
    onFocused: (Boolean) -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    // A fixed line-out has no level to step: the view model says so if a step is tried.
    val showSteps = !fixed && stepMarksShown(focused)
    // A hold, or Menu, sets an exact level — where stepping there by fives would take a while.
    // Not for a fixed line-out, which has no level to set.
    val setExactly = onSetExactly.takeIf { !fixed }
    Surface(
        onClick = onToggleMute,
        onLongClick = setExactly,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        // Focus shows in its colour; tv-material3's grow on focus pushed it past its own row.
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        modifier = modifier
            .then(if (setExactly != null) Modifier.dpadMenuKey(setExactly) else Modifier)
            .onFocusChanged { focused = it.isFocused; onFocused(it.isFocused) }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    // A fixed line-out has no level to step, so left does what it does from any
                    // row's leftmost control: back to the room list. Otherwise every press only
                    // posted the "fixed" notice and the remote stayed put.
                    Key.DirectionLeft -> { if (fixed) onExitLeft() else onStep(-1); true }
                    Key.DirectionRight -> { onStep(+1); true }
                    else -> false
                }
            }
            .tapToClick(onToggleMute),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StepMark("\u2212", showSteps, MaterialTheme.typography.titleMedium) { onStep(-1) }
            Icon(
                imageVector = if (muted) Icons.AutoMirrored.Filled.VolumeOff
                else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = if (muted) "Unmute" else "Mute",
                modifier = Modifier.size(30.dp),
            )
            if (!fixed) VolumeWedge(volume, muted)
            // The size a level is read at across a room: the remote's own keys go to the
            // television over CEC, so this is the one level on screen for the room itself.
            val levelStyle = MaterialTheme.typography.headlineSmall
            // Exactly as wide as "100", so the control does not change size as the level moves and
            // "100" sits as close to + as − does to the speaker; a shorter level is centred in it.
            // A fixed 52dp left "100" with space to spare before +.
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val levelWidth = remember(levelStyle, density) {
                with(density) { measurer.measure("100", levelStyle).size.width.toDp() }
            }
            Text(
                text = when {
                    fixed -> "Fixed"
                    volume == null -> "\u2014"
                    else -> volume.toString()
                },
                style = levelStyle,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = levelWidth),
            )
            StepMark("+", showSteps, MaterialTheme.typography.titleMedium) { onStep(+1) }
        }
    }
}

/**
 * The level as a wedge rising left to right, filled up to it — the shape Android's own volume
 * display uses. In the content colour, so it inverts with the focused container as the room
 * panel's bars do; dimmed while muted, keeping the level the speaker remembers.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun VolumeWedge(volume: Int?, muted: Boolean) {
    val ink = mutedInk(muted)
    Canvas(modifier = Modifier.size(width = 84.dp, height = 28.dp)) {
        val wedge = Path().apply {
            moveTo(0f, size.height)
            lineTo(size.width, 0f)
            lineTo(size.width, size.height)
            close()
        }
        drawPath(wedge, ink.copy(alpha = ink.alpha * 0.25f))
        if (volume != null && volume > 0) {
            clipRect(right = size.width * volume / 100f) { drawPath(wedge, ink) }
        }
    }
}


internal class VolumeEntry(
    val open: () -> Unit,
    val focus: FocusRequester,
    /** Whether the header volume has focus; the art names its service while it does. */
    val focused: MutableState<Boolean>,
)

/**
 * An exact level for the room, opened by a hold on its volume: a number typed or said on the
 * keyboard, which comes up for digits, or one of the levels most often wanted, picked with no
 * typing at all. Anything outside 0 to 100 is not sent, and says why.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SetVolumeOverlay(room: String, current: Int?, trapped: Boolean, onSet: (Int) -> Unit, onDismiss: () -> Unit) {
    BackHandler { onDismiss() }
    var text by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    val fieldFocus = rememberAutoFocusRequester()
    val submit = {
        when (val level = text.trim().toIntOrNull()) {
            null -> problem = "Type a level from 0 to 100"
            !in 0..100 -> problem = "$level is past 100; a level goes from 0 to 100"
            else -> onSet(level)
        }
    }
    Overlay(onDismiss, trapped) {
        Column(
            modifier = Modifier
                .width(520.dp)
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Volume in $room", style = MaterialTheme.typography.titleLarge)
            current?.let { Text("Now $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SearchField(
                    value = text,
                    placeholder = "0 to 100",
                    onValueChange = { typed -> text = typed.filter { it.isDigit() }.take(3); problem = null },
                    onSearch = submit,
                    numeric = true,
                    modifier = Modifier.weight(1f).focusRequester(fieldFocus),
                )
                Spacer(Modifier.width(12.dp))
                AppButton(onClick = submit) { Text("Set") }
            }
            problem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QUICK_LEVELS.forEach { level ->
                    AppButton(onClick = { onSet(level) }, modifier = Modifier.weight(1f)) {
                        Text("$level", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

/** The levels a room is most often set to, as one press each: quiet to loud, short of loud. */
private val QUICK_LEVELS = listOf(5, 10, 15, 20, 25, 30, 40)

/** "15 minutes", "1 hour", "1½ hours", "2 hours". */
