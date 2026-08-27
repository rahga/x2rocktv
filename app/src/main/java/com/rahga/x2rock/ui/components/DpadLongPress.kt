package com.rahga.x2rock.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val LONG_PRESS_MILLIS = 600L

/**
 * Hold-to-open-context-menu for a TV remote, which has no long-press gesture of its own: start a
 * timer on key-down of the select key and fire [onLongPress] if it survives; cancel on key-up. The
 * dedicated Menu key fires immediately.
 *
 * The select key is never consumed, so a short press still reaches the underlying clickable.
 */
@Composable
fun Modifier.dpadLongPress(onLongPress: () -> Unit): Modifier {
    val scope = rememberCoroutineScope()
    var pendingLongPress by remember { mutableStateOf<Job?>(null) }

    return onKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.Menu) {
            pendingLongPress?.cancel()
            pendingLongPress = null
            onLongPress()
            return@onKeyEvent true
        }
        if (event.key == Key.DirectionCenter || event.key == Key.Enter) {
            when (event.type) {
                KeyEventType.KeyDown -> if (pendingLongPress == null) {
                    pendingLongPress = scope.launch {
                        delay(LONG_PRESS_MILLIS)
                        pendingLongPress = null
                        onLongPress()
                    }
                }
                KeyEventType.KeyUp -> {
                    pendingLongPress?.cancel()
                    pendingLongPress = null
                }
                else -> {}
            }
        }
        false
    }
}
