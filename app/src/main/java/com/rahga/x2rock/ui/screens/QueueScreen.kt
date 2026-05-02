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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.rahga.x2rock.ui.theme.AppButton
import com.rahga.x2rock.model.QueueItem
import com.rahga.x2rock.viewmodel.QueueViewModel

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun QueueScreen(
    onBack: () -> Unit,
    viewModel: QueueViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    BackHandler { onBack() }

    Surface(modifier = Modifier.fillMaxSize()) {
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
                items = s.items,
                currentTrackName = s.currentTrackName,
                onBack = onBack,
                onPlayItem = viewModel::playItem,
                onRemoveItem = viewModel::removeItem
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun QueueList(
    items: List<QueueItem>,
    currentTrackName: String?,
    onBack: () -> Unit,
    onPlayItem: (Int) -> Unit,
    onRemoveItem: (String) -> Unit
) {
    val firstFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var contextMenuItem by remember { mutableStateOf<QueueItem?>(null) }

    val currentIndex = remember(items, currentTrackName) {
        if (currentTrackName != null) items.indexOfFirst { it.track?.name == currentTrackName } else -1
    }
    val focusTargetIndex = if (currentIndex >= 0) currentIndex else 0

    LaunchedEffect(items.isNotEmpty()) {
        if (items.isNotEmpty()) {
            if (currentIndex > 0) listState.scrollToItem(currentIndex)
            try { firstFocus.requestFocus() } catch (_: Exception) {}
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
                    modifier = if (items.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier
                ) { Text("← Back") }
                Spacer(Modifier.width(24.dp))
                Text(
                    text = if (items.isEmpty()) "Queue is empty" else "Queue (${items.size})",
                    style = MaterialTheme.typography.displaySmall
                )
            }
            Spacer(Modifier.height(24.dp))
            if (items.isEmpty()) return@Column

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(items) { index, item ->
                    QueueRow(
                        index = index + 1,
                        item = item,
                        isCurrent = currentTrackName != null && item.track?.name == currentTrackName,
                        onClick = { onPlayItem(index + 1) },
                        onLongPress = { contextMenuItem = item },
                        modifier = if (index == focusTargetIndex) Modifier.focusRequester(firstFocus) else Modifier
                    )
                }
            }
        }

        val menuItem = contextMenuItem
        if (menuItem != null) {
            QueueItemContextMenu(
                item = menuItem,
                onRemove = { onRemoveItem(menuItem.id); contextMenuItem = null },
                onDismiss = { contextMenuItem = null }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun QueueRow(
    index: Int,
    item: QueueItem,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var longPressJob by remember { mutableStateOf<Job?>(null) }

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isCurrent) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                else Modifier
            )
            .onKeyEvent { event ->
                if (event.key == Key.DirectionCenter || event.key == Key.Enter) {
                    when (event.type) {
                        KeyEventType.KeyDown -> {
                            if (longPressJob == null) {
                                longPressJob = scope.launch {
                                    delay(600L)
                                    longPressJob = null
                                    onLongPress()
                                }
                            }
                        }
                        KeyEventType.KeyUp -> {
                            longPressJob?.cancel()
                            longPressJob = null
                        }
                        else -> {}
                    }
                }
                false
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = "$index",
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
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler { onDismiss() }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { firstFocus.requestFocus() } catch (_: Exception) {} }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center
    ) {
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
            AppButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        }
    }
}
