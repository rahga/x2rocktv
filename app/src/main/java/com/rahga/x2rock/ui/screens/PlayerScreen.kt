package com.rahga.x2rock.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.rahga.x2rock.model.RepeatModes
import com.rahga.x2rock.model.isPlaying
import com.rahga.x2rock.model.toPlaybackLabel
import com.rahga.x2rock.ui.components.Overlay
import com.rahga.x2rock.ui.theme.AppButton
import com.rahga.x2rock.ui.theme.rememberAutoFocusRequester
import com.rahga.x2rock.ui.theme.requestFocusSafely
import com.rahga.x2rock.viewmodel.PlayerUiState
import com.rahga.x2rock.viewmodel.PlayerVolumeEntry
import com.rahga.x2rock.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerPane(
    viewModel: PlayerViewModel,
    detailFocusRequester: FocusRequester,
    onOpenQueue: () -> Unit,
    onOpenFavorites: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var showSleepTimerPicker by remember { mutableStateOf(false) }

    // The picker traps focus, so closing it has to hand focus back explicitly.
    LaunchedEffect(showSleepTimerPicker) {
        if (!showSleepTimerPicker) detailFocusRequester.requestFocusSafely()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                            viewModel.togglePlayPause(); true
                        }
                        Key.MediaNext -> { viewModel.skipToNextTrack(); true }
                        Key.MediaPrevious -> { viewModel.skipToPreviousTrack(); true }
                        Key.MediaFastForward -> { viewModel.seekBy(+30_000L); true }
                        Key.MediaRewind -> { viewModel.seekBy(-30_000L); true }
                        else -> false
                    }
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 64.dp, vertical = 48.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                TrackInfo(state)
                PlaybackControls(
                    state = state,
                    viewModel = viewModel,
                    playPauseFocusRequester = detailFocusRequester,
                    onOpenQueue = onOpenQueue,
                    onOpenFavorites = onOpenFavorites,
                    onOpenSleepTimer = { showSleepTimerPicker = true }
                )
            }
        }

        if (showSleepTimerPicker) {
            SleepTimerPickerOverlay(
                onSelect = { minutes ->
                    viewModel.setSleepTimer(minutes)
                    showSleepTimerPicker = false
                },
                onDismiss = { showSleepTimerPicker = false }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TrackInfo(state: PlayerUiState) {
    Column {
        Text(state.groupName, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.albumArtUrl != null) {
                AsyncImage(
                    model = state.albumArtUrl,
                    contentDescription = "Album art",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(200.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(modifier = Modifier.width(32.dp))
            }
            Column {
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
                        text = state.playbackState.toPlaybackLabel(),
                        style = MaterialTheme.typography.headlineMedium
                    )
                }
                state.error?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProgressBar(state: PlayerUiState, onSeekBy: (Long) -> Unit) {
    if (state.durationMillis <= 0) return

    var displayPositionMillis by remember(state.positionUpdatedAt) {
        mutableLongStateOf(state.positionMillis)
    }

    LaunchedEffect(state.positionUpdatedAt, state.playbackState) {
        if (state.playbackState.isPlaying()) {
            while (true) {
                delay(1_000L)
                displayPositionMillis = (displayPositionMillis + 1_000L).coerceAtMost(state.durationMillis)
            }
        }
    }

    val progress = (displayPositionMillis.toFloat() / state.durationMillis).coerceIn(0f, 1f)
    var isFocused by remember { mutableStateOf(false) }
    val barColor = if (isFocused) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .focusable()
                .onFocusChanged { isFocused = it.isFocused }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> { onSeekBy(-30_000L); true }
                        Key.DirectionRight -> { onSeekBy(+30_000L); true }
                        else -> false
                    }
                }
        ) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = barColor
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(displayPositionMillis.toTimeString(), style = MaterialTheme.typography.bodySmall)
            if (isFocused) {
                Text("◀ ▶  seek 30s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            Text(state.durationMillis.toTimeString(), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlaybackControls(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    playPauseFocusRequester: FocusRequester,
    onOpenQueue: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenSleepTimer: () -> Unit
) {
    LaunchedEffect(Unit) {
        playPauseFocusRequester.requestFocusSafely()
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ProgressBar(state, onSeekBy = { viewModel.seekBy(it) })
        Spacer(modifier = Modifier.height(24.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.focusGroup()
        ) {
            AppButton(onClick = { viewModel.skipToPreviousTrack() }) { Text("⏮  Prev") }
            if (state.durationMillis > 0) {
                AppButton(onClick = { viewModel.seekBy(-30_000L) }) { Text("−30s") }
            }
            AppButton(
                onClick = { viewModel.togglePlayPause() },
                modifier = Modifier
                    .width(160.dp)
                    .focusRequester(playPauseFocusRequester)
            ) {
                Text(if (state.playbackState.isPlaying()) "⏸  Pause" else "▶  Play")
            }
            if (state.durationMillis > 0) {
                AppButton(onClick = { viewModel.seekBy(+30_000L) }) { Text("+30s") }
            }
            AppButton(onClick = { viewModel.skipToNextTrack() }) { Text("Next  ⏭") }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.focusGroup()
        ) {
            AppButton(onClick = { viewModel.toggleShuffle() }) {
                Text(if (state.shuffle) "Shuffle ON" else "Shuffle OFF")
            }
            AppButton(onClick = { viewModel.cycleRepeat() }) {
                Text(state.repeat.toRepeatLabel())
            }
            AppButton(onClick = { viewModel.toggleCrossfade() }) {
                Text(if (state.crossfade) "Crossfade ON" else "Crossfade OFF")
            }
            AppButton(onClick = onOpenQueue) { Text("Queue") }
            AppButton(onClick = onOpenFavorites) { Text("Favorites") }
            AppButton(onClick = {
                if (state.sleepTimerRemainingMillis != null) viewModel.cancelSleepTimer()
                else onOpenSleepTimer()
            }) {
                Text(
                    if (state.sleepTimerRemainingMillis != null)
                        "Sleep: ${state.sleepTimerRemainingMillis.toTimeString()}"
                    else "Sleep Timer"
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.focusGroup()
        ) {
            // Disabled until the speaker's volume is known, not merely while muted: the
            // view model declines to act without a baseline, so an enabled button in that
            // window would take focus and then do nothing at all.
            val volumeKnown = state.volume != null
            AppButton(
                onClick = { viewModel.adjustVolume(-5) },
                enabled = volumeKnown && !state.isMuted,
            ) { Text("Vol −") }
            Text(
                text = when {
                    state.volume == null -> "Volume: —"
                    state.isMuted -> "Muted"
                    else -> "Volume: ${state.volume}"
                },
                style = MaterialTheme.typography.bodyLarge
            )
            AppButton(
                onClick = { viewModel.adjustVolume(+5) },
                enabled = volumeKnown && !state.isMuted,
            ) { Text("Vol +") }
            AppButton(onClick = { viewModel.toggleMute() }, enabled = volumeKnown) {
                Text(if (state.isMuted) "Unmute" else "Mute")
            }
        }

        if (state.playerVolumes.isNotEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))
            Text("Speakers", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))
            state.playerVolumes.forEach { entry ->
                PlayerVolumeRow(entry, viewModel)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlayerVolumeRow(entry: PlayerVolumeEntry, viewModel: PlayerViewModel) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp).focusGroup()
    ) {
        Text(
            text = entry.playerName,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(160.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        AppButton(
            onClick = { viewModel.adjustPlayerVolume(entry.playerId, -5) },
            enabled = !entry.muted
        ) { Text("−") }
        Text(
            text = if (entry.muted) "Muted" else "${entry.volume}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(52.dp)
        )
        AppButton(
            onClick = { viewModel.adjustPlayerVolume(entry.playerId, +5) },
            enabled = !entry.muted
        ) { Text("+") }
        AppButton(onClick = { viewModel.togglePlayerMute(entry.playerId) }) {
            Text(if (entry.muted) "Unmute" else "Mute")
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SleepTimerPickerOverlay(
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler { onDismiss() }
    val firstFocus = rememberAutoFocusRequester()

    Overlay {
        Column(
            modifier = Modifier
                .width(280.dp)
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Sleep Timer", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            listOf(15, 30, 45, 60).forEachIndexed { index, minutes ->
                AppButton(
                    onClick = { onSelect(minutes) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                ) {
                    Text("$minutes minutes")
                }
            }
            AppButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        }
    }
}

internal fun Long.toTimeString(): String {
    val totalSeconds = (this / 1_000).coerceAtLeast(0)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
           else "%d:%02d".format(minutes, seconds)
}

private fun String.toRepeatLabel(): String = when (this) {
    RepeatModes.ALL -> "Repeat All"
    RepeatModes.ONE -> "Repeat One"
    else -> "Repeat OFF"
}
