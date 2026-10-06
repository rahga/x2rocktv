package com.rahga.x2rock.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent

/**
 * Pins D-pad focus inside a modal, without blocking movement within it.
 *
 * Content behind a dialog stays in the composition and stays focusable, so a D-pad press can walk
 * focus out to a control the user cannot see. The previous guard for that —
 * `onKeyEvent { it.key != Key.Back }` — consumed *every* non-Back key, including the D-pad events
 * Compose's root handler needs in order to move focus at all, so it also froze focus on whichever
 * button opened focused. Cancelling focus *exit* is the targeted version of the same intent.
 *
 * **The properties go before the group.** A focus target collects its properties from the
 * modifiers *above* it in the chain, so `focusGroup().focusProperties { exit }` gave the rule
 * to the group's children and the group itself never saw it: the trap was a no-op, and a press
 * up from Settings walked focus to the sidebar's buttons with the panel still open. Seen on
 * the Shield, 2026-10-03.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.modalFocusTrap(): Modifier =
    focusProperties { exit = { FocusRequester.Cancel } }.focusGroup()

/**
 * Scrim + centred, focus-trapped content. A tap on the scrim is [onDismiss]; Back is each
 * overlay's own `BackHandler`.
 *
 * The scrim has to take taps either way: without a handler of its own a tap passes through it
 * to whichever control is drawn behind, which the viewer cannot see is there.
 */
@Composable
fun Overlay(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .tapToClick(onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier.modalFocusTrap().keepTaps()) {
            content()
        }
    }
}

/**
 * Takes the taps that land on a surface's own background, so they close nothing. A tap goes
 * to the innermost handler under it and then outwards, so one on a dialog's title or padding
 * would otherwise reach the scrim it sits on and dismiss it.
 */
@Composable
fun Modifier.keepTaps(): Modifier = tapToClick(onClick = {})
