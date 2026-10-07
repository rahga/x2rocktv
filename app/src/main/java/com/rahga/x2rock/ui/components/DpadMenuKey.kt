package com.rahga.x2rock.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import com.rahga.x2rock.ui.theme.requestFocusSafely
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

/**
 * The remote's Menu key as a row's second action: what a hold reaches too.
 *
 * The hold itself is tv-material3's own `onLongClick`, which fires on the select key's first
 * repeat and then withholds `onClick` on release. This used to time the hold here as well, and
 * let the release through to the Card — so holding a playlist row appended it to the queue and,
 * on release, replaced the queue with it. Where a hold opened an overlay the stolen focus hid
 * that; where it only posted a notice it did not.
 */
fun Modifier.dpadMenuKey(onMenu: () -> Unit): Modifier = onKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown && event.key == Key.Menu) {
        onMenu()
        true
    } else false
}

/**
 * A press of [key] moves focus to [target] and is consumed — how the room list and the player pane
 * cross into each other, since a focus property on the controls' containers does not govern the
 * search (see CLAUDE.md, "Focus, and why it is handled as keys").
 */
fun Modifier.exitOnKey(key: Key, target: FocusRequester): Modifier = onKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown && event.key == key) {
        target.requestFocusSafely()
        true
    } else false
}
