package com.rahga.x2rock.ui.screens

import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.rahga.x2rock.ui.theme.IconAppButton
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.offset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.ThumbDown as ThumbDownOutlined
import androidx.compose.material.icons.outlined.ThumbUp as ThumbUpOutlined
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.rahga.x2rock.ui.theme.AppButton

// The pane's buttons: a place named under its icon while focused, Group, the mode toggles, the
// rating buttons, and the overhang that keeps an end button's name whole. Split from PlayerScreen.kt.

/** A setting and the value it currently holds, the way the Sonos app draws these two. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TwoLineButton(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppButton(onClick = onClick, modifier = modifier.widthIn(min = 220.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * The room panel's grouping half, from the pane: who is playing together (the room's settings are
 * Configure's). Select on a room enters this pane, as every list on a TV does, so the panel needed
 * a way in a viewer can see; a hold on the room picks it up to move it instead (P-20).
 */
@Composable
internal fun GroupButton(onClick: () -> Unit, focusRequester: FocusRequester) =
    PlaceButton(Icons.Filled.SpeakerGroup, "Group", onClick, Modifier.focusRequester(focusRequester))

/** How far a [Named] name may reach past either end of its row: "Cancel sleep timer" needs most. */
internal val LABEL_OVERHANG = 48.dp

/**
 * Measure [by] wider on each side than offered and sit that much further left, so whatever this
 * draws — and clips — reaches past both edges of its slot equally while the layout around it is
 * unchanged.
 */
internal fun Modifier.overhang(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx()
    val placeable = measurable.measure(constraints.offset(horizontal = 2 * extra))
    layout((placeable.width - 2 * extra).coerceAtLeast(0), placeable.height) { placeable.place(-extra, 0) }
}

/** A place to go from the pane, as an icon: [Named] says which while it has focus. */
@Composable
internal fun PlaceButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) =
    Named(label) { named ->
        IconAppButton(onClick = onClick, modifier = modifier.then(named)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(30.dp))
        }
    }

/**
 * A button with its name under it, shown only while it has focus — the way a TV's player
 * controls name their icons. The name takes no room of its own, either way: no width, so a long
 * one never spreads the row, and no height, so the row is its buttons' height and the pane fits
 * the screen. Reserving its height made the pane a little taller than the screen, and it then
 * scrolled whenever focus reached the seek bar or the volume. It is drawn in the gap below the
 * row, which the pane's spacing (20dp) and padding (32dp) already leave.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun Named(label: String, button: @Composable (Modifier) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(contentAlignment = Alignment.BottomCenter) {
        button(Modifier.onFocusChanged { focused = it.hasFocus })
        if (focused) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.layout { measurable, _ ->
                    val text = measurable.measure(Constraints())
                    layout(0, 0) { text.place(-text.width / 2, 0) }
                },
            )
        }
    }
}


/**
 * A play mode as an icon. Its state is in the glyph's shape — Material's "on" forms sit in a
 * filled square — rather than in colour alone, which a focused button's own colour would hide.
 */
@Composable
internal fun ModeButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconAppButton(onClick = onClick, modifier = modifier) {
        // Larger than the default 24dp: at three metres the "on" forms' filled square was all
        // that read, not the glyph inside it.
        Icon(imageVector = icon, contentDescription = description, modifier = Modifier.size(30.dp))
    }
}

@Composable
internal fun HeartButton(favorite: Boolean, onClick: () -> Unit) {
    IconAppButton(onClick = onClick) {
        Icon(
            imageVector = if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = if (favorite) "A favourite — remove it" else "Add to favourites",
            modifier = Modifier.size(30.dp),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun BanButton(onClick: () -> Unit) {
    IconAppButton(onClick = onClick) {
        Icon(Icons.Outlined.Block, contentDescription = "Don't play this again", modifier = Modifier.size(30.dp))
    }
}

/** A thumb, filled once the track is already rated that way and outlined otherwise. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun RateButton(up: Boolean, selected: Boolean, onClick: () -> Unit) {
    IconAppButton(onClick = onClick) {
        Icon(
            imageVector = when {
                up && selected -> Icons.Filled.ThumbUp
                up -> Icons.Outlined.ThumbUpOutlined
                selected -> Icons.Filled.ThumbDown
                else -> Icons.Outlined.ThumbDownOutlined
            },
            contentDescription = when {
                up && selected -> "Rated up"
                up -> "Rate up"
                selected -> "Rated down"
                else -> "Rate down"
            },
            modifier = Modifier.size(30.dp),
        )
    }
}
