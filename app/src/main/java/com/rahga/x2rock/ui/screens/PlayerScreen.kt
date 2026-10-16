package com.rahga.x2rock.ui.screens

import androidx.compose.foundation.horizontalScroll
import kotlinx.coroutines.flow.drop
import androidx.compose.runtime.snapshotFlow
import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.viewmodel.trackLine
import com.rahga.x2rock.ui.components.DotScanner
import com.rahga.x2rock.ui.components.NoticeBanner
import com.rahga.x2rock.ui.components.exitOnKey
import com.rahga.x2rock.ui.theme.requestFocusRetrying
import com.rahga.x2rock.ui.theme.LocalArtColors
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.graphics.Brush
import androidx.tv.material3.SurfaceDefaults
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import com.rahga.x2rock.viewmodel.roomActivity
import com.rahga.x2rock.viewmodel.hasSource
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOn
import androidx.compose.material.icons.filled.RepeatOneOn
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ShuffleOn
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import com.rahga.x2rock.ui.serviceLogo
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Tv
import com.rahga.x2rock.lan.SNOOZE_MINUTES
import com.rahga.x2rock.smapi.Thumb
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.rahga.x2rock.model.RepeatModes
import com.rahga.x2rock.model.isPlaying
import com.rahga.x2rock.ui.components.handOffFocus
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.rahga.x2rock.ui.theme.AppButton
import com.rahga.x2rock.ui.theme.requestFocusSafely
import com.rahga.x2rock.viewmodel.PlayerUiState
import com.rahga.x2rock.viewmodel.PlayerVolumeEntry
import com.rahga.x2rock.viewmodel.PlayerViewModel
import com.rahga.x2rock.viewmodel.upNextLine
import com.rahga.x2rock.viewmodel.previousLine

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
    /** The Queue, Browse and Search buttons, so Back from those screens can return to them. */
    queueFocusRequester: FocusRequester,
    browseFocusRequester: FocusRequester,
    searchFocusRequester: FocusRequester,
    /** The Full screen button, for the same reason. */
    nowPlayingFocusRequester: FocusRequester,
    /** The Group button, so closing the room panel it opened can return to it. */
    groupFocusRequester: FocusRequester,
    onOpenQueue: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    /** Open the room panel: grouping, then the room's settings. */
    onOpenGroup: () -> Unit,
    /**
     * Where focus starts instead, when this pane is composed on the way back from a screen one of
     * its buttons opened: that button. See the note on the grab below.
     */
    startFocusRequester: FocusRequester?,
) {
    val state by viewModel.uiState.collectAsState()
    // The cover's own colour behind the pane, as the Sonos app tints Now Playing — eased, so a
    // track change washes over rather than flashes. The plain surface where there is no cover.
    val surface = MaterialTheme.colorScheme.surface
    val tint by animateColorAsState(
        LocalArtColors.current?.shade?.takeIf { !state.onTvInput } ?: surface,
        animationSpec = tween(600),
        label = "artTint",
    )
    var showSleepTimerPicker by remember { mutableStateOf(false) }
    // The exact-level entry, opened by a hold on the volume; focus goes back to the volume after.
    var showSetVolume by remember { mutableStateOf(false) }
    val volumeFocusRequester = remember { FocusRequester() }
    // An overlay closing here gives focus back before it goes; see [handOffFocus].
    var overlayReleasing by remember { mutableStateOf(false) }
    val closeScope = rememberCoroutineScope()
    fun closeOverlay(close: () -> Unit, vararg targets: FocusRequester) {
        closeScope.launch { handOffFocus({ overlayReleasing = it }, close, *targets) }
    }
    // The art names its service while the volume or the seek bar has focus: the two controls a
    // viewer stops on while the music plays, rather than passes through.
    val volumeFocused = remember { mutableStateOf(false) }
    var seekFocused by remember { mutableStateOf(false) }
    // What Next or Prev goes to is named between the seek bar's times while that button has focus:
    // the one moment it answers a question, and less on screen otherwise. Under the transport, a
    // line of its own pushed the places row past the safe area on a two-line title (Shield,
    // 2026-10-10).
    var nextFocused by remember { mutableStateOf(false) }
    var previousFocused by remember { mutableStateOf(false) }
    val volumeEntry = VolumeEntry(open = { showSetVolume = true }, focus = volumeFocusRequester, focused = volumeFocused)
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
        if (startFocusRequester?.requestFocusRetrying() == true) return@LaunchedEffect
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
    LaunchedEffect(Unit) {
        snapshotFlow { state.onTvInput }.drop(1).collect {
            if (paneHasFocus) detailFocusRequester.requestFocusSafely()
        }
    }

    // The picker traps focus, so closing it hands focus back to Zzz, the button that opened it,
    // where the remote was; the pane's first control only if Zzz has gone meanwhile.
    val sleepFocusRequester = remember { FocusRequester() }

    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(tint, surface)))) {
        Surface(
            // Transparent so the art's tint shows through. The remote's transport keys are
            // MainActivity's, for every screen alike, so none are handled here.
            colors = SurfaceDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .fillMaxSize()
                .onFocusChanged { paneHasFocus = it.hasFocus }
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
                // The pane keeps one shape down to and including the seek bar's slot, whatever the
                // room plays — art tile or its blank, title, bar or its blank — so walking the room
                // list moves nothing above the transport. Below it, each source has its own rows.
                if (state.onTvInput) {
                    TvInfo(state, viewModel, sidebarFocusRequester, volumeEntry)
                    ProgressBar(state, onSeekBy = {}, onSeekTo = {})
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
                        groupFocusRequester = groupFocusRequester,
                        onOpenGroup = onOpenGroup,
                    )
                } else {
                    TrackInfo(state, viewModel, sidebarFocusRequester, volumeEntry, showService = volumeFocused.value || seekFocused)
                    ProgressBar(
                        state,
                        onSeekBy = { viewModel.seekBy(it) },
                        onSeekTo = { viewModel.seekTo(it) },
                        onFocused = { seekFocused = it },
                        caption = when {
                            nextFocused && state.actions.canSkip -> state.upNextLine()
                            previousFocused && state.actions.canSkipToPrevious -> state.previousLine()
                            else -> null
                        },
                    )
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
                        nowPlayingFocusRequester = nowPlayingFocusRequester,
                        onOpenNowPlaying = onOpenNowPlaying,
                        groupFocusRequester = groupFocusRequester,
                        onOpenGroup = onOpenGroup,
                        onNextFocused = { nextFocused = it },
                        onPreviousFocused = { previousFocused = it },
                        alarm = if (state.ringingAlarm != null) { { AlarmControls(viewModel, sidebarFocusRequester, detailFocusRequester) } } else null,
                    )
                }
            }
        }

        // What did not work — "Couldn't seek: …" — floats over the foot of the pane for a few
        // seconds, as the home screen's does over the room panel: a line among the rows used to
        // push the places below it down and vanish again, and read as part of the pane.
        state.notice?.let { NoticeBanner(it, Modifier.align(Alignment.BottomCenter)) }

        if (showSetVolume) {
            SetVolumeOverlay(
                room = state.groupName,
                current = state.volume,
                trapped = !overlayReleasing,
                onSet = { level ->
                    viewModel.setVolume(level)
                    closeOverlay({ showSetVolume = false }, volumeFocusRequester)
                },
                onDismiss = { closeOverlay({ showSetVolume = false }, volumeFocusRequester) },
            )
        }

        if (showSleepTimerPicker) {
            SleepTimerPickerOverlay(
                trapped = !overlayReleasing,
                onSelect = { minutes ->
                    viewModel.setSleepTimer(minutes)
                    closeOverlay({ showSleepTimerPicker = false }, sleepFocusRequester, detailFocusRequester)
                },
                onDismiss = { closeOverlay({ showSleepTimerPicker = false }, sleepFocusRequester, detailFocusRequester) }
            )
        }
    }
}

/**
 * A ringing alarm's two answers, in the places row's slot below the transport while it rings: as a
 * row of their own above the track they pushed that row off the screen (P-25, 34px at 1080p with a
 * two-line title, Shield and emulator alike). Play/Pause keeps the focus it had: pressing it, or the
 * remote's play/pause key, stops the alarm too, which is what someone half awake reaches for.
 * Snooze is one press down. A snoozed alarm is not offered here: the
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
private fun TrackInfo(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    exitLeftFocusRequester: FocusRequester,
    volumeEntry: VolumeEntry,
    /** Draw the service's logo in the art's corner. */
    showService: Boolean,
) {
    Column {
        PaneHeader(state, viewModel, exitLeftFocusRequester, volumeEntry)
        Spacer(modifier = Modifier.height(16.dp))
        // The tile's slot is always held, occupied or not, so every title starts at the same place
        // and the row keeps its height — the room list's 56dp rule, at the pane's size. Blank for
        // a room with nothing to show, as a grey square would assert something that is not there.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = ART_SIZE)) {
            if (state.albumArtUrl != null) {
                Box(Modifier.size(ART_SIZE).clip(RoundedCornerShape(8.dp))) {
                    AsyncImage(
                        model = state.albumArtUrl,
                        contentDescription = "Album art",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Which service this is playing from, in the art's bottom-left fifth: only
                    // squares are published (Sonos's own list, see serviceLogo), so it sits on the
                    // art rather than beside the text. Shown while the volume or the seek bar has
                    // focus, so it never sits over the cover otherwise.
                    val logo = state.serviceName?.let(::serviceLogo)
                    if (showService && logo != null) {
                        AsyncImage(
                            model = logo,
                            contentDescription = "Playing from ${state.serviceName}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.align(Alignment.BottomStart).fillMaxSize(0.2f),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(32.dp))
            } else if (state.isRadio) {
                // A station logo is shown where there is one — many services carry it — and
                // this stands in where there is not. A stream loaded by its URL really does
                // send `images: []`, so there is nothing to fetch and nothing to wait for.
                GlyphTile(Icons.Default.Radio)
                Spacer(modifier = Modifier.width(32.dp))
            } else {
                Spacer(modifier = Modifier.size(ART_SIZE))
                Spacer(modifier = Modifier.width(32.dp))
            }
            Column {
                when {
                    state.isLoading -> DotScanner()
                    state.trackName != null -> {
                        Text(
                            text = state.trackName,
                            style = MaterialTheme.typography.displaySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        val subtitle = state.trackLine()
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
                    // host: x2rock's rule, and better than a transport word. The same goes for
                    // anything else that names its source and not a track: a radio program
                    // stopped between songs (Radio Paradise's Main Mix) read "Idle" here.
                    state.sourceName != null -> Text(
                        text = state.sourceName,
                        style = MaterialTheme.typography.displaySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    // "Idle" describes the transport and says nothing a listener can use. With
                    // nothing loaded at all the Sonos app says "No Content", and so does this;
                    // otherwise the room's own word, the one the list and the panel use.
                    else -> Text(
                        text = if (!state.actions.canPlay) "No Content"
                            else roomActivity(state.playbackState, state.hasSource).label,
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

/**
 * What the play/pause control shows: Play when not playing; Pause, or Stop for a live stream that
 * cannot pause — pausing one leaves the room IDLE (verified on hardware), so Stop is what happens.
 * The pane's button and the now-playing bar share it so the rule cannot drift.
 */
internal fun PlayerUiState.playPauseGlyph(): Pair<ImageVector, String> = when {
    !playbackState.isPlaying() -> Icons.Filled.PlayArrow to "Play"
    actions.canPause -> Icons.Filled.Pause to "Pause"
    else -> Icons.Filled.Stop to "Stop"
}

/**
 * A left-press here leaves the player pane for the room list.
 *
 * Put on the leftmost control of each row. Declaring `left` on the pane instead does not
 * work: a focus search starting inside it escapes rather than being redirected, and focus
 * is lost altogether — which on a remote leaves nothing to press but Back.
 */
private fun Modifier.exitLeftTo(target: FocusRequester): Modifier = exitOnKey(Key.DirectionLeft, target)

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
    nowPlayingFocusRequester: FocusRequester,
    onOpenNowPlaying: () -> Unit,
    groupFocusRequester: FocusRequester,
    onOpenGroup: () -> Unit,
    onNextFocused: (Boolean) -> Unit,
    onPreviousFocused: (Boolean) -> Unit,
    /** A ringing alarm's controls, drawn in place of the places row while it rings; see [AlarmControls]. */
    alarm: (@Composable () -> Unit)? = null,
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

    // The pane's full width, so the rows centre on the pane: as wide as its widest row only, the
    // column sat at the left and centred them on itself, off the pane's middle.
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val transportExit = booleanArrayOf(false)
        if (hasContent) {
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
                        modifier = Modifier.claimExit(transportExit).onFocusChanged { onPreviousFocused(it.isFocused) },
                    )
                }
                AppButton(
                    onClick = { viewModel.togglePlayPause() },
                    // Holding it is thumbs up, where the thumbs are offered: the remote's star button,
                    // the obvious key for it, is Google TV's own and never reaches an app (it is a
                    // global key, handed to the launcher). The thumbs beside it stay the visible way.
                    onLongClick = state.rating?.let { { viewModel.rateUp() } },
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
                    val (icon, description) = state.playPauseGlyph()
                    // Centred in the button's own minimum width, not the row's: on a stream it is the only
                    // transport control, and a filling box stretched it across the pane.
                    Box(Modifier.widthIn(min = 80.dp), contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = description, modifier = Modifier.size(36.dp))
                    }
                }
                if (state.actions.canSkip) {
                    ModeButton(
                        icon = Icons.Filled.SkipNext,
                        description = "Next",
                        onClick = { viewModel.skipToNextTrack() },
                        modifier = Modifier.onFocusChanged { onNextFocused(it.isFocused) },
                    )
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
                    if (rating.style == SonosHousehold.RatingStyle.FAVORITE) {
                        // Deezer's pair, as the Sonos app draws it: a heart that toggles, and a ban
                        // that skips the track and so is never shown as set.
                        HeartButton(favorite = rating.current == Thumb.UP) { viewModel.rateUp() }
                        BanButton { viewModel.rateDown() }
                    } else {
                        RateButton(up = true, selected = rating.current == Thumb.UP) { viewModel.rateUp() }
                        RateButton(up = false, selected = rating.current == Thumb.DOWN) { viewModel.rateDown() }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Where to go from here. These used to share a row with shuffle and repeat as labelled
        // buttons, and at 1080p on a TV — 960dp across, a third of it the room list — six buttons
        // do not fit: Favorites was squeezed to a sliver with its label wrapped a letter to a line,
        // which made the row hundreds of pixels tall and pushed the volume below the screen, and
        // Sleep Timer was off the edge entirely. Seen on the Shield and the emulator alike.
        // With Group added it is one button wider than the pane again (Sleep was cut off on the
        // Streamer), so the row scrolls, as rows on a TV do: focus moving right brings the next
        // button into view, and the sliver at the edge says there is one.
        // Icons since 2026-10-09, each named under it while it has focus: as labelled buttons the
        // row overran the pane and scrolled, which left "Queue" cut in half at the left edge.
        // While an alarm rings its answers take this row's place: nowhere to go matters then, and
        // everything above stays where it was.
        if (alarm != null) alarm() else Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
            // The scroll clips at its edges, which cut a focused end button's name ("Sleep tim");
            // so it reaches LABEL_OVERHANG past the pane on both sides and its content is inset by
            // the same, leaving the buttons where they were and room for a name at either end.
            modifier = Modifier
                .overhang(LABEL_OVERHANG)
                .focusGroup()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = LABEL_OVERHANG)
        ) {
            val placesExit = booleanArrayOf(false)
            // Queue keeps its place whatever the source permits: it is a way to look at what
            // is loaded rather than an action on the current item. Only UPnP being off takes
            // it away, since the queue lives nowhere else; the pane says so below.
            if (!state.upnpOff) {
                PlaceButton(
                    Icons.AutoMirrored.Filled.QueueMusic, "Queue", onOpenQueue,
                    Modifier.focusRequester(queueFocusRequester).claimExit(placesExit),
                )
            }
            // With no transport drawn there is nothing for a right-press from the room list
            // to land on, so the entry point moves here — which is also the one control that
            // helps, being how an empty room is given something to play.
            PlaceButton(
                Icons.AutoMirrored.Filled.LibraryBooks, "Browse", onOpenFavorites,
                Modifier.focusRequester(browseFocusRequester).claimExit(placesExit).then(
                    if (hasContent) Modifier else Modifier.focusRequester(playPauseFocusRequester)
                ),
            )
            PlaceButton(Icons.Filled.Search, "Search", onOpenSearch, Modifier.focusRequester(searchFocusRequester))
            if (state.canSaveFavorite) {
                PlaceButton(Icons.Outlined.StarBorder, "Save favorite · Beta", viewModel::saveFavorite)
            }
            GroupButton(onOpenGroup, groupFocusRequester)
            // With nothing loaded there is nothing for a timer to stop, so it goes with the
            // transport. Kept while one is running, so a timer is never there and unseen.
            if (hasContent || state.sleepTimerEndsAt != null) {
                SleepTimerButton(state, viewModel, onOpenSleepTimer, sleepFocusRequester)
            }
            // The room's music on the whole screen, for an hour of listening; with nothing loaded
            // there is nothing to show there.
            if (hasContent) {
                PlaceButton(
                    Icons.Filled.Fullscreen, "Full screen", onOpenNowPlaying,
                    Modifier.focusRequester(nowPlayingFocusRequester),
                )
            }
        }

        SpeakerRows(state, viewModel, exitLeftFocusRequester)
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
private fun TvInfo(state: PlayerUiState, viewModel: PlayerViewModel, exitLeftFocusRequester: FocusRequester, volumeEntry: VolumeEntry) {
    Column {
        PaneHeader(state, viewModel, exitLeftFocusRequester, volumeEntry)
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = ART_SIZE),
        ) {
            GlyphTile(Icons.Default.Tv)
            Spacer(modifier = Modifier.width(32.dp))
            Column {
                Text("TV", style = MaterialTheme.typography.displaySmall)
                Spacer(modifier = Modifier.height(8.dp))
                Text("HDMI", style = MaterialTheme.typography.bodyLarge)
            }
            // Empty while the television is off or between sources, where the player reports
            // no signal at all — an empty string reads better than "No Signal 0.0". The rest of the
            // row, right-aligned with a gap: given only what it needed, "Dolby Digital Surround 5.1"
            // squeezed the spacer out and wrapped its "5.1" in beside "HDMI" (Shield, 2026-10-11).
            Text(
                state.inputFormat,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.End,
                maxLines = 2,
                modifier = Modifier.weight(1f).padding(start = 32.dp),
            )
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
    groupFocusRequester: FocusRequester,
    onOpenGroup: () -> Unit,
) {
    // The pane's full width, so the rows centre on the pane: as wide as its widest row only, the
    // column sat at the left and centred them on itself, off the pane's middle.
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
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
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
            modifier = Modifier.focusGroup(),
        ) {
            // The way out of the television and back into music. Sonos's own TV screen has no
            // such row, but a room on its HDMI input is exactly where wanting to put music on
            // instead is a live thought, and nothing else here offers it.
            PlaceButton(
                Icons.AutoMirrored.Filled.LibraryBooks, "Browse", onOpenFavorites,
                Modifier
                    .focusRequester(browseFocusRequester)
                    .exitLeftTo(exitLeftFocusRequester)
                    .then(if (state.upnpOff) Modifier.focusRequester(firstFocusRequester) else Modifier),
            )
            PlaceButton(Icons.Filled.Search, "Search", onOpenSearch, Modifier.focusRequester(searchFocusRequester))
            GroupButton(onOpenGroup, groupFocusRequester)
            SleepTimerButton(state, viewModel, onOpenSleepTimer, sleepFocusRequester)
        }

        SpeakerRows(state, viewModel, exitLeftFocusRequester)
    }
}

/*
 * Search sits beside Browse in the pane's row rather than above the room list: the list selects the
 * room focus rests on, so walking up it to a button at its top selected every room on the way, and
 * Browse opened "in Bedroom" when the walk began on Kitchen (Streamer, 2026-10-06). From the pane,
 * the room is the one already chosen.
 */

/** A setting and the value it currently holds, the way the Sonos app draws these two. */
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

/** The cover's size in the pane: large, but not so large the second row of buttons leaves the screen. */
private val ART_SIZE = 168.dp

/** A seek total as a viewer reads it: −30s, +1:00, −1:30. */
