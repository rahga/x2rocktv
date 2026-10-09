package com.rahga.x2rock.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A playing room's mark: a hi-fi's LED level meter, four columns of dots on a flat bottom. The
 * bottom row is always lit, so the baseline reads as one line; unlit dots stay faintly drawn so
 * the grid keeps its shape.
 *
 * Quiet on purpose. Columns glide between levels, the top dot fading in and out, rather than
 * stepping: stepped at a quarter second it read as a low frame rate (Streamer, 2026-10-08). It is
 * drawn smaller and fainter than the other room glyphs, because it moves and they do not.
 *
 * Decoration, not a meter: the player sends no levels, so the columns move to a fixed pattern.
 * Only the draw phase reads the clock, so a row playing costs a redraw a frame, not a recomposition.
 */
@Composable
fun DotEqualizer(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp, description: String? = null) {
    val clock by rememberInfiniteTransition(label = "equalizer").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(LOOP_MILLIS, easing = LinearEasing)),
        label = "equalizer clock",
    )
    Canvas(modifier.size(size).semantics { description?.let { contentDescription = it } }) {
        val pitch = this.size.width / COLUMNS
        val rowPitch = this.size.height / ROWS
        val dot = minOf(pitch, rowPitch) * 0.62f
        val corner = CornerRadius(dot * 0.3f)
        val lit = color.copy(alpha = color.alpha * LIT_ALPHA)
        val unlit = color.copy(alpha = color.alpha * UNLIT_ALPHA)
        LEVELS.forEachIndexed { column, levels ->
            val position = clock * levels.size
            val step = position.toInt().coerceAtMost(levels.lastIndex)
            val t = (position - step).let { it * it * (3 - 2 * it) } // smoothstep: eases at each level
            val level = levels[step] + (levels[(step + 1) % levels.size] - levels[step]) * t
            for (row in 0 until ROWS) { // row 0 is the bottom
                val on = (level - row).coerceIn(0f, 1f)
                drawRoundRect(
                    color = if (on >= 1f) lit else if (on <= 0f) unlit else lerp(unlit, lit, on),
                    topLeft = Offset(column * pitch + (pitch - dot) / 2, this.size.height - (row + 1) * rowPitch + (rowPitch - dot) / 2),
                    size = Size(dot, dot),
                    cornerRadius = corner,
                )
            }
        }
    }
}

private const val COLUMNS = 4
private const val ROWS = 5
private const val LIT_ALPHA = 0.8f
private const val UNLIT_ALPHA = 0.14f

/** Each column's levels over one loop. Different lengths, so the columns move out of time. */
private val LEVELS = listOf(
    intArrayOf(3, 5, 4, 2, 4, 5, 3, 2),
    intArrayOf(5, 3, 4, 5, 2, 3, 4),
    intArrayOf(2, 4, 5, 3, 5, 2, 4, 3, 5, 1),
    intArrayOf(4, 2, 3, 4, 1, 3, 2, 5, 3),
)

/** About half a second from one level to the next. */
private const val LOOP_MILLIS = 4_400
