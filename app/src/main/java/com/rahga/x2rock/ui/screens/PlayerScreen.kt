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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Tv
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
import androidx.compose.ui.focus.focusProperties
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
import androidx.tv.material3.Icon
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
    /**
     * Where a left-press from the leftmost control of any row goes.
     *
     * Declared on those controls rather than on the pane, because a `left` property on an
     * ancestor does not reliably redirect a search starting inside it — the search escaped
     * instead and focus was lost altogether, which on a remote leaves nothing to press.
     */
    sidebarFocusRequester: FocusRequester,
    onOpenQueue: () -> Unit,
    onOpenFavorites: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var showSleepTimerPicker by remember { mutableStateOf(false) }
    var paneHasFocus by remember { mutableStateOf(false) }

    // Where focus starts, once, for the life of this pane.
    //
    // Deliberately not inside the controls themselves. The music pane and the TV pane are
    // separate composables, so every change of source re-enters one of them — and a grab
    // there pulls focus out of the room list while the viewer is still arrowing through it.
    // HomeScreen owns the policy otherwise: it requests this pane on a right-press, when the
    // sidebar hides, and on resume.
    LaunchedEffect(Unit) { detailFocusRequester.requestFocusSafely() }

    // The one case that might have to re-request: a source change while this pane holds
    // focus. The focused control leaves composition with its branch, and on a remote that
    // leaves nothing to press but Back. Guarded on the pane actually having focus, so a room
    // list change never pulls it across.
    //
    // Both outcomes are verified on hardware — focus moved from Night Sound to Pause when a
    // stream was started on a room sitting on its TV input, and stayed in the room list when
    // the source changed while the sidebar had focus. What is *not* established is which
    // mechanism produces the first one. Compose processes the focus invalidation from the
    // removed node in onEndApplyChanges, before this coroutine is dispatched, so this flag
    // may already read false and Compose's own handling may be doing the work. Settling that
    // needs a device; until then this is kept because the behaviour it describes is correct,
    // not because the guard is known to be what causes it.
    LaunchedEffect(state.onTvInput) {
        if (paneHasFocus) detailFocusRequester.requestFocusSafely()
    }

    // The picker traps focus, so closing it has to hand focus back explicitly.
    LaunchedEffect(showSleepTimerPicker) {
        if (!showSleepTimerPicker) detailFocusRequester.requestFocusSafely()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .onFocusChanged { paneHasFocus = it.hasFocus }
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
                // A television input is a different source, not the music pane with pieces
                // missing, so it gets its own header and its own controls.
                if (state.onTvInput) {
                    TvInfo(state)
                    TvControls(
                        state = state,
                        viewModel = viewModel,
                        firstFocusRequester = detailFocusRequester,
                        exitLeftFocusRequester = sidebarFocusRequester,
                        onOpenFavorites = onOpenFavorites,
                        onOpenSleepTimer = { showSleepTimerPicker = true },
                    )
                } else {
                    TrackInfo(state)
                    PlaybackControls(
                        state = state,
                        viewModel = viewModel,
                        playPauseFocusRequester = detailFocusRequester,
                        exitLeftFocusRequester = sidebarFocusRequester,
                        onOpenQueue = onOpenQueue,
                        onOpenFavorites = onOpenFavorites,
                        onOpenSleepTimer = { showSleepTimerPicker = true }
                    )
                }
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
            } else if (state.isRadio) {
                // A station logo is shown where there is one — many services carry it — and
                // this stands in where there is not. A stream loaded by its URL really does
                // send `images: []`, so there is nothing to fetch and nothing to wait for.
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Radio,
                        contentDescription = null,
                        modifier = Modifier.size(88.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
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
                        // A service radio station can carry a track *and* a streamInfo, so
                        // show what the station says only when it says something the title
                        // does not — otherwise the same line appears twice.
                        state.streamInfo?.takeIf { it != state.trackName }?.let { info ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(info, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    // A stream loaded by URL has no track object, so this is its only
                    // now-playing. Shown whole: it is the station's own string, and what
                    // looks like "Artist - Title" often is not.
                    state.streamInfo != null -> {
                        Text(
                            text = state.streamInfo,
                            style = MaterialTheme.typography.displaySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        state.sourceName?.let { station ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(station, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    // "Idle" describes the transport; with nothing loaded at all it is the
                    // wrong thing to say, because the room is not resting between tracks —
                    // there are none. The Sonos app names this state, and so does this.
                    else -> Text(
                        text = if (!state.actions.canPlay) "No Content"
                            else state.playbackState.toPlaybackLabel(),
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

/**
 * A left-press here leaves the room view for the rooms panel.
 *
 * Put on the leftmost control of each row. Declaring `left` on the pane instead does not
 * work: a focus search starting inside it escapes rather than being redirected, and focus
 * is lost altogether — which on a remote leaves nothing to press but Back.
 */
private fun Modifier.exitLeftTo(target: FocusRequester): Modifier = onKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
        target.requestFocusSafely()
        true
    } else false
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlaybackControls(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    playPauseFocusRequester: FocusRequester,
    exitLeftFocusRequester: FocusRequester,
    onOpenQueue: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenSleepTimer: () -> Unit
) {
    // The exit has to sit on whichever control is actually leftmost, and that now depends on
    // what the source permits: hiding Prev promotes the seek button, hiding Shuffle promotes
    // Repeat. Claimed by the first control drawn in each row rather than pinned to a
    // particular one, because a row whose leftmost control has no exit loses focus entirely
    // on a left-press — see the note on exitLeftTo.
    fun Modifier.claimExit(claimed: BooleanArray): Modifier =
        if (claimed[0]) this else { claimed[0] = true; this.exitLeftTo(exitLeftFocusRequester) }

    // Nothing is loaded at all — not paused, not stopped, but empty. The player says so
    // itself: `canPlay` is false only here, and stays true even for a stream that is merely
    // stopped. Sonos draws this room's transport greyed out; we leave it out, because a
    // disabled tv-material button takes focus and draws nothing, and the row would be
    // nothing but such buttons.
    val hasContent = state.actions.canPlay

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ProgressBar(state, onSeekBy = { viewModel.seekBy(it) })
        if (hasContent) Spacer(modifier = Modifier.height(24.dp))

        val transportExit = booleanArrayOf(false)
        if (hasContent)

        Row(
            // Tighter than the rows below it: with a duration this row grows from three
            // buttons to five, and at 24.dp the last one overflowed and wrapped its label
            // down the screen a letter at a time.
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.focusGroup()
        ) {
            // Drawn only where the source permits them. A live stream refuses skip, seek
            // and shuffle outright, and a row of controls that cannot act is worse on a
            // remote than a shorter row: each is still a focus stop that does nothing.
            if (state.actions.canSkipToPrevious) {
                AppButton(
                    onClick = { viewModel.skipToPreviousTrack() },
                    modifier = Modifier.claimExit(transportExit),
                ) { Text("⏮  Prev") }
            }
            if (state.durationMillis > 0 && state.actions.canSeek) {
                AppButton(
                    onClick = { viewModel.seekBy(-30_000L) },
                    modifier = Modifier.claimExit(transportExit),
                ) { Text("−30s") }
            }
            AppButton(
                onClick = { viewModel.togglePlayPause() },
                // Wide enough that Play and Pause do not shuffle the row as it toggles,
                // but no longer a fixed width that the seek buttons have to fit around.
                modifier = Modifier
                    .widthIn(min = 148.dp)
                    .focusRequester(playPauseFocusRequester)
                    .claimExit(transportExit)
            ) {
                // A live stream cannot be paused, only stopped: pausing one leaves the room
                // IDLE rather than PAUSED, verified on hardware. The command is the same
                // either way — the player does the right thing — so only the word changes,
                // to the one that describes what will actually happen.
                Text(
                    when {
                        !state.playbackState.isPlaying() -> "▶  Play"
                        state.actions.canPause -> "⏸  Pause"
                        else -> "⏹  Stop"
                    }
                )
            }
            if (state.durationMillis > 0 && state.actions.canSeek) {
                AppButton(onClick = { viewModel.seekBy(+30_000L) }) { Text("+30s") }
            }
            if (state.actions.canSkip) {
                AppButton(onClick = { viewModel.skipToNextTrack() }) { Text("Next  ⏭") }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.focusGroup()
        ) {
            val modeExit = booleanArrayOf(false)
            // Hidden with no content, even though the player reports all three as available.
            // `availablePlaybackActions` answers "what may I do to this content", not "is
            // there any" — and the Sonos app hides them here for the same reason.
            if (hasContent && state.actions.canShuffle) {
                AppButton(
                    onClick = { viewModel.toggleShuffle() },
                    modifier = Modifier.claimExit(modeExit),
                ) {
                    Text(if (state.shuffle) "Shuffle ON" else "Shuffle OFF")
                }
            }
            if (hasContent && state.actions.canRepeat) {
                AppButton(
                    onClick = { viewModel.cycleRepeat() },
                    modifier = Modifier.claimExit(modeExit),
                ) {
                    Text(state.repeat.toRepeatLabel())
                }
            }
            if (hasContent && state.actions.canCrossfade) {
                AppButton(
                    onClick = { viewModel.toggleCrossfade() },
                    modifier = Modifier.claimExit(modeExit),
                ) {
                    Text(if (state.crossfade) "Crossfade ON" else "Crossfade OFF")
                }
            }
            // Queue keeps its place whatever the source permits: it is a way to look at what
            // is loaded rather than an action on the current item.
            AppButton(
                onClick = onOpenQueue,
                modifier = Modifier.claimExit(modeExit),
            ) { Text("Queue") }
            // With no transport drawn there is nothing for a right-press from the room list
            // to land on, so the entry point moves here — which is also the one control that
            // helps, being how an empty room is given something to play.
            AppButton(
                onClick = onOpenFavorites,
                modifier = if (hasContent) Modifier
                    else Modifier.focusRequester(playPauseFocusRequester),
            ) { Text("Favorites") }
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

        VolumeRow(state, viewModel, exitLeftFocusRequester)

        if (state.playerVolumes.isNotEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))
            Text("Speakers", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))
            state.playerVolumes.forEach { entry ->
                PlayerVolumeRow(entry, viewModel, exitLeftFocusRequester)
            }
        }
    }
}

/**
 * The group's level, identical on the music pane and the TV one.
 *
 * Shared rather than duplicated on purpose: volume is the one control that means the same
 * thing whatever the room is playing, and it has to sit in the same place and answer the
 * same presses when the source changes. A separate copy for the TV pane would drift.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun VolumeRow(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    exitLeftFocusRequester: FocusRequester,
) {
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
            modifier = Modifier.exitLeftTo(exitLeftFocusRequester),
        ) { Text("Vol \u2212") }
        Text(
            text = when {
                state.volume == null -> "Volume: \u2014"
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
}

/**
 * The header for a room on its television input, in place of [TrackInfo].
 *
 * There is no track to name and the player sends `images: []`, so the art slot carries a
 * television glyph — the same invention the room list makes, for the same reason. "TV" and
 * "HDMI" name the source the way the Sonos app does, and the format sits out to the right
 * where a duration would be.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvInfo(state: PlayerUiState) {
    Column {
        Text(state.groupName, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(24.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Tv,
                    contentDescription = null,
                    modifier = Modifier.size(88.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
            Spacer(modifier = Modifier.width(32.dp))
            Column {
                Text("TV", style = MaterialTheme.typography.displaySmall)
                Spacer(modifier = Modifier.height(8.dp))
                Text("HDMI", style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(modifier = Modifier.weight(1f))
            // Empty while the television is off or between sources, where the player reports
            // no signal at all — an empty string reads better than "No Signal 0.0".
            if (state.inputFormat.isNotEmpty()) {
                Text(state.inputFormat, style = MaterialTheme.typography.headlineSmall)
            }
        }
    }
}

/**
 * What a room on its television input can be told to do.
 *
 * Not the music controls with the inapplicable ones removed: skip, shuffle, repeat and the
 * queue have nothing to act on here, and a grid that loses buttons when the source changes
 * is worse on a remote than a different grid. So this is its own surface, sharing only
 * [VolumeRow] — which is the one control that does mean the same thing either way.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvControls(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    firstFocusRequester: FocusRequester,
    exitLeftFocusRequester: FocusRequester,
    onOpenFavorites: () -> Unit,
    onOpenSleepTimer: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.focusGroup()
        ) {
            // Both are soundbar settings read from `settings:1` and written over UPnP, and
            // neither is pushed — so the label is whatever the last read said, and a press
            // re-reads rather than assuming it landed.
            // Never disabled, because this pane is only drawn for a room *on* its HDMI
            // input, which means a soundbar exists — `soundbarId` is null only while the
            // read is in flight or if it failed. A disabled tv-material Button still takes
            // focus and draws no highlight, and this is where a right-press from the room
            // list lands, so disabling it would park the remote on an invisible control.
            // The unknown value is said rather than guessed instead.
            val settingsKnown = state.soundbarId != null
            TwoLineButton(
                title = "Night Sound",
                value = if (!settingsKnown) "—" else if (state.nightMode) "On" else "Off",
                onClick = { viewModel.toggleNightMode() },
                modifier = Modifier
                    .focusRequester(firstFocusRequester)
                    .exitLeftTo(exitLeftFocusRequester),
            )
            TwoLineButton(
                title = "Speech Enhancement",
                value = if (!settingsKnown) "—" else if (state.speechEnhancement) "On" else "Off",
                onClick = { viewModel.toggleSpeechEnhancement() },
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        VolumeRow(state, viewModel, exitLeftFocusRequester)

        Spacer(modifier = Modifier.height(16.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.focusGroup(),
        ) {
            // The way out of the television and back into music. Sonos's own TV screen has no
            // such row, but a room on its HDMI input is exactly where wanting to put music on
            // instead is a live thought, and nothing else here offers it.
            AppButton(
                onClick = onOpenFavorites,
                modifier = Modifier.exitLeftTo(exitLeftFocusRequester),
            ) { Text("Favorites") }
            AppButton(
                onClick = {
                    if (state.sleepTimerRemainingMillis != null) viewModel.cancelSleepTimer()
                    else onOpenSleepTimer()
                },
            ) {
                Text(
                    if (state.sleepTimerRemainingMillis != null)
                        "Sleep: ${state.sleepTimerRemainingMillis.toTimeString()}"
                    else "Sleep Timer"
                )
            }
        }

        if (state.playerVolumes.isNotEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))
            Text("Speakers", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))
            state.playerVolumes.forEach { entry ->
                PlayerVolumeRow(entry, viewModel, exitLeftFocusRequester)
            }
        }
    }
}

/** A setting and the value it currently holds, the way the Sonos app draws these two. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TwoLineButton(
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlayerVolumeRow(
    entry: PlayerVolumeEntry,
    viewModel: PlayerViewModel,
    exitLeftFocusRequester: FocusRequester,
) {
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
            enabled = !entry.muted,
            // The leftmost control of this row, and these rows appear whenever the room is
            // grouped. Without it a left press falls through to the pane's `focusProperties`,
            // which does not govern the search — so focus left the pane and was lost.
            modifier = Modifier.exitLeftTo(exitLeftFocusRequester),
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
