package com.rahga.x2rock.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.rahga.x2rock.ui.theme.IconAppButton
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.path
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.rahga.x2rock.ui.components.Overlay
import com.rahga.x2rock.ui.theme.AppButton
import com.rahga.x2rock.ui.theme.rememberAutoFocusRequester
import com.rahga.x2rock.viewmodel.PlayerUiState
import com.rahga.x2rock.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay

// The sleep timer's button, its icon, the countdown it shows and the picker it opens. Split from
// PlayerScreen.kt, where both control rows place it.
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SleepTimerPickerOverlay(
    trapped: Boolean,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler { onDismiss() }
    val firstFocus = rememberAutoFocusRequester()

    Overlay(onDismiss, trapped) {
        Column(
            modifier = Modifier
                .width(440.dp)
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Sleep Timer", style = MaterialTheme.typography.titleLarge)
            // Up to two hours, as the Sonos app offers: a film or an album outlasts sixty minutes.
            // In pairs, so six choices fit the screen's height with Cancel still on it.
            SLEEP_CHOICES.chunked(2).forEachIndexed { row, pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEachIndexed { column, minutes ->
                        AppButton(
                            onClick = { onSelect(minutes) },
                            modifier = Modifier
                                .weight(1f)
                                .then(if (row == 0 && column == 0) Modifier.focusRequester(firstFocus) else Modifier)
                        ) {
                            Text(sleepChoiceLabel(minutes), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            AppButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
        }
    }
}

private val SLEEP_CHOICES = listOf(15, 30, 45, 60, 90, 120)

/** What the volume control needs to offer an exact level: how to open the entry, and its own focus. */
internal fun sleepChoiceLabel(minutes: Int): String = when {
    minutes < 60 -> "$minutes minutes"
    minutes == 60 -> "1 hour"
    minutes % 60 == 0 -> "${minutes / 60} hours"
    else -> "${minutes / 60}\u00BD hours"
}

/** Each speaker's own level, for a group of more than one. Drawn under both panes' controls. */
/**
 * The sleep timer's one button: what is left and a cancel while it runs, the picker otherwise.
 * Sonos's own timer, on AVTransport: with UPnP off there is none to set, and no button.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SleepTimerButton(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    onOpenSleepTimer: () -> Unit,
    focusRequester: FocusRequester,
) {
    if (state.upnpOff) return
    val sleepLeft = rememberSleepCountdown(state.sleepTimerEndsAt)
    val context = LocalContext.current
    Named(if (sleepLeft != null) "Cancel sleep timer" else "Sleep timer") { named ->
        IconAppButton(
            onClick = { if (sleepLeft != null) viewModel.cancelSleepTimer() else onOpenSleepTimer() },
            modifier = Modifier.focusRequester(focusRequester).then(named),
        ) {
            Icon(
                imageVector = SleepIcon,
                contentDescription = if (sleepLeft != null) "Sleep timer, ${sleepLeft.toTimeString()} left. Press to cancel"
                else "Sleep timer",
                modifier = Modifier.size(30.dp),
            )
            if (sleepLeft != null) {
                Spacer(modifier = Modifier.width(8.dp))
                // The clock time it ends, which is what someone going to bed is asking — in the device's
                // own 12- or 24-hour form. One short line: "14:34 · ends 8:58 PM" wrapped to four lines
                // in the pane's button row at 1080p (Streamer, 2026-10-06). What is left stays in the
                // description, and the time it ends does not tick.
                val endsAt = remember(state.sleepTimerEndsAt) {
                    android.text.format.DateFormat.getTimeFormat(context)
                        .format(java.util.Date(System.currentTimeMillis() + sleepLeft))
                }
                Text(endsAt, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
            }
        }
    }
}

/**
 * The sleep timer as "Zzz", drawn: Material has nothing that means sleep and nothing else.
 * The moon is Night Sound's — the Sonos app's symbol for it, and on the TV pane the two sit a
 * row apart — and Snooze's alarm clock is already the ringing alarm's Snooze button.
 */
private val SleepIcon: ImageVector =
    ImageVector.Builder(
        name = "Sleep", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        // Three Zs, each smaller and higher than the last, with clear space between them —
        // touching, they read as a staircase. The strokes thin with them so the smallest is
        // still a Z at three metres rather than a blot.
        zee(left = 1.5f, top = 16f, right = 7.5f, bottom = 22f, weight = 1.7f)
        zee(left = 11f, top = 9f, right = 15f, bottom = 13f, weight = 1.4f)
        zee(left = 18f, top = 2.5f, right = 21f, bottom = 5.5f, weight = 1.2f)
    }.build()

private fun ImageVector.Builder.zee(left: Float, top: Float, right: Float, bottom: Float, weight: Float) {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = weight, strokeLineJoin = StrokeJoin.Miter) {
        moveTo(left, top)
        lineTo(right, top)
        lineTo(left, bottom)
        lineTo(right, bottom)
    }
}

/**
 * What is left on the sleep timer, ticking once a second here: the view model holds the moment
 * it ends and no clock. Null for no timer, or one that has run out — the speaker stops the room
 * then, and the view model re-reads on that event. On the monotonic clock the view model used,
 * so an NTP step after boot cannot make a running timer read as expired.
 */
@Composable
private fun rememberSleepCountdown(endsAt: Long?): Long? {
    var left by remember(endsAt) { mutableStateOf(endsAt?.let { it - SystemClock.elapsedRealtime() }?.takeIf { it > 0 }) }
    LaunchedEffect(endsAt) {
        if (endsAt == null) return@LaunchedEffect
        while (true) {
            val remaining = endsAt - SystemClock.elapsedRealtime()
            left = remaining.takeIf { it > 0 }
            if (remaining <= 0) break
            delay(1_000L)
        }
    }
    return left
}

/** How long a seek burst's total stays up after the last press, before it fades. */
