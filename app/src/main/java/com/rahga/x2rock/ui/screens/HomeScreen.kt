package com.rahga.x2rock.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.rahga.x2rock.model.Group
import com.rahga.x2rock.viewmodel.HomeViewModel

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    onGroupSelected: (Group) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Surface(modifier = Modifier.fillMaxSize()) {
        when (val s = state) {
            is HomeViewModel.UiState.Loading -> LoadingContent()
            is HomeViewModel.UiState.Success -> RoomsContent(s.groups, onGroupSelected)
            is HomeViewModel.UiState.Error -> ErrorContent(s.message, viewModel::loadGroups)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LoadingContent() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Loading rooms…", style = MaterialTheme.typography.titleLarge)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RoomsContent(groups: List<Group>, onGroupSelected: (Group) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 48.dp, top = 40.dp)
    ) {
        Text("Rooms", style = MaterialTheme.typography.displaySmall)
        Spacer(modifier = Modifier.height(32.dp))
        if (groups.isEmpty()) {
            Text("No rooms found.", style = MaterialTheme.typography.bodyLarge)
        } else {
            LazyRow(
                contentPadding = PaddingValues(end = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                items(groups) { group ->
                    RoomCard(group, onClick = { onGroupSelected(group) })
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RoomCard(group: Group, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.size(width = 280.dp, height = 160.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = group.name,
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 2
            )
            Text(
                text = group.playbackState.toDisplayLabel(),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Failed to load rooms", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}

private fun String.toDisplayLabel(): String = when (this) {
    "PLAYBACK_STATE_PLAYING" -> "Playing"
    "PLAYBACK_STATE_PAUSED" -> "Paused"
    "PLAYBACK_STATE_BUFFERING" -> "Buffering"
    else -> "Idle"
}
