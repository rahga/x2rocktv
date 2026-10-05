package com.rahga.x2rock.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
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
