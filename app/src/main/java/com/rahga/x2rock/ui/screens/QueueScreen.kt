package com.rahga.x2rock.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.rahga.x2rock.ui.components.NoticeBanner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.rahga.x2rock.model.QueueItem
import com.rahga.x2rock.model.isPlaying
import com.rahga.x2rock.ui.theme.AppButton
import com.rahga.x2rock.ui.theme.rememberAutoFocusRequester
import com.rahga.x2rock.ui.theme.requestFocusSafely
import com.rahga.x2rock.ui.components.Overlay
import com.rahga.x2rock.ui.components.dpadMenuKey
import com.rahga.x2rock.viewmodel.PlayerViewModel
import com.rahga.x2rock.viewmodel.QueueEntry
import com.rahga.x2rock.viewmodel.QueueViewModel
import com.rahga.x2rock.viewmodel.neighbourSlots

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun QueueScreen(
    onBack: () -> Unit,
    playerViewModel: PlayerViewModel,
    viewModel: QueueViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val playerState by playerViewModel.uiState.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val clearArmed by viewModel.clearArmed.collectAsState()
    BackHandler { onBack() }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                when (val s = state) {
                    is QueueViewModel.UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Loading queue…", style = MaterialTheme.typography.titleLarge)
                    }
                    is QueueViewModel.UiState.Error -> Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("Failed to load queue", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(s.message, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(24.dp))
                        AppButton(onClick = { viewModel.reload() }) { Text("Retry") }
                    }
                    is QueueViewModel.UiState.Success -> QueueList(
                        entries = s.entries,
                        currentTrackName = s.currentTrackName,
                        inUse = s.inUse,
                        onBack = onBack,
                        onPlayItem = viewModel::playItem,
                        onRemoveItem = viewModel::removeItem,
                        onMoveUp = viewModel::moveUp,
                        onMoveDown = viewModel::moveDown,
                        clearArmed = clearArmed,
                        onClear = viewModel::clearQueue,
                        onSave = viewModel::saveAsPlaylist,
                    )
                }
                // This screen's own failures, or the now-playing bar's, which go through the
                // player — either would otherwise be said only on a pane this screen covers.
                (notice ?: playerState.notice)?.let {
                    NoticeBanner(it, Modifier.align(Alignment.BottomCenter))
                }
            }
            val nowPlayingTrack = playerState.trackName
            if (nowPlayingTrack != null) {
                NowPlayingBar(
                    trackName = nowPlayingTrack,
                    artistName = playerState.artistName,
                    isCurrentlyPlaying = playerState.playbackState.isPlaying(),
                    onPlayPause = { playerViewModel.togglePlayPause() }
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun QueueList(
    entries: List<QueueEntry>,
    currentTrackName: String?,
    inUse: Boolean,
    onBack: () -> Unit,
    onPlayItem: (Int) -> Unit,
    onRemoveItem: (Int) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    clearArmed: Boolean,
    onClear: () -> Unit,
    onSave: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var contextMenuEntry by remember { mutableStateOf<QueueEntry?>(null) }

    val currentIndex = remember(entries, currentTrackName) {
        if (currentTrackName != null) entries.indexOfFirst { it.item.track?.name == currentTrackName } else -1
    }
    val focusTargetIndex = if (currentIndex >= 0) currentIndex else 0

    LaunchedEffect(entries.isNotEmpty()) {
        if (entries.isNotEmpty()) {
            if (currentIndex > 0) listState.scrollToItem(currentIndex)
            firstFocus.requestFocusSafely()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 48.dp, top = 40.dp, end = 48.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppButton(
                    onClick = onBack,
                    modifier = if (entries.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier
                ) { Text("← Back") }
                Spacer(Modifier.width(24.dp))
                Text(
                    text = if (entries.isEmpty()) "Queue is empty" else "Queue (${entries.size})",
                    style = MaterialTheme.typography.displaySmall
                )
                if (entries.isNotEmpty()) {
                    Spacer(Modifier.width(24.dp))
                    AppButton(onClick = onSave) { Text("Save as playlist") }
                    Spacer(Modifier.width(12.dp))
                    // Two presses: the label says what the second will do.
                    AppButton(onClick = onClear) { Text(if (clearArmed) "Press again to clear" else "Clear") }
                }
            }
            if (!inUse && entries.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "The room is playing something else. Choose a track to go back to the queue.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Spacer(Modifier.height(24.dp))
            if (entries.isEmpty()) return@Column

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(entries) { index, entry ->
                    QueueRow(
                        entry = entry,
                        isCurrent = currentTrackName != null && entry.item.track?.name == currentTrackName,
                        onClick = { onPlayItem(entry.trackNumber) },
                        onLongPress = { contextMenuEntry = entry },
                        modifier = if (index == focusTargetIndex) Modifier.focusRequester(firstFocus) else Modifier
                    )
                }
            }
        }

        val menuEntry = contextMenuEntry
        if (menuEntry != null) {
            QueueItemContextMenu(
                item = menuEntry.item,
                canMoveUp = neighbourSlots(entries, menuEntry.trackNumber).first != null,
                canMoveDown = neighbourSlots(entries, menuEntry.trackNumber).second != null,
                onRemove = { onRemoveItem(menuEntry.trackNumber); contextMenuEntry = null },
                onMoveUp = { onMoveUp(menuEntry.trackNumber); contextMenuEntry = null },
                onMoveDown = { onMoveDown(menuEntry.trackNumber); contextMenuEntry = null },
                onDismiss = { contextMenuEntry = null }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun QueueRow(
    entry: QueueEntry,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val item = entry.item
    Card(
        onClick = onClick,
        onLongClick = onLongPress,
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isCurrent) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                else Modifier
            )
            .dpadMenuKey(onLongPress)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = "${entry.trackNumber}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(40.dp)
            )
            item.track?.imageUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
                Spacer(Modifier.width(16.dp))
            }
            Column {
                Text(
                    text = item.track?.name ?: "Unknown track",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val sub = listOfNotNull(
                    item.track?.artist?.name,
                    item.track?.album?.name
                ).joinToString(" • ")
                if (sub.isNotEmpty()) {
                    Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun QueueItemContextMenu(
    item: QueueItem,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler { onDismiss() }
    val firstFocus = rememberAutoFocusRequester()

    Overlay {
        Column(
            modifier = Modifier
                .width(320.dp)
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = item.track?.name ?: "Track",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            AppButton(
                onClick = onRemove,
                modifier = Modifier.fillMaxWidth().focusRequester(firstFocus)
            ) {
                Text("Remove from Queue")
            }
            // Drawn only where there is somewhere to move to, rather than disabled.
            if (canMoveUp) {
                AppButton(onClick = onMoveUp, modifier = Modifier.fillMaxWidth()) { Text("Move up") }
            }
            if (canMoveDown) {
                AppButton(onClick = onMoveDown, modifier = Modifier.fillMaxWidth()) { Text("Move down") }
            }
            AppButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        }
    }
}
