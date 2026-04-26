package com.rahga.x2rock.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.rahga.x2rock.viewmodel.PlayerUiState
import com.rahga.x2rock.viewmodel.PlayerViewModel

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    BackHandler { onBack() }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 64.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            TrackInfo(state)
            PlaybackControls(state, viewModel)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TrackInfo(state: PlayerUiState) {
    Column {
        Text(state.groupName, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(24.dp))
        when {
            state.isLoading -> Text("Loading…", style = MaterialTheme.typography.bodyLarge)
            state.trackName != null -> {
                Text(
                    text = state.trackName,
                    style = MaterialTheme.typography.displaySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val subtitle = listOfNotNull(state.artistName, state.albumName).joinToString(" • ")
                if (subtitle.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(subtitle, style = MaterialTheme.typography.bodyLarge)
                }
            }
            else -> Text(
                text = state.playbackState.toPlayerDisplayLabel(),
                style = MaterialTheme.typography.headlineMedium
            )
        }
        state.error?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlaybackControls(state: PlayerUiState, viewModel: PlayerViewModel) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = { viewModel.skipToPreviousTrack() }) {
                Text("⏮  Prev")
            }
            Button(
                onClick = { viewModel.togglePlayPause() },
                modifier = Modifier.width(160.dp)
            ) {
                Text(if (state.playbackState == "PLAYBACK_STATE_PLAYING") "⏸  Pause" else "▶  Play")
            }
            Button(onClick = { viewModel.skipToNextTrack() }) {
                Text("Next  ⏭")
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = { viewModel.adjustVolume(-5) }) { Text("Vol −") }
            Text("Volume: ${state.volume}", style = MaterialTheme.typography.bodyLarge)
            Button(onClick = { viewModel.adjustVolume(+5) }) { Text("Vol +") }
        }
    }
}

private fun String.toPlayerDisplayLabel(): String = when (this) {
    "PLAYBACK_STATE_PLAYING" -> "Playing"
    "PLAYBACK_STATE_PAUSED" -> "Paused"
    "PLAYBACK_STATE_BUFFERING" -> "Buffering"
    else -> "Idle"
}
