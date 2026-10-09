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
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import kotlin.math.PI
import kotlin.math.cos

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

/**
 * Loading, in the equalizer's dots: one row of larger blocks with a lit head sweeping back and
 * forth and a fading trail behind it, KITT's scanner. Easing at each end, where the head turns,
 * as the original's did. Like [DotEqualizer], only the draw phase reads the clock.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DotScanner(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
    description: String = "Loading",
) {
    val clock by rememberInfiniteTransition(label = "scanner").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SCAN_MILLIS, easing = LinearEasing)),
        label = "scanner clock",
    )
    Canvas(
        modifier
            .size(width = SCAN_BLOCK * SCAN_BLOCKS + SCAN_GAP * (SCAN_BLOCKS - 1), height = SCAN_BLOCK)
            .semantics { contentDescription = description }
    ) {
        val block = SCAN_BLOCK.toPx()
        val pitch = block + SCAN_GAP.toPx()
        val outward = clock < 0.5f
        val sweep = if (outward) clock * 2 else 2 - clock * 2
        val head = (1 - cos(PI * sweep).toFloat()) / 2 * (SCAN_BLOCKS - 1)
        val direction = if (outward) 1 else -1
        for (i in 0 until SCAN_BLOCKS) {
            val behind = (head - i) * direction // how far behind the head this block is; negative is ahead
            val glow = (if (behind >= 0) 1 - behind / SCAN_TRAIL else 1 + behind / SCAN_LEAD).coerceIn(0f, 1f)
            drawRoundRect(
                // Squared, so the trail falls away quickly behind a bright head rather than evenly.
                color = lerp(color.copy(alpha = color.alpha * UNLIT_ALPHA), color, glow * glow),
                topLeft = Offset(i * pitch, 0f),
                size = Size(block, block),
                cornerRadius = CornerRadius(block * 0.2f),
            )
        }
    }
}

private const val SCAN_BLOCKS = 8
private val SCAN_BLOCK = 12.dp
private val SCAN_GAP = 5.dp
/** How many blocks the trail fades over, behind the head, and how sharply it lights ahead of it. */
private const val SCAN_TRAIL = 3f
private const val SCAN_LEAD = 0.7f
/** There and back. */
private const val SCAN_MILLIS = 1_800
