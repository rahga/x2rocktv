package com.rahga.x2rock.ui.screens

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.ThumbDown as ThumbDownOutlined
import androidx.compose.material.icons.outlined.ThumbUp as ThumbUpOutlined
import com.rahga.x2rock.lan.SNOOZE_MINUTES
import com.rahga.x2rock.smapi.Thumb
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
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
            // Scrolls, because a grouped room's speaker rows run off the bottom: with a five-room
            // party on the Shield they were drawn below the screen and focus could not reach
            // them. A focused control in a scrolling column is brought into view as the remote
            // moves, so nothing else is needed for D-pad.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 64.dp, vertical = 48.dp),
                verticalArrangement = Arrangement.spacedBy(32.dp)
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
                    if (state.ringingAlarm != null) AlarmControls(viewModel, sidebarFocusRequester, detailFocusRequester)
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

/**
 * A ringing alarm's two answers, above the track while it rings. Play/Pause keeps the focus it
 * had: pressing it, or the remote's play/pause key, stops the alarm too, which is what someone
 * half awake reaches for. Snooze is one press up. A snoozed alarm is not offered here: the
 * player still counts it as running, but nothing is sounding, and it comes back when it rings.
 *
 * This row leaves the screen the moment the room stops, so a press on it takes the focused
 * button away from under the remote; focus then lands wherever Compose finds first, which on
 * the Shield was the settings gear. It is handed to Play/Pause instead, the control that was
 * one press below.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AlarmControls(
    viewModel: PlayerViewModel,
    exitLeftFocusRequester: FocusRequester,
    playPauseFocusRequester: FocusRequester,
) {
    var hadFocus by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { if (hadFocus) playPauseFocusRequester.requestFocusSafely() }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .focusGroup()
            .onFocusChanged { hadFocus = it.hasFocus },
    ) {
        Text("Alarm", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.width(12.dp))
        AppButton(
            onClick = { viewModel.snoozeAlarm() },
            modifier = Modifier.exitLeftTo(exitLeftFocusRequester),
        ) { Text("Snooze ${SNOOZE_MINUTES} min") }
        AppButton(onClick = { viewModel.stopAlarm() }) { Text("Stop") }
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
                GlyphTile(Icons.Default.Radio)
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
                    // A stream with no text of its own yet — the first ICY title has not come,
                    // or never will — is named once, by its station or, for a bare URL, its
                    // host: x2rock's rule, and better than a transport word.
                    state.isRadio && state.sourceName != null -> Text(
                        text = state.sourceName,
                        style = MaterialTheme.typography.displaySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
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
                // Without this a failed stream is just an idle room: the player says why in
                // an event of its own, and only once.
                if (state.upnpOff) {
                    Spacer(modifier = Modifier.height(8.dp))
                    UpnpOffNote()
                }
                state.playbackError?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProgressBar(state: PlayerUiState, onSeekBy: (Long) -> Unit, onSeekTo: (Long) -> Unit) {
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

    // The running total of a burst of seek presses, shown under the bar and faded out once the
    // presses stop, so a viewer holding left can see how far back they are going. Clamped to
    // the track, as the seek itself is; a fresh burst starts from nothing.
    var seekTotal by remember { mutableLongStateOf(0L) }
    var seekFrom by remember { mutableLongStateOf(0L) }
    var seekPresses by remember { mutableStateOf(0) }
    var seekShown by remember { mutableStateOf(false) }
    val seekAlpha by animateFloatAsState(if (seekShown) 1f else 0f, animationSpec = tween(400), label = "seekTotal")
    LaunchedEffect(seekPresses) {
        if (seekPresses == 0) return@LaunchedEffect
        delay(SEEK_TOTAL_SHOWN_MILLIS)
        seekShown = false
    }
    fun seek(delta: Long) {
        if (!seekShown) {
            seekTotal = 0L
            seekFrom = displayPositionMillis
        }
        seekTotal = (seekTotal + delta).coerceIn(-seekFrom, state.durationMillis - seekFrom)
        seekShown = true
        seekPresses++
        onSeekBy(delta)
    }
    // A tap aims at a point rather than stepping, so it is sent as one; the total under the
    // bar still says how far that is from where the tap began.
    fun seekToFraction(fraction: Float) {
        val target = (fraction.coerceIn(0f, 1f) * state.durationMillis).toLong()
        if (!seekShown) seekFrom = displayPositionMillis
        seekTotal = target - seekFrom
        seekShown = true
        seekPresses++
        onSeekTo(target)
    }
    val barColor = if (isFocused) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // Before focusable(), not after: onFocusChanged sees focus on what follows it,
                // and placed after it never saw the bar take focus — so the bar never changed
                // colour and its hint never showed, and landing on it looked like landing on
                // nothing.
                .onFocusChanged { isFocused = it.isFocused }
                .focusable()
                // Touch and mouse, for anything that has them — the emulator, a tablet. A remote
                // never sends a tap, so on a TV this is inert. The strip is taller than the bar
                // drawn in it, or an 8dp line would be a hard thing to hit.
                .pointerInput(state.durationMillis) {
                    detectTapGestures { offset -> seekToFraction(offset.x / size.width) }
                }
                .padding(vertical = 12.dp)
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> { seek(-30_000L); true }
                        Key.DirectionRight -> { seek(+30_000L); true }
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
            // The total's slot is always laid out, invisible when there is nothing to say: it is
            // taller than the times beside it, and appearing only on a press made the row grow
            // and nudged every row below it down the screen.
            Box(contentAlignment = Alignment.Center) {
                Text(
                    seekTotal.toSeekLabel(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.alpha(seekAlpha),
                )
                if (seekAlpha == 0f && isFocused) {
                    Text("◀ ▶  seek 30s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
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
        ProgressBar(state, onSeekBy = { viewModel.seekBy(it) }, onSeekTo = { viewModel.seekTo(it) })
        if (hasContent) Spacer(modifier = Modifier.height(24.dp))

        val transportExit = booleanArrayOf(false)
        if (hasContent)

        Row(
            // Three at most. Seeking is the progress bar's, left and right on it, as it was all
            // along: the −30s and +30s buttons that also sat here are the Sonos app's, which
            // draws them as icons; as labelled buttons across a room they crowded this row
            // into overflowing, and said what the bar already does.
            horizontalArrangement = Arrangement.spacedBy(24.dp),
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
            if (state.actions.canSkip) {
                AppButton(onClick = { viewModel.skipToNextTrack() }) { Text("Next  ⏭") }
            }
            // Drawn only where a press can succeed: the track has an id, its service needs no
            // account and publishes ratings. A Live broadcast — this household's ordinary
            // iHeartRadio listening — has no track id, so it never shows them.
            state.rating?.let { rating ->
                RateButton(up = true, selected = rating.current == Thumb.UP) { viewModel.rateUp() }
                RateButton(up = false, selected = rating.current == Thumb.DOWN) { viewModel.rateDown() }
            }
        }

        if (state.notice != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(state.notice, style = MaterialTheme.typography.bodySmall)
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
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Where to go from here, in a row of its own. It used to share the modes' row, and at
        // 1080p on a TV — 960dp across, a third of it the room list — six buttons do not fit:
        // Favorites was squeezed to a sliver with its label wrapped a letter to a line, which
        // made the row hundreds of pixels tall and pushed the volume below the screen, and
        // Sleep Timer was off the edge entirely. Seen on the Shield and the emulator alike.
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.focusGroup()
        ) {
            val placesExit = booleanArrayOf(false)
            // Queue keeps its place whatever the source permits: it is a way to look at what
            // is loaded rather than an action on the current item. Only UPnP being off takes
            // it away, since the queue lives nowhere else; the pane says so below.
            if (!state.upnpOff) {
                AppButton(
                    onClick = onOpenQueue,
                    modifier = Modifier.claimExit(placesExit),
                ) { Text("Queue") }
            }
            // With no transport drawn there is nothing for a right-press from the room list
            // to land on, so the entry point moves here — which is also the one control that
            // helps, being how an empty room is given something to play.
            AppButton(
                onClick = onOpenFavorites,
                modifier = Modifier.claimExit(placesExit).then(
                    if (hasContent) Modifier else Modifier.focusRequester(playPauseFocusRequester)
                ),
            ) { Text("Favorites") }
            SleepTimerButton(state, viewModel, onOpenSleepTimer)
        }

        Spacer(modifier = Modifier.height(16.dp))

        VolumeRow(state, viewModel, exitLeftFocusRequester)

        SpeakerRows(state, viewModel, exitLeftFocusRequester)
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
        // A fixed line-out has no level to step, so −/+ are not drawn — hidden rather than
        // disabled, for the reason below — and Mute takes over as the row's left exit.
        if (state.volumeFixed) {
            Text("Volume: fixed", style = MaterialTheme.typography.bodyLarge)
        } else {
            // Never disabled. A disabled tv-material3 button still takes focus and draws no
            // highlight, and these once were, until a level was known and while muted. A step
            // is relative, so it needs no level, and on a muted room it unmutes as the player
            // does.
            AppButton(
                onClick = { viewModel.adjustVolume(-5) },
                modifier = Modifier.exitLeftTo(exitLeftFocusRequester),
            ) { Text("Vol \u2212") }
            // A muted room keeps its level — the Control API reports it through mute — so it
            // is shown, and said to be muted, rather than replaced by the word.
            Text(
                text = when {
                    state.volume == null -> "Volume: \u2014"
                    state.isMuted -> "Volume: ${state.volume} (muted)"
                    else -> "Volume: ${state.volume}"
                },
                style = MaterialTheme.typography.bodyLarge
            )
            AppButton(onClick = { viewModel.adjustVolume(+5) }) { Text("Vol +") }
        }
        // Needs the current state to toggle, so a press in the moment before the first
        // snapshot does nothing — the view model says why in its own comment.
        AppButton(
            onClick = { viewModel.toggleMute() },
            modifier = if (state.volumeFixed) Modifier.exitLeftTo(exitLeftFocusRequester) else Modifier,
        ) {
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
            GlyphTile(Icons.Default.Tv)
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

        // The two toggles above stay drawn — they are where focus lands — and a press explains
        // itself through the notice. This says it before anyone has to press.
        if (state.upnpOff) {
            Spacer(modifier = Modifier.height(12.dp))
            UpnpOffNote()
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
            SleepTimerButton(state, viewModel, onOpenSleepTimer)
        }

        SpeakerRows(state, viewModel, exitLeftFocusRequester)
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
        // A fixed line-out has no level to step: −/+ are not drawn, and Mute is the left exit.
        if (!entry.fixed) {
            AppButton(
                onClick = { viewModel.adjustPlayerVolume(entry.playerId, -5) },
                // Not disabled while muted: a step unmutes, and a disabled button here would
                // be this row's left exit with no highlight to show the remote was on it.
                // The leftmost control of this row, and these rows appear whenever the room
                // is grouped. Without it a left press falls through to the pane's
                // `focusProperties`, which does not govern the search — so focus left the
                // pane and was lost.
                modifier = Modifier.exitLeftTo(exitLeftFocusRequester),
            ) { Text("−") }
        }
        Text(
            text = when {
                entry.fixed -> "fixed"
                entry.muted -> "${entry.volume} (muted)"
                else -> "${entry.volume}"
            },
            style = MaterialTheme.typography.bodyMedium,
            // Wide enough for "100 (muted)" on one line.
            modifier = Modifier.width(104.dp)
        )
        if (!entry.fixed) {
            AppButton(
                onClick = { viewModel.adjustPlayerVolume(entry.playerId, +5) },
            ) { Text("+") }
        }
        AppButton(
            onClick = { viewModel.togglePlayerMute(entry.playerId) },
            modifier = if (entry.fixed) Modifier.exitLeftTo(exitLeftFocusRequester) else Modifier,
        ) {
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

/** Each speaker's own level, for a group of more than one. Drawn under both panes' volume row. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SpeakerRows(state: PlayerUiState, viewModel: PlayerViewModel, exitLeftFocusRequester: FocusRequester) {
    if (state.playerVolumes.isEmpty()) return
    Spacer(modifier = Modifier.height(24.dp))
    Text("Speakers", style = MaterialTheme.typography.titleSmall)
    Spacer(modifier = Modifier.height(8.dp))
    state.playerVolumes.forEach { entry ->
        PlayerVolumeRow(entry, viewModel, exitLeftFocusRequester)
    }
}

/** The art tile with a glyph for the source in it, where the source sends no image of its own. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GlyphTile(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(200.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(88.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

/**
 * The sleep timer's one button: what is left and a cancel while it runs, the picker otherwise.
 * Sonos's own timer, on AVTransport: with UPnP off there is none to set, and no button.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SleepTimerButton(state: PlayerUiState, viewModel: PlayerViewModel, onOpenSleepTimer: () -> Unit) {
    if (state.upnpOff) return
    val sleepLeft = rememberSleepCountdown(state.sleepTimerEndsAt)
    AppButton(onClick = { if (sleepLeft != null) viewModel.cancelSleepTimer() else onOpenSleepTimer() }) {
        Text(if (sleepLeft != null) "Sleep: ${sleepLeft.toTimeString()}" else "Sleep Timer")
    }
}

/**
 * What is left on the sleep timer, ticking once a second here: the view model holds the moment
 * it ends and no clock. Null for no timer, or one that has run out — the speaker stops the room
 * then, and the view model re-reads on that event. On the monotonic clock the view model used,
 * so an NTP step after boot cannot make a running timer read as expired.
 */
@Composable
private fun rememberSleepCountdown(endsAt: Long?): Long? {
    var left by remember(endsAt) { mutableStateOf(endsAt?.let { it - SystemClock.elapsedRealtime() }?.takeIf { it > 0 }) }
    LaunchedEffect(endsAt) {
        if (endsAt == null) return@LaunchedEffect
        while (true) {
            val remaining = endsAt - SystemClock.elapsedRealtime()
            left = remaining.takeIf { it > 0 }
            if (remaining <= 0) break
            delay(1_000L)
        }
    }
    return left
}

/** How long a seek burst's total stays up after the last press, before it fades. */
private const val SEEK_TOTAL_SHOWN_MILLIS = 1_500L

/** A seek total as a viewer reads it: −30s, +1:00, −1:30. */
internal fun Long.toSeekLabel(): String {
    val sign = if (this < 0) "−" else "+"
    val seconds = kotlin.math.abs(this) / 1_000
    return if (seconds < 60) "$sign${seconds}s" else sign + (seconds * 1_000).toTimeString()
}

internal fun Long.toTimeString(): String {
    val totalSeconds = (this / 1_000).coerceAtLeast(0)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(java.util.Locale.ROOT, hours, minutes, seconds)
           else "%d:%02d".format(java.util.Locale.ROOT, minutes, seconds)
}

private fun String.toRepeatLabel(): String = when (this) {
    RepeatModes.ALL -> "Repeat All"
    RepeatModes.ONE -> "Repeat One"
    else -> "Repeat OFF"
}

/** A thumb, filled once the track is already rated that way and outlined otherwise. */
@Composable
private fun RateButton(up: Boolean, selected: Boolean, onClick: () -> Unit) {
    AppButton(onClick = onClick) {
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
        )
    }
}

/** One line for a household with UPnP switched off, naming what it costs and where to fix it. */
@Composable
private fun UpnpOffNote() {
    Text(
        "UPnP is off for this system, so the queue, the sleep timer, the TV input and Night " +
            "Sound and Speech Enhancement can't be used. Turn it on in the Sonos app: Account > Privacy and " +
            "Security > Connection Security.",
        style = MaterialTheme.typography.bodySmall,
    )
}
