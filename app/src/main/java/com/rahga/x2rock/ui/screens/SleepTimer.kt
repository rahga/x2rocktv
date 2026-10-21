package com.rahga.x2rock.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.rahga.x2rock.ui.theme.IconAppButton
import androidx.compose.ui.platform.LocalContext
import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.path
import androidx.compose.foundation.layout.Spacer
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
import com.rahga.x2rock.ui.theme.rememberAutoFocusRequester
import com.rahga.x2rock.viewmodel.PlayerUiState
import com.rahga.x2rock.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay

// The sleep timer's lengths and labels, and the countdown Configure's button shows. Split from
// PlayerScreen.kt.
/** The sleep timer's lengths, as the Sonos app offers: up to two hours, since a film outlasts sixty minutes. */
internal val SLEEP_CHOICES = listOf(15, 30, 45, 60, 90, 120)

/** "15 minutes", "1 hour", "1½ hours", "2 hours". */
internal fun sleepChoiceLabel(minutes: Int): String = when {
    minutes < 60 -> "$minutes minutes"
    minutes == 60 -> "1 hour"
    minutes % 60 == 0 -> "${minutes / 60} hours"
    else -> "${minutes / 60}\u00BD hours"
}

/**
 * The pane's Configure place, where Sleep timer was (2026-10-17): it opens the room's Configure
 * panel — Source, Sleep timer, Sound, Room settings. While a sleep timer runs it says when it ends,
 * as the Sleep button did, since nothing else on the pane would: "Configure · 8:58 PM".
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ConfigureButton(state: PlayerUiState, onClick: () -> Unit, focusRequester: FocusRequester) {
    val endsAt = rememberSleepEnd(state.sleepTimerEndsAt.takeIf { !state.upnpOff })
    Named(if (endsAt != null) "Configure · sleep at $endsAt" else "Configure") { named ->
        IconAppButton(onClick = onClick, modifier = Modifier.focusRequester(focusRequester).then(named)) {
            Icon(Icons.Filled.Tune, contentDescription = "Configure", modifier = Modifier.size(30.dp))
            if (endsAt != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(SleepIcon, contentDescription = "Sleep timer ends at $endsAt", modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(endsAt, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
            }
        }
    }
}

/**
 * The clock time a running sleep timer ends, in the device's own 12- or 24-hour form, or null for
 * none — what someone going to bed is asking. It does not tick.
 */
@Composable
internal fun rememberSleepEnd(endsAt: Long?): String? {
    val left = rememberSleepCountdown(endsAt) ?: return null
    val context = LocalContext.current
    return remember(endsAt) {
        android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(System.currentTimeMillis() + left))
    }
}

/**
 * The sleep timer as "Zzz", drawn: Material has nothing that means sleep and nothing else.
 * The moon is Night Sound's — the Sonos app's symbol for it, and on the TV pane the two sit a
 * row apart — and Snooze's alarm clock is already the ringing alarm's Snooze button.
 */
internal val SleepIcon: ImageVector =
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
