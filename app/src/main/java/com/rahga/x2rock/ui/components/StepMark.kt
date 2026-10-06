package com.rahga.x2rock.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.Text

/**
 * − or + beside a level that left and right step: drawn while the control has focus, so the
 * remote's two directions are seen to mean something, and by touch, where each is the way to
 * step it. Always laid out, so showing them moves nothing, and a tap target only while shown —
 * the padding is inside the target, because a bare glyph is too small for a finger.
 */
@Composable
fun StepMark(mark: String, shown: Boolean, style: TextStyle = LocalTextStyle.current, onStep: () -> Unit) {
    Text(
        text = mark,
        style = style,
        modifier = Modifier
            .alpha(if (shown) 1f else 0f)
            .then(if (shown) Modifier.tapToClick(onStep) else Modifier)
            .padding(horizontal = 8.dp),
    )
}

/**
 * Whether [StepMark]s are drawn: while their control has focus, and always by touch, where they
 * are the way to step it.
 */
@Composable
fun stepMarksShown(focused: Boolean): Boolean =
    focused || LocalInputModeManager.current.inputMode == InputMode.Touch

/** The step a remote press moves a volume by, in the player pane's header and the room panel alike. */
const val VOLUME_STEP = 5

/**
 * A level's ink, dimmed while muted: the speaker keeps its level through mute, and so does what
 * draws it. In the content colour, so it inverts with a focused container.
 */
@Composable
fun mutedInk(muted: Boolean): Color =
    LocalContentColor.current.let { if (muted) it.copy(alpha = it.alpha * 0.4f) else it }
