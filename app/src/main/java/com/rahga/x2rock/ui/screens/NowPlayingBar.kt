package com.rahga.x2rock.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.rahga.x2rock.model.isPlaying
import com.rahga.x2rock.ui.components.ArtSlot
import com.rahga.x2rock.ui.theme.IconAppButton
import com.rahga.x2rock.viewmodel.PlayerUiState

/**
 * What the room plays, at the foot of a screen opened from it, with its play/pause.
 *
 * Drawn for anything loaded, not just a track: a station loaded by URL has no track at all, and
 * the bar used to vanish for exactly the radio this household listens to most. Its line is then
 * what the station says, or the station itself.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun NowPlayingBar(state: PlayerUiState, onPlayPause: () -> Unit) {
    // Not for a television input: its source is the TV, and a play key for it means nothing.
    if (state.onTvInput) return
    val title = state.trackName ?: state.streamInfo ?: state.sourceName ?: return
    val subtitle = if (state.trackName != null) state.artistName else state.sourceName.takeIf { it != title }
    Surface(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .padding(horizontal = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ArtSlot(state.albumArtUrl)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(16.dp))
            IconAppButton(onClick = onPlayPause) {
                val (icon, description) = when {
                    !state.playbackState.isPlaying() -> Icons.Filled.PlayArrow to "Play"
                    state.actions.canPause -> Icons.Filled.Pause to "Pause"
                    else -> Icons.Filled.Stop to "Stop"
                }
                Icon(icon, contentDescription = description, modifier = Modifier.size(30.dp))
            }
        }
    }
}
