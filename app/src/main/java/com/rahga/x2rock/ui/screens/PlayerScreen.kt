package com.rahga.x2rock.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.animateColorAsState
import com.rahga.x2rock.ui.theme.rememberArtColors
import com.rahga.x2rock.ui.theme.IconAppButton
import androidx.compose.ui.graphics.Brush
import androidx.tv.material3.SurfaceDefaults
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import com.rahga.x2rock.viewmodel.roomActivity
import com.rahga.x2rock.viewmodel.hasSource
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOn
import androidx.compose.material.icons.filled.RepeatOneOn
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ShuffleOn
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.tv.material3.ClickableSurfaceDefaults
import com.rahga.x2rock.lan.SNOOZE_MINUTES
import com.rahga.x2rock.smapi.Thumb
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.rahga.x2rock.ui.components.StepMark
import com.rahga.x2rock.ui.components.VOLUME_STEP
import com.rahga.x2rock.ui.components.mutedInk
import com.rahga.x2rock.ui.components.stepMarksShown
import com.rahga.x2rock.ui.components.tapToClick
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
    /** The Queue and Browse buttons, so Back from those screens can return to them. */
    queueFocusRequester: FocusRequester = remember { FocusRequester() },
    browseFocusRequester: FocusRequester = remember { FocusRequester() },
    searchFocusRequester: FocusRequester = remember { FocusRequester() },
    onOpenQueue: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenSearch: () -> Unit = {},
    /**
     * Where focus starts instead, when this pane is composed on the way back from a screen one of
     * its buttons opened: that button. See the note on the grab below.
     */
    startFocusRequester: FocusRequester? = null,
) {
    val state by viewModel.uiState.collectAsState()
    // The cover's own colour behind the pane, as the Sonos app tints Now Playing — eased, so a
    // track change washes over rather than flashes. The plain surface where there is no cover.
    val surface = MaterialTheme.colorScheme.surface
    val tint by animateColorAsState(
        rememberArtColors(state.albumArtUrl.takeIf { !state.onTvInput })?.shade ?: surface,
        animationSpec = tween(600),
        label = "artTint",
    )
    var showSleepTimerPicker by remember { mutableStateOf(false) }
    var paneHasFocus by remember { mutableStateOf(false) }

    // Where focus starts, once, for the life of this pane.
    //
    // Deliberately not inside the controls themselves. The music pane and the TV pane are
    // separate composables, so every change of source re-enters one of them — and a grab
    // there pulls focus out of the room list while the viewer is still arrowing through it.
    // HomeScreen owns the policy otherwise: it requests this pane on a right-press, when the
    // sidebar hides, and on resume.
    //
    // On the way back from Queue, Browse or Search it starts on that button instead, from the first
    // frame. Starting on the primary control and leaving HomeScreen's resume handler to move it
    // showed focus on the wrong button for about a second — the resume comes only after the
    // navigation's fade — and while the room's state was still arriving the primary control was
    // Browse, so it read as Browse, then Queue (Streamer, 2026-10-07). Retried for a few frames,
    // since the button is composed with the controls, after this pane.
    LaunchedEffect(Unit) {
        startFocusRequester?.let { start ->
            repeat(10) {
                if (start.requestFocusSafely()) return@LaunchedEffect
                withFrameNanos { }
            }
        }
        detailFocusRequester.requestFocusSafely()
    }

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
    //
    // Only on a *change* of source, never on the first composition: there the pane may already hold
    // focus — the button Back returned to — and this took it straight to the primary control,
    // which on a room still reading as empty is Browse. Seen on the Streamer: Back from Queue put
    // focus on Browse until the resume handler moved it, about a second later.
    var lastOnTvInput by remember { mutableStateOf(state.onTvInput) }
    LaunchedEffect(state.onTvInput) {
        if (state.onTvInput == lastOnTvInput) return@LaunchedEffect
        lastOnTvInput = state.onTvInput
        if (paneHasFocus) detailFocusRequester.requestFocusSafely()
    }

    // The picker traps focus, so closing it has to hand focus back explicitly — to Zzz, the
    // button that opened it, where the remote was; the pane's first control only if Zzz has
    // gone meanwhile. Not on the first composition, which would start the pane on the timer.
    val sleepFocusRequester = remember { FocusRequester() }
    var pickerOpened by remember { mutableStateOf(false) }
    LaunchedEffect(showSleepTimerPicker) {
        when {
            showSleepTimerPicker -> pickerOpened = true
            // The first composition: where focus starts is the grab above's to decide. Requesting
            // the primary control here too overrode it, sending Back from Queue to Browse.
            !pickerOpened -> Unit
            !sleepFocusRequester.requestFocusSafely() -> detailFocusRequester.requestFocusSafely()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(tint, surface)))) {
        Surface(
            // Transparent so the art's tint shows through; the content colour is named, because one
            // derived from a transparent container came out black on the dark pane.
            colors = SurfaceDefaults.colors(containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface),
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
                    .padding(horizontal = 48.dp, vertical = 32.dp),
                // Tight enough that the second row of buttons sits inside the screen's safe area
                // at 1080p with a two-line title; at 32dp it touched the bottom edge.
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // A television input is a different source, not the music pane with pieces
                // missing, so it gets its own header and its own controls.
                if (state.onTvInput) {
                    TvInfo(state, viewModel, sidebarFocusRequester)
                    TvControls(
                        state = state,
                        viewModel = viewModel,
                        firstFocusRequester = detailFocusRequester,
                        exitLeftFocusRequester = sidebarFocusRequester,
                        onOpenFavorites = onOpenFavorites,
                        onOpenSleepTimer = { showSleepTimerPicker = true },
                        sleepFocusRequester = sleepFocusRequester,
                        browseFocusRequester = browseFocusRequester,
                        searchFocusRequester = searchFocusRequester,
                        onOpenSearch = onOpenSearch,
                    )
                } else {
                    if (state.ringingAlarm != null) AlarmControls(viewModel, sidebarFocusRequester, detailFocusRequester)
                    TrackInfo(state, viewModel, sidebarFocusRequester)
                    PlaybackControls(
                        state = state,
                        viewModel = viewModel,
                        playPauseFocusRequester = detailFocusRequester,
                        exitLeftFocusRequester = sidebarFocusRequester,
                        onOpenQueue = onOpenQueue,
                        onOpenFavorites = onOpenFavorites,
                        onOpenSleepTimer = { showSleepTimerPicker = true },
                        sleepFocusRequester = sleepFocusRequester,
                        queueFocusRequester = queueFocusRequester,
                        browseFocusRequester = browseFocusRequester,
                        searchFocusRequester = searchFocusRequester,
                        onOpenSearch = onOpenSearch,
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
private fun TrackInfo(state: PlayerUiState, viewModel: PlayerViewModel, exitLeftFocusRequester: FocusRequester) {
    Column {
        PaneHeader(state, viewModel, exitLeftFocusRequester)
        Spacer(modifier = Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.albumArtUrl != null) {
                AsyncImage(
                    model = state.albumArtUrl,
                    contentDescription = "Album art",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(ART_SIZE)
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
    // A burst of presses or taps is measured from where its first one began.
    fun showSeek(total: (from: Long) -> Long) {
        if (!seekShown) seekFrom = displayPositionMillis
        seekTotal = total(seekFrom)
        seekShown = true
        seekPresses++
    }
    fun seek(delta: Long) {
        showSeek { from -> ((if (seekShown) seekTotal else 0L) + delta).coerceIn(-from, state.durationMillis - from) }
        onSeekBy(delta)
    }
    // A tap aims at a point rather than stepping, so it is sent as one; the total under the
    // bar still says how far that is from where the tap began.
    fun seekToFraction(fraction: Float) {
        val target = (fraction.coerceIn(0f, 1f) * state.durationMillis).toLong()
        showSeek { from -> target - from }
        onSeekTo(target)
    }
    val tapAt by rememberUpdatedState(::seekToFraction)
    val barColor = if (isFocused) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary

    Column {
        BoxWithConstraints(
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
                // Started once and reading the latest handler, as tapToClick does: the displayed
                // position is a new state at every report, and a stale handler measured from an
                // old one.
                .pointerInput(Unit) {
                    detectTapGestures { offset -> tapAt(offset.x / size.width) }
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
            // An 8dp bar recolouring to the focus tint was too faint to find across a room — a
            // press up from the transport read as focus vanishing. Focused, the bar grows and a
            // knob sits at the playhead, so where focus is and where a seek starts are unmistakable.
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(if (isFocused) 12.dp else 8.dp),
                color = barColor
            )
            if (isFocused) {
                val knob = 20.dp
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = (maxWidth - knob) * progress)
                        .size(knob)
                        .clip(CircleShape)
                        .background(barColor)
                )
            }
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val hint = MaterialTheme.colorScheme.secondary
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, tint = hint, modifier = Modifier.size(20.dp))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = hint, modifier = Modifier.size(20.dp))
                        Text(" seek 30s", style = MaterialTheme.typography.bodySmall, color = hint)
                    }
                }
            }
            Text(state.durationMillis.toTimeString(), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * A left-press here leaves the player pane for the room list.
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
    onOpenSleepTimer: () -> Unit,
    sleepFocusRequester: FocusRequester,
    queueFocusRequester: FocusRequester,
    browseFocusRequester: FocusRequester,
    searchFocusRequester: FocusRequester,
    onOpenSearch: () -> Unit,
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
            // Icons, as every television player draws a transport, and as the Sonos app does: as
            // labelled buttons ("⏮  Prev", "Next  ⏭") they took twice the width, which is what had
            // pushed the thumbs off the row. Spaced at 12dp so shuffle, the transport, repeat and a
            // pair of thumbs all fit the pane at 1080p.
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.focusGroup()
        ) {
            // Drawn only where the source permits them. A live stream refuses skip, seek
            // and shuffle outright, and a row of controls that cannot act is worse on a
            // remote than a shorter row: each is still a focus stop that does nothing.
            //
            // Shuffle and repeat flank the transport as icons, where the Sonos app puts them.
            // Like the rest of this row they are not drawn with no content, even though the
            // player reports both as available: `availablePlaybackActions` answers "what may I
            // do to this content", not "is there any".
            if (state.actions.canShuffle) {
                ModeButton(
                    icon = if (state.shuffle) Icons.Filled.ShuffleOn else Icons.Filled.Shuffle,
                    description = if (state.shuffle) "Shuffle, on" else "Shuffle, off",
                    onClick = { viewModel.toggleShuffle() },
                    modifier = Modifier.claimExit(transportExit),
                )
            }
            if (state.actions.canGoBack) {
                ModeButton(
                    icon = Icons.Filled.SkipPrevious,
                    description = "Previous",
                    onClick = { viewModel.skipToPreviousTrack() },
                    modifier = Modifier.claimExit(transportExit),
                )
            }
            AppButton(
                onClick = { viewModel.togglePlayPause() },
                // The widest control, so the one pressed most is the one found first; wide enough
                // that Play and Pause do not shuffle the row as it toggles.
                modifier = Modifier
                    .widthIn(min = 104.dp)
                    .focusRequester(playPauseFocusRequester)
                    .claimExit(transportExit)
            ) {
                // A live stream cannot be paused, only stopped: pausing one leaves the room
                // IDLE rather than PAUSED, verified on hardware. The command is the same
                // either way — the player does the right thing — so only the glyph changes,
                // to the one that describes what will actually happen.
                val (icon, description) = when {
                    !state.playbackState.isPlaying() -> Icons.Filled.PlayArrow to "Play"
                    state.actions.canPause -> Icons.Filled.Pause to "Pause"
                    else -> Icons.Filled.Stop to "Stop"
                }
                // Centred in the button's own minimum width, not the row's: on a stream it is the only
                // transport control, and a filling box stretched it across the pane.
                Box(Modifier.widthIn(min = 80.dp), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = description, modifier = Modifier.size(36.dp))
                }
            }
            if (state.actions.canSkip) {
                ModeButton(icon = Icons.Filled.SkipNext, description = "Next", onClick = { viewModel.skipToNextTrack() })
            }
            if (state.actions.canRepeat) {
                ModeButton(
                    icon = when (state.repeat) {
                        RepeatModes.ALL -> Icons.Filled.RepeatOn
                        RepeatModes.ONE -> Icons.Filled.RepeatOneOn
                        else -> Icons.Filled.Repeat
                    },
                    description = when (state.repeat) {
                        RepeatModes.ALL -> "Repeat all"
                        RepeatModes.ONE -> "Repeat one"
                        else -> "Repeat, off"
                    },
                    onClick = { viewModel.cycleRepeat() },
                )
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

        // Where to go from here, and crossfade. These used to share a row with shuffle and repeat
        // as labelled buttons, and at
        // 1080p on a TV — 960dp across, a third of it the room list — six buttons do not fit:
        // Favorites was squeezed to a sliver with its label wrapped a letter to a line, which
        // made the row hundreds of pixels tall and pushed the volume below the screen, and
        // Sleep Timer was off the edge entirely. Seen on the Shield and the emulator alike.
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
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
                    modifier = Modifier.focusRequester(queueFocusRequester).claimExit(placesExit),
                ) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Queue")
                }
            }
            // With no transport drawn there is nothing for a right-press from the room list
            // to land on, so the entry point moves here — which is also the one control that
            // helps, being how an empty room is given something to play. The sidebar has the
            // same destination at its top; this one is where the remote already is.
            AppButton(
                onClick = onOpenFavorites,
                modifier = Modifier.focusRequester(browseFocusRequester).claimExit(placesExit).then(
                    if (hasContent) Modifier else Modifier.focusRequester(playPauseFocusRequester)
                ),
            ) {
                Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("Browse")
            }
            SearchButton(onOpenSearch, searchFocusRequester)
            // With nothing loaded there is nothing for a timer to stop, so it goes with the
            // transport. Kept while one is running, so a timer is never there and unseen.
            if (hasContent || state.sleepTimerEndsAt != null) {
                SleepTimerButton(state, viewModel, onOpenSleepTimer, sleepFocusRequester)
            }
            // A setting of the room rather than of the track, so it sits with these rather than
            // the transport; the Sonos app keeps it a level further away still, in a menu.
            if (hasContent && state.actions.canCrossfade) {
                ModeButton(
                    icon = if (state.crossfade) CrossfadeOnIcon else CrossfadeIcon,
                    description = if (state.crossfade) "Crossfade, on" else "Crossfade, off",
                    onClick = { viewModel.toggleCrossfade() },
                )
            }
        }

        SpeakerRows(state, viewModel, exitLeftFocusRequester)
    }
}

/**
 * The room's name, and opposite it the group's level — identical on the music pane and the
 * TV one, because volume is the one control that means the same thing whatever the room is
 * playing, and it has to sit in the same place and answer the same presses when the source
 * changes.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PaneHeader(state: PlayerUiState, viewModel: PlayerViewModel, exitLeftFocusRequester: FocusRequester) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The room, and what it is doing in the word the room list and panel use too: a paused
        // stream and an idle room used to look the same here, and the panel called both "Idle".
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
            Text(
                text = state.groupName,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            val activity = roomActivity(state.playbackState, state.hasSource)
            Text(
                text = "  ·  " + if (state.onTvInput && activity.isPlaying) "TV" else activity.label,
                style = MaterialTheme.typography.titleMedium,
                color = if (activity.isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        HeaderVolume(
            state,
            onStep = { viewModel.adjustVolume(it) },
            onToggleMute = { viewModel.toggleMute() },
            onExitLeft = { exitLeftFocusRequester.requestFocusSafely() },
        )
    }
}

/**
 * Speaker, wedge, level: one control. Select mutes; left and right step the level while it
 * has focus, with − and + drawn either side to say so. Deliberately small — the remote's own
 * volume keys are the television's (see CLAUDE.md), and the system draws its own display
 * for those, so this is for the room rather than a second volume screen.
 *
 * Left steps down rather than leaving for the room list, as the room panel's levels do; up
 * and down move on. The − and + keep their space when hidden, so focusing the control
 * does not shift the room name. By touch they are always shown, and are their own targets;
 * a tap anywhere else on the control mutes.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HeaderVolume(state: PlayerUiState, onStep: (Int) -> Unit, onToggleMute: () -> Unit, onExitLeft: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    // A fixed line-out has no level to step: the view model says so if a step is tried.
    val showSteps = !state.volumeFixed && stepMarksShown(focused)
    Surface(
        onClick = onToggleMute,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    // A fixed line-out has no level to step, so left does what it does from any
                    // row's leftmost control: back to the room list. Otherwise every press only
                    // posted the "fixed" notice and the remote stayed put.
                    Key.DirectionLeft -> { if (state.volumeFixed) onExitLeft() else onStep(-VOLUME_STEP); true }
                    Key.DirectionRight -> { onStep(+VOLUME_STEP); true }
                    else -> false
                }
            }
            .tapToClick(onToggleMute),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StepMark("\u2212", showSteps, MaterialTheme.typography.titleMedium) { onStep(-VOLUME_STEP) }
            Icon(
                imageVector = if (state.isMuted) Icons.AutoMirrored.Filled.VolumeOff
                else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = if (state.isMuted) "Unmute" else "Mute",
                modifier = Modifier.size(30.dp),
            )
            if (!state.volumeFixed) VolumeWedge(state.volume, state.isMuted)
            Text(
                text = when {
                    state.volumeFixed -> "Fixed"
                    state.volume == null -> "\u2014"
                    else -> state.volume.toString()
                },
                // The size a level is read at across a room: the remote's own keys go to the
                // television over CEC, so this is the one level on screen for the room itself.
                style = MaterialTheme.typography.headlineSmall,
                // Wide enough for "100", so the control does not change size as the level moves.
                modifier = Modifier.widthIn(min = 52.dp),
            )
            StepMark("+", showSteps, MaterialTheme.typography.titleMedium) { onStep(+VOLUME_STEP) }
        }
    }
}

/**
 * The level as a wedge rising left to right, filled up to it — the shape Android's own volume
 * display uses. In the content colour, so it inverts with the focused container as the room
 * panel's bars do; dimmed while muted, keeping the level the speaker remembers.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun VolumeWedge(volume: Int?, muted: Boolean) {
    val ink = mutedInk(muted)
    Canvas(modifier = Modifier.size(width = 84.dp, height = 28.dp)) {
        val wedge = Path().apply {
            moveTo(0f, size.height)
            lineTo(size.width, 0f)
            lineTo(size.width, size.height)
            close()
        }
        drawPath(wedge, ink.copy(alpha = ink.alpha * 0.25f))
        if (volume != null && volume > 0) {
            clipRect(right = size.width * volume / 100f) { drawPath(wedge, ink) }
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
private fun TvInfo(state: PlayerUiState, viewModel: PlayerViewModel, exitLeftFocusRequester: FocusRequester) {
    Column {
        PaneHeader(state, viewModel, exitLeftFocusRequester)
        Spacer(modifier = Modifier.height(16.dp))
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
 * the header's volume — which is the one control that does mean the same thing either way.
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
    sleepFocusRequester: FocusRequester,
    browseFocusRequester: FocusRequester,
    searchFocusRequester: FocusRequester,
    onOpenSearch: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Written over UPnP, so with it off they are not drawn at all; the room list says why,
        // once, and Favorites takes over as where focus enters.
        if (!state.upnpOff) {
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
        }

        Spacer(modifier = Modifier.height(24.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.focusGroup(),
        ) {
            // The way out of the television and back into music. Sonos's own TV screen has no
            // such row, but a room on its HDMI input is exactly where wanting to put music on
            // instead is a live thought, and nothing else here offers it.
            AppButton(
                onClick = onOpenFavorites,
                modifier = Modifier
                    .focusRequester(browseFocusRequester)
                    .exitLeftTo(exitLeftFocusRequester)
                    .then(if (state.upnpOff) Modifier.focusRequester(firstFocusRequester) else Modifier),
            ) {
                Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("Browse")
            }
            SearchButton(onOpenSearch, searchFocusRequester)
            SleepTimerButton(state, viewModel, onOpenSleepTimer, sleepFocusRequester)
        }

        SpeakerRows(state, viewModel, exitLeftFocusRequester)
    }
}

/**
 * Search every service at once, for this room. Here, beside Browse, rather than above the room
 * list: the list selects the room focus rests on, so walking up it to a button at its top selected
 * every room on the way, and Browse opened "in Bedroom" when the walk began on Kitchen (Streamer,
 * 2026-10-06). From the pane, the room is the one already chosen.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchButton(onClick: () -> Unit, focusRequester: FocusRequester) {
    AppButton(onClick = onClick, modifier = Modifier.focusRequester(focusRequester)) {
        Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        Text("Search")
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
            modifier = Modifier.width(150.dp),
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
            ) { Icon(Icons.Filled.Remove, contentDescription = "Quieter") }
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
            ) { Icon(Icons.Filled.Add, contentDescription = "Louder") }
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

    Overlay(onDismiss) {
        Column(
            modifier = Modifier
                .width(440.dp)
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Sleep Timer", style = MaterialTheme.typography.titleLarge)
            // Up to two hours, as the Sonos app offers: a film or an album outlasts sixty minutes.
            // In pairs, so six choices fit the screen's height with Cancel still on it.
            SLEEP_CHOICES.chunked(2).forEachIndexed { row, pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEachIndexed { column, minutes ->
                        AppButton(
                            onClick = { onSelect(minutes) },
                            modifier = Modifier
                                .weight(1f)
                                .then(if (row == 0 && column == 0) Modifier.focusRequester(firstFocus) else Modifier)
                        ) {
                            Text(sleepChoiceLabel(minutes), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            AppButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
        }
    }
}

private val SLEEP_CHOICES = listOf(15, 30, 45, 60, 90, 120)

/** "15 minutes", "1 hour", "1½ hours", "2 hours". */
internal fun sleepChoiceLabel(minutes: Int): String = when {
    minutes < 60 -> "$minutes minutes"
    minutes == 60 -> "1 hour"
    minutes % 60 == 0 -> "${minutes / 60} hours"
    else -> "${minutes / 60}\u00BD hours"
}

/** Each speaker's own level, for a group of more than one. Drawn under both panes' controls. */
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
            .size(ART_SIZE)
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
private fun SleepTimerButton(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    onOpenSleepTimer: () -> Unit,
    focusRequester: FocusRequester,
) {
    if (state.upnpOff) return
    val sleepLeft = rememberSleepCountdown(state.sleepTimerEndsAt)
    val context = LocalContext.current
    AppButton(
        onClick = { if (sleepLeft != null) viewModel.cancelSleepTimer() else onOpenSleepTimer() },
        modifier = Modifier.focusRequester(focusRequester),
    ) {
        Icon(
            imageVector = SleepIcon,
            contentDescription = if (sleepLeft != null) "Sleep timer, ${sleepLeft.toTimeString()} left. Press to cancel"
            else "Sleep timer",
            modifier = Modifier.size(30.dp),
        )
        if (sleepLeft != null) {
            Spacer(modifier = Modifier.width(8.dp))
            // The clock time it ends, which is what someone going to bed is asking — in the device's
            // own 12- or 24-hour form. One short line: "14:34 · ends 8:58 PM" wrapped to four lines
            // in the pane's button row at 1080p (Streamer, 2026-10-06). What is left stays in the
            // description, and the time it ends does not tick.
            val endsAt = remember(state.sleepTimerEndsAt) {
                android.text.format.DateFormat.getTimeFormat(context)
                    .format(java.util.Date(System.currentTimeMillis() + sleepLeft))
            }
            Text(endsAt, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * The sleep timer as "Zzz", drawn: Material has nothing that means sleep and nothing else.
 * The moon is Night Sound's — the Sonos app's symbol for it, and on the TV pane the two sit a
 * row apart — and Snooze's alarm clock is already the ringing alarm's Snooze button.
 */
private val SleepIcon: ImageVector =
    ImageVector.Builder(
        name = "Sleep", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        // Three Zs, each smaller and higher than the last, with clear space between them —
        // touching, they read as a staircase. The strokes thin with them so the smallest is
        // still a Z at three metres rather than a blot.
        zee(left = 1.5f, top = 16f, right = 7.5f, bottom = 22f, weight = 1.7f)
        zee(left = 11f, top = 9f, right = 15f, bottom = 13f, weight = 1.4f)
        zee(left = 18f, top = 2.5f, right = 21f, bottom = 5.5f, weight = 1.2f)
    }.build()

private fun ImageVector.Builder.zee(left: Float, top: Float, right: Float, bottom: Float, weight: Float) {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = weight, strokeLineJoin = StrokeJoin.Miter) {
        moveTo(left, top)
        lineTo(right, top)
        lineTo(left, bottom)
        lineTo(right, bottom)
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

/** The cover's size in the pane: large, but not so large the second row of buttons leaves the screen. */
private val ART_SIZE = 168.dp

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

/**
 * A play mode as an icon. Its state is in the glyph's shape — Material's "on" forms sit in a
 * filled square — rather than in colour alone, which a focused button's own colour would hide.
 */
@Composable
private fun ModeButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconAppButton(onClick = onClick, modifier = modifier) {
        // Larger than the default 24dp: at three metres the "on" forms' filled square was all
        // that read, not the glyph inside it.
        Icon(imageVector = icon, contentDescription = description, modifier = Modifier.size(30.dp))
    }
}

/**
 * Crossfade, which Material has no icon for: a fade-out and a fade-in crossing, the bowtie
 * audio editors draw — outlined when off. On, it is cut out of a filled square, as Material's
 * ShuffleOn and RepeatOn are, so the three modes say "on" the same way.
 */
private val CrossfadeIcon: ImageVector = crossfadeIcon(on = false)
private val CrossfadeOnIcon: ImageVector = crossfadeIcon(on = true)

private fun crossfadeIcon(on: Boolean): ImageVector =
    ImageVector.Builder(
        name = if (on) "CrossfadeOn" else "Crossfade",
        defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        if (on) {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                moveTo(5f, 3f)
                lineTo(19f, 3f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 21f, 5f)
                lineTo(21f, 19f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 19f, 21f)
                lineTo(5f, 21f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 3f, 19f)
                lineTo(3f, 5f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 5f, 3f)
                close()
                bowtie(left = 6.5f, right = 17.5f, top = 8f, bottom = 16f)
            }
        } else {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineJoin = StrokeJoin.Round) {
                bowtie(left = 4f, right = 20f, top = 7f, bottom = 17f)
            }
        }
    }.build()

/** Two triangles meeting at the centre: one tall on the left, the other tall on the right. */
private fun PathBuilder.bowtie(left: Float, right: Float, top: Float, bottom: Float) {
    val midX = (left + right) / 2
    val midY = (top + bottom) / 2
    moveTo(left, top)
    lineTo(midX, midY)
    lineTo(right, top)
    lineTo(right, bottom)
    lineTo(midX, midY)
    lineTo(left, bottom)
    close()
}

/** A thumb, filled once the track is already rated that way and outlined otherwise. */
@Composable
private fun RateButton(up: Boolean, selected: Boolean, onClick: () -> Unit) {
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
