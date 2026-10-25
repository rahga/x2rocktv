package com.rahga.x2rock.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.offset
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.tv.material3.Text
import com.rahga.x2rock.model.isPlaying
import com.rahga.x2rock.ui.components.tapToClick
import com.rahga.x2rock.viewmodel.PlayerUiState
import com.rahga.x2rock.viewmodel.positionNow
import kotlinx.coroutines.delay

// The pane's seek bar: the progress line, the knob and the burst of steps a held key makes, and
// the time labels under it. Split from PlayerScreen.kt, where PlaybackControls draws it.
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ProgressBar(
    state: PlayerUiState,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onFocused: (Boolean) -> Unit = {},
    /** The bar stopped taking focus while it had it: see the effect on `seekable` below. */
    onLeave: () -> Unit = {},
    /** "Up next · …" or "Previous · …", drawn between the times while set: see PlayerPane's nextFocused. */
    caption: String? = null,
) {
    // Always laid out, blank where there is nothing to show: a station, a TV input or an empty
    // room has no duration, and closing the slot up moved every row below it as the viewer
    // walked the room list. The pane above the transport keeps one shape whatever a room plays.
    val hasDuration = state.durationMillis > 0
    // Seekable only where the player says so. `canSeek` is per content, not per service: an
    // iHeartRadio artist station's track carries a duration and refuses a seek with
    // ERROR_DISALLOWED_BY_POLICY, where the same service's on-demand tracks seek fine. The player
    // reports it with every status, so a bar that cannot seek is a plain progress line — not a
    // focus stop that answers a press with an error.
    val seekable = hasDuration && state.actions.canSeek

    val displayPositionMillis by rememberLivePosition(state.positionMillis, state.positionUpdatedAt, state.playbackState, state.durationMillis)

    val progress = if (hasDuration) (displayPositionMillis.toFloat() / state.durationMillis).coerceIn(0f, 1f) else 0f
    var isFocused by remember { mutableStateOf(false) }

    // The running total of a burst of seek presses, shown under the bar and faded out once the
    // presses stop, so a viewer holding left can see how far back they are going. Clamped to
    // the track, as the seek itself is; a fresh burst starts from nothing.
    var seekTotal by remember { mutableLongStateOf(0L) }
    var seekFrom by remember { mutableLongStateOf(0L) }
    var seekPresses by remember { mutableIntStateOf(0) }
    var seekShown by remember { mutableStateOf(false) }
    // Presses so far in this burst: the step climbs 10s, 20s, then 30s for as long as the presses
    // keep coming, so a nudge stays a nudge and a long way back is still quick. A pause long enough
    // for the total to fade starts the next burst at 10s again.
    var burstSteps by remember { mutableIntStateOf(0) }
    val seekAlpha by animateFloatAsState(if (seekShown) 1f else 0f, animationSpec = tween(400), label = "seekTotal")
    LaunchedEffect(seekPresses) {
        if (seekPresses == 0) return@LaunchedEffect
        delay(SEEK_TOTAL_SHOWN_MILLIS)
        seekShown = false
        burstSteps = 0
    }
    // A burst of presses or taps is measured from where its first one began.
    fun showSeek(total: (from: Long) -> Long) {
        if (!seekShown) seekFrom = displayPositionMillis
        seekTotal = total(seekFrom)
        seekShown = true
        seekPresses++
    }
    fun seek(direction: Int) {
        val delta = direction * SEEK_STEPS_MILLIS[burstSteps.coerceAtMost(SEEK_STEPS_MILLIS.lastIndex)]
        burstSteps++
        showSeek { from -> ((if (seekShown) seekTotal else 0L) + delta).coerceIn(-from, state.durationMillis - from) }
        onSeekBy(delta)
    }
    // A tap aims at a point rather than stepping, so it is sent as one; the total under the
    // bar still says how far that is from where the tap began.
    fun seekToFraction(fraction: Float) {
        val target = (fraction.coerceIn(0f, 1f) * state.durationMillis).toLong()
        showSeek { from -> target - from }
        onSeekTo(target)
    }
    val tapAt by rememberUpdatedState(::seekToFraction)
    val barColor = if (isFocused) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary

    // Before focusable(), not after: onFocusChanged sees focus on what follows it, and placed
    // after it never saw the bar take focus — so the bar never changed colour and its hint never
    // showed, and landing on it looked like landing on nothing.
    // Touch and mouse, for anything that has them — the emulator, a tablet. A remote never sends
    // a tap, so on a TV this is inert. The strip is taller than the bar drawn in it, or an 8dp
    // line would be a hard thing to hit. Started once and reading the latest handler, as
    // tapToClick does: the displayed position is a new state at every report, and a stale
    // handler measured from an old one.
    // `canSeek` is per content, so it can go under the viewer — the next track an artist station's —
    // and the bar stops being focusable with focus on it. That drops focus, and the platform handed it
    // to the first room row (P-102). Said once, as it goes.
    val watch = remember { LeaveWatch() }
    LaunchedEffect(seekable) {
        // Focused still, or lost with the change: Compose may report the loss as the bar lets go.
        if (!seekable && (watch.had || watch.lostJustNow())) {
            watch.changed(false)
            isFocused = false
            onFocused(false)
            onLeave()
        }
    }
    val seekControls = if (!seekable) Modifier else Modifier
        .onFocusChanged {
            watch.changed(it.isFocused)
            isFocused = it.isFocused
            onFocused(it.isFocused)
        }
        .focusable()
        .pointerInput(Unit) {
            detectTapGestures { offset -> tapAt(offset.x / size.width) }
        }
    val seekKeys = if (!seekable) Modifier else Modifier.onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        when (event.key) {
            Key.DirectionLeft -> { seek(-1); true }
            Key.DirectionRight -> { seek(+1); true }
            else -> false
        }
    }

    Column(modifier = Modifier.alpha(if (hasDuration) 1f else 0f)) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .then(seekControls)
                .padding(vertical = 12.dp)
                .height(SEEK_KNOB)
                .then(seekKeys)
        ) {
            // An 8dp bar recolouring to the focus tint was too faint to find across a room — a
            // press up from the transport read as focus vanishing. Focused, the bar grows and a
            // knob sits at the playhead, so where focus is and where a seek starts are unmistakable.
            // Both inside a strip already the knob's height, so taking focus moves nothing else on
            // the pane: the strip used to grow by the knob and nudge every row.
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.align(Alignment.CenterStart).fillMaxWidth().height(if (isFocused) 12.dp else 8.dp),
                color = barColor,
                // The theme's own, not material3's: no material3 theme is set, so its default track
                // was that library's light-baseline grey, the brightest thing on a dark pane (P-01).
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            if (isFocused) {
                val knob = SEEK_KNOB
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = (maxWidth - knob) * progress)
                        .size(knob)
                        .clip(CircleShape)
                        .background(barColor)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(displayPositionMillis.toTimeString(), style = MaterialTheme.typography.bodySmall)
            // The total's slot is always laid out, invisible when there is nothing to say: it is
            // taller than the times beside it, and appearing only on a press made the row grow
            // and nudged every row below it down the screen.
            // Weighted, so a long caption ellipsizes between the times rather than over them.
            Box(contentAlignment = Alignment.Center, modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                if (seekAlpha == 0f && !isFocused && caption != null) {
                    Text(
                        caption,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    seekTotal.toSeekLabel(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.alpha(seekAlpha),
                )
                if (seekAlpha == 0f && isFocused) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val hint = MaterialTheme.colorScheme.secondary
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, tint = hint, modifier = Modifier.size(20.dp))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = hint, modifier = Modifier.size(20.dp))
                        Text(" seek", style = MaterialTheme.typography.bodySmall, color = hint)
                    }
                }
            }
            Text(state.durationMillis.toTimeString(), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** How long a seek burst's total stays up after the last press, before it fades. */
private const val SEEK_TOTAL_SHOWN_MILLIS = 1_500L

/** The seek bar's knob, and the height its strip always keeps. */
private val SEEK_KNOB = 20.dp

/** The step of each press in a burst of seeks, the last repeating: see the seek bar's burstSteps. */
private val SEEK_STEPS_MILLIS = longArrayOf(10_000L, 20_000L, 30_000L)

/** A seek total as a viewer reads it: −30s, +1:00, −1:30. */
internal fun Long.toSeekLabel(): String {
    val sign = if (this < 0) "−" else "+"
    val seconds = kotlin.math.abs(this) / 1_000
    return if (seconds < 60) "$sign${seconds}s" else sign + (seconds * 1_000).toTimeString()
}

/**
 * Where a playing track is now: the last report plus the time since it. The report alone put a bar
 * composed after it — the pane on the way back from any screen, the full-screen view — behind by
 * however long ago the player last spoke, until it spoke again (Shield, 2026-10-10).
 */
internal fun livePosition(positionMillis: Long, updatedAt: Long, playbackState: String, durationMillis: Long): Long =
    positionNow(positionMillis, updatedAt, playbackState.isPlaying(), durationMillis)

/** [livePosition], counting on a second at a time while the room plays, until the player next reports. */
@Composable
internal fun rememberLivePosition(positionMillis: Long, updatedAt: Long, playbackState: String, durationMillis: Long): State<Long> {
    val position = remember(updatedAt) { mutableLongStateOf(livePosition(positionMillis, updatedAt, playbackState, durationMillis)) }
    // The duration as it is now, not as the loop began: a new track's metadata can arrive after
    // its first status, which moved the stamp and restarted this with the old track's length.
    val duration by rememberUpdatedState(durationMillis)
    LaunchedEffect(updatedAt, playbackState) {
        if (playbackState.isPlaying()) while (true) {
            delay(1_000L)
            val next = position.longValue + 1_000L
            position.longValue = if (duration > 0) next.coerceAtMost(duration) else next
        }
    }
    return position
}

internal fun Long.toTimeString(): String {
    val totalSeconds = (this / 1_000).coerceAtLeast(0)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(java.util.Locale.ROOT, hours, minutes, seconds)
           else "%d:%02d".format(java.util.Locale.ROOT, minutes, seconds)
}
