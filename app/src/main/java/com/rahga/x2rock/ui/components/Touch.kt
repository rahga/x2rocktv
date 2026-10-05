package com.rahga.x2rock.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * A tap as a press, for anything with touch or a mouse. tv-material3's clickables answer only
 * the remote's select key — a tap on one does nothing at all — so each of them takes this as
 * well. A remote never sends a tap, so on a television nothing changes.
 *
 * The detector is started once and reads the latest lambdas, rather than being keyed on them:
 * the player pane recomposes on every position tick, and a restart there would cancel a hold
 * half-way through.
 */
@Composable
fun Modifier.tapToClick(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier {
    val tap by rememberUpdatedState(onClick)
    val hold by rememberUpdatedState(onLongClick)
    return pointerInput(Unit) {
        detectTapGestures(
            onTap = { tap() },
            // Without a hold of its own, a hold is a slow tap, as it is on a phone's buttons.
            onLongPress = { (hold ?: tap)() },
        )
    }
}
