package com.rahga.x2rock.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color

/**
 * Pins D-pad focus inside a modal, without blocking movement within it.
 *
 * Content behind a dialog stays in the composition and stays focusable, so a D-pad press can walk
 * focus out to a control the user cannot see. The previous guard for that —
 * `onKeyEvent { it.key != Key.Back }` — consumed *every* non-Back key, including the D-pad events
 * Compose's root handler needs in order to move focus at all, so it also froze focus on whichever
 * button opened focused. Cancelling focus *exit* is the targeted version of the same intent.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.modalFocusTrap(): Modifier =
    focusGroup().focusProperties { onExit = { cancelFocusChange() } }

/** Scrim + centred, focus-trapped content. */
@Composable
fun Overlay(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier.modalFocusTrap()) {
            content()
        }
    }
}
