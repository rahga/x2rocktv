package com.rahga.x2rock.ui.screens

import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.runtime.mutableLongStateOf
import android.os.SystemClock
import com.rahga.x2rock.viewmodel.label
import androidx.compose.runtime.rememberCoroutineScope
import com.rahga.x2rock.ui.components.handOffFocus
import kotlinx.coroutines.launch
import com.rahga.x2rock.ui.components.DotEqualizer
import com.rahga.x2rock.ui.components.DotScanner
import com.rahga.x2rock.ui.components.exitOnKey
import com.rahga.x2rock.ui.theme.requestFocusRetrying
import com.rahga.x2rock.ui.theme.IconLabelButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.draw.alpha
import com.rahga.x2rock.viewmodel.RoomActivity
import com.rahga.x2rock.viewmodel.roomActivity
import com.rahga.x2rock.viewmodel.hasSource
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import com.rahga.x2rock.lan.HouseholdChoice
import com.rahga.x2rock.ui.components.NoticeBanner
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.rahga.x2rock.model.AppColorTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.rahga.x2rock.model.Group
import com.rahga.x2rock.model.followingRoom
import com.rahga.x2rock.model.PlaybackStates
import com.rahga.x2rock.model.isPlaying
import com.rahga.x2rock.ui.components.Overlay
import com.rahga.x2rock.ui.components.dpadMenuKey
import com.rahga.x2rock.ui.components.keepTaps
import com.rahga.x2rock.ui.components.modalFocusTrap
import com.rahga.x2rock.ui.components.tapToClick
import com.rahga.x2rock.ui.theme.AppButton
import com.rahga.x2rock.ui.theme.AppCard
import com.rahga.x2rock.ui.theme.rememberAutoFocusRequester
import com.rahga.x2rock.ui.theme.requestFocusSafely
import com.rahga.x2rock.ui.theme.swatchColor
import com.rahga.x2rock.viewmodel.HomeViewModel
import com.rahga.x2rock.viewmodel.roomRowLines
import com.rahga.x2rock.viewmodel.PlayerViewModel
import com.rahga.x2rock.viewmodel.sortGroups

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(
    onOpenQueue: (groupId: String) -> Unit,
    onOpenFavorites: (groupId: String) -> Unit,
    onOpenSearch: (groupId: String) -> Unit,
    onOpenNowPlaying: (groupId: String) -> Unit,
    onOpenPreferredServices: () -> Unit,
    onOpenAlarms: () -> Unit,
    onOpenAbout: () -> Unit,
    /** Settings → Beta features → Send a report (T-21). */
    onOpenReport: () -> Unit,
    // Never defaulted to hiltViewModel(): inside the NavHost that would mint a second
    // PlayerViewModel owned by the back-stack entry, which would attach its own controls to the
    // publisher and never be told the room. NavGraph passes the activity's.
    homeViewModel: HomeViewModel,
    playerViewModel: PlayerViewModel,
    /**
     * Settings → Sonos account (T-27): offered only by a build carrying a developer integration, with
     * Beta features on; [accountSignedIn] is its state. Off by default, as in every build from the repo.
     */
    accountOffered: Boolean = false,
    accountSignedIn: Boolean = false,
    onOpenAccount: () -> Unit = {},
) {
    val state by homeViewModel.uiState.collectAsState()
    val selectedTheme by homeViewModel.selectedTheme.collectAsState()
    val selectedGroupId by homeViewModel.selectedGroupId.collectAsState()
    val upnpOff by homeViewModel.upnpOff.collectAsState()
    val betaEnabled by homeViewModel.betaEnabled.collectAsState()
    val roomRanks by homeViewModel.roomRanks.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var panelGroup by remember { mutableStateOf<Group?>(null) }
    // The room being moved in the list, picked up by a hold or Menu on it (P-20).
    var movingGroupId by remember { mutableStateOf<String?>(null) }

    val detailFocusRequester = remember { FocusRequester() }
    val sidebarFocusRequester = remember { FocusRequester() }

    // The controls that open another screen, so Back can return to the one that did. This screen
    // leaves the composition while another is shown, so which one it was is kept saveable; the
    // requesters are fresh on return, and found again by name.
    val queueFocus = remember { FocusRequester() }
    val browseFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val nowPlayingFocus = remember { FocusRequester() }
    // The pane's Group button, and whether the open panel came from it: closing the panel goes
    // back to whatever opened it, the button or the room.
    val groupFocus = remember { FocusRequester() }
    var panelFromPane by remember { mutableStateOf(false) }
    // Settings opens from its own button at the foot of the list, and closing it goes back there.
    val settingsButtonFocus = remember { FocusRequester() }
    var opener by rememberSaveable { mutableStateOf<Opener?>(null) }
    fun openerFocus(opener: Opener?): FocusRequester? = when (opener) {
        Opener.QUEUE -> queueFocus
        Opener.BROWSE -> browseFocus
        Opener.SEARCH -> searchFocus
        Opener.NOW_PLAYING -> nowPlayingFocus
        // Not the pane's: the Settings button is at the foot of the list; see the resume below.
        Opener.SETTINGS, null -> null
    }

    val groups = (state as? HomeViewModel.UiState.Success)?.groups ?: emptyList()
    val rooms = (state as? HomeViewModel.UiState.Success)?.rooms ?: emptyMap()

    // Keyed on groups too: a deep link can select a room before the group list has loaded, and
    // without the re-run the player pane would keep the empty name it resolved to first.
    LaunchedEffect(selectedGroupId, groups) {
        val id = selectedGroupId ?: return@LaunchedEffect
        val name = groups.find { it.id == id }?.name ?: return@LaunchedEffect
        playerViewModel.selectGroup(id, name)
    }

    // Selection follows focus only once this screen has put focus somewhere itself. Until then a
    // row taking focus is Android's automatic first focus, not the viewer: it lands on the first
    // focusable thing, which since Settings moved to the foot of the list is the first room — and
    // selecting it overrode the room named "This is my TV". Seen on the Shield, 2026-10-07: it
    // opened on Bedroom, with focus on Living Room. Settings at the top used to absorb that focus.
    var focusPlaced by remember { mutableStateOf(false) }
    fun place(target: FocusRequester): Boolean = target.requestFocusSafely().also { if (it) focusPlaced = true }

    // When an Up or Down last reached this screen. A row selects its room on focus only when one of
    // these put it there, or when it is the room already selected. Focus can reach a row with no key
    // behind it: a focused control leaving the composition makes Compose drop focus, and the platform
    // then puts it on the first focusable thing — the first room. At launch on the Shield the pane's
    // first control did leave (the music pane gave way to the TV pane as Living Room's metadata
    // arrived), and that first row, Bedroom, took the selection from the stated TV room (logcat,
    // 2026-10-11). That focus is put back on the selected room instead.
    var lastListKeyAt by remember { mutableLongStateOf(0L) }
    val focusScope = rememberCoroutineScope()
    fun rowFocused(group: Group) {
        if (!focusPlaced) return
        val byKey = SystemClock.uptimeMillis() - lastListKeyAt < LIST_KEY_WINDOW_MILLIS
        if (byKey || group.id == selectedGroupId) homeViewModel.selectGroup(group.id)
        else focusScope.launch { sidebarFocusRequester.requestFocusRetrying() }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Wait for the frames that apply a selection made just before resuming. A deep link
            // to the running app sets it in onNewIntent, and the sidebar's requester sits on
            // whichever row is selected — asked at once, it was still on the *old* row, focus
            // landed there, and selection-follows-focus put the old room back. Seen on the
            // Shield: a link to Bedroom left Kitchen selected.
            withFrameNanos { }
            withFrameNanos { }
            // Back from another screen returns to the control that opened it — Queue, Browse,
            // Search — rather than to the room. The pane puts it there itself, from its first frame
            // (`startFocusRequester`); here it only counts as placed, and the opener is spent.
            if (opener == Opener.SETTINGS) {
                // Back from a screen Settings opened: to the Settings button, which opened Settings.
                opener = null
                if (settingsButtonFocus.requestFocusRetrying()) focusPlaced = true else place(sidebarFocusRequester)
            } else if (opener != null) {
                opener = null
                focusPlaced = true
            } else {
                place(sidebarFocusRequester)
            }
        }
    }

    // Settings traps focus like any panel, and slides out rather than vanishing, so it hands focus
    // back to its button before it goes; closing first left a gap the platform filled with the
    // room row. See [handOffFocus].
    var settingsReleasing by remember { mutableStateOf(false) }
    val closeScope = rememberCoroutineScope()
    fun closeSettings() {
        closeScope.launch { handOffFocus({ settingsReleasing = it }, { showSettings = false }, settingsButtonFocus) }
    }
    BackHandler(enabled = showSettings) { closeSettings() }
    // The room panel likewise: closed first and focus requested after, there was a frame with
    // focus nowhere, which the platform filled with the first room row — whose focus selects
    // that room (Streamer). So it hands focus to what opened it before it goes.
    var panelReleasing by remember { mutableStateOf(false) }
    fun closePanel() {
        closeScope.launch {
            handOffFocus({ panelReleasing = it }, { panelGroup = null }, if (panelFromPane) groupFocus else sidebarFocusRequester)
        }
    }
    BackHandler(enabled = panelGroup != null) { closePanel() }

    val settingsFocus = remember { FocusRequester() }
    LaunchedEffect(showSettings) {
        if (showSettings) settingsFocus.requestFocusSafely()
    }

    // Both modals hand focus back as they close, above; nothing is left to place afterwards.
    val modalVisible = showSettings || panelGroup != null

    // Focus goes to the selected room's row whenever the list has it again. Three ways of
    // losing it all left focus on the first thing Compose finds — the first room's row: a cold
    // start, where the rooms arrive after the resume request above has found no row; a regroup,
    // which mints new group ids so the focused row simply leaves (the selection follows the
    // speakers to the new one); and a reconnect, which withdraws the list while it runs. Keyed
    // on the selection too, for a regroup whose old row goes in the same frame the new
    // selection arrives. Not while a panel is open, which has its own, nor with focus in the
    // player pane, which the viewer put there.
    var paneHasFocus by remember { mutableStateOf(false) }
    // And not while the list already holds it: the sidebar selects on focus, so arrowing down
    // the rooms changes the selection at every press, and each would otherwise re-request the
    // row it is already on.
    var listHasFocus by remember { mutableStateOf(false) }

    // Back from the player pane goes to the room list. A BackHandler rather than a key handler
    // on the pane: the key handler took every Back pressed inside the pane, a menu drawn there
    // included, so the sleep timer menu could not be closed with it. Handlers registered later
    // win, so a menu's own still does; this one is off while a panel outside the pane is open.
    BackHandler(enabled = paneHasFocus && !modalVisible) {
        sidebarFocusRequester.requestFocusSafely()
    }
    val listReady = selectedGroupId != null && groups.any { it.id == selectedGroupId }
    LaunchedEffect(listReady, selectedGroupId) {
        // "The list already has it" counts only once focus was placed: before that, the list holding
        // focus is the automatic first focus on the wrong row, and is exactly what to correct.
        if (!listReady || modalVisible || paneHasFocus || (listHasFocus && focusPlaced) || opener != null) return@LaunchedEffect
        // The row is composed, and may scroll into view, in the frames after it arrives.
        if (sidebarFocusRequester.requestFocusRetrying()) focusPlaced = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Seen before anything handles it, and never consumed: only noted, for [rowFocused].
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.DirectionUp || event.key == Key.DirectionDown)) {
                    lastListKeyAt = SystemClock.uptimeMillis()
                }
                false
            },
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            RoomSidebar(
                state = state,
                selectedGroupId = selectedGroupId,
                rooms = rooms,
                sidebarFocusRequester = sidebarFocusRequester,
                detailFocusRequester = detailFocusRequester,
                onFocused = ::rowFocused,
                onOpenPanel = { panelFromPane = false; panelGroup = it },
                roomRanks = roomRanks,
                movingGroupId = movingGroupId,
                onMoving = { movingGroupId = it },
                onMove = homeViewModel::moveRoom,
                onSettingsClick = { showSettings = true },
                settingsFocusRequester = settingsButtonFocus,
                onRetry = { homeViewModel.connect() },
                onChooseHousehold = { homeViewModel.chooseHousehold(it) },
                upnpOff = upnpOff,
                onListFocusChanged = { listHasFocus = it },
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onFocusChanged { paneHasFocus = it.hasFocus }
                    // Up and down never leave the pane. With Settings at the foot of the room
                    // list, a press down from the pane's last row found it geometrically and
                    // landed there (Streamer, 2026-10-07). Left still leaves, by the key
                    // handlers on each row's leftmost control. Before the group, not after: a
                    // focus target takes its properties from the modifiers above it.
                    .focusProperties {
                        exit = { direction ->
                            if (direction == FocusDirection.Up || direction == FocusDirection.Down) FocusRequester.Cancel
                            else FocusRequester.Default
                        }
                    }
                    .focusGroup()
                    .focusProperties { left = sidebarFocusRequester }
            ) {
                // The pane only for a room the list holds. A selection outlives the household it
                // was made in: with the Wi-Fi off it went on drawing Bedroom, "Nothing playing",
                // over the list's own error (2026-10-09).
                if (!listReady) {
                    // The room list says in words what is happening; this side shows it. The scanner
                    // only while the household is first being found — not while reconnecting, when
                    // nothing is loading but a network is being waited for, and not over an error.
                    // "Select a room" only when there is one to select.
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        when {
                            (state as? HomeViewModel.UiState.Loading)?.reconnecting == false -> DotScanner()
                            groups.isNotEmpty() -> Text("Select a room", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                } else {
                    PlayerPane(
                        viewModel = playerViewModel,
                        detailFocusRequester = detailFocusRequester,
                        sidebarFocusRequester = sidebarFocusRequester,
                        queueFocusRequester = queueFocus,
                        browseFocusRequester = browseFocus,
                        searchFocusRequester = searchFocus,
                        nowPlayingFocusRequester = nowPlayingFocus,
                        groupFocusRequester = groupFocus,
                        startFocusRequester = openerFocus(opener),
                        onOpenQueue = { opener = Opener.QUEUE; onOpenQueue(selectedGroupId!!) },
                        onOpenFavorites = { opener = Opener.BROWSE; onOpenFavorites(selectedGroupId!!) },
                        onOpenSearch = { opener = Opener.SEARCH; onOpenSearch(selectedGroupId!!) },
                        onOpenNowPlaying = { opener = Opener.NOW_PLAYING; onOpenNowPlaying(selectedGroupId!!) },
                        onOpenGroup = {
                            groups.firstOrNull { it.id == selectedGroupId }?.let { panelFromPane = true; panelGroup = it }
                        },
                    )
                }
            }
        }

        AnimatedVisibility(visible = showSettings, enter = fadeIn(), exit = fadeOut()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .tapToClick(onClick = { closeSettings() })
            )
        }

        AnimatedVisibility(
            visible = showSettings,
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it }),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            SettingsPanel(
                currentTheme = selectedTheme,
                firstFocus = settingsFocus,
                trapped = !settingsReleasing,
                onOpenPreferredServices = {
                    opener = Opener.SETTINGS
                    showSettings = false
                    onOpenPreferredServices()
                },
                onOpenAlarms = {
                    opener = Opener.SETTINGS
                    showSettings = false
                    onOpenAlarms()
                },
                onOpenAbout = {
                    opener = Opener.SETTINGS
                    showSettings = false
                    onOpenAbout()
                },
                roomsPlaced = roomRanks.isNotEmpty(),
                onResetRoomOrder = homeViewModel::resetRoomOrder,
                betaEnabled = betaEnabled,
                onToggleBeta = { homeViewModel.setBetaEnabled(!betaEnabled) },
                onOpenReport = {
                    opener = Opener.SETTINGS
                    showSettings = false
                    onOpenReport()
                },
                accountOffered = accountOffered,
                accountSignedIn = accountSignedIn,
                onOpenAccount = {
                    opener = Opener.SETTINGS
                    showSettings = false
                    onOpenAccount()
                },
                onThemeSelected = { homeViewModel.setTheme(it) }
            )
        }

        val panel = panelGroup
        // Re-read from the live list rather than the captured group: a regroup mints new
        // ids, and this panel is the surface most likely to be open while one happens —
        // including one performed from it. Following the speakers keeps it on the room.
        // By the one rule every screen follows a room by: the coordinator before any member,
        // or a split would take the panel — its title unchanged, `onRemovePlayer` and
        // `onUseTvInput` acting on a different room — to whichever fragment came first.
        val liveGroup = panel?.let { captured ->
            groups.firstOrNull { it.id == captured.id } ?: groups.followingRoom(captured.coordinatorId, captured.playerIds)
        }
        if (panel != null && liveGroup == null) {
            // Its speakers went somewhere this can no longer name.
            LaunchedEffect(panel.id) { panelGroup = null }
        }
        if (liveGroup != null) {
            val playerNames = remember(liveGroup) { homeViewModel.playerNamesForGroup(liveGroup) }
            val volumes by homeViewModel.playerVolumes.collectAsState()
            val groupVolumes by homeViewModel.groupVolumes.collectAsState()
            val mutedPlayers by homeViewModel.mutedPlayers.collectAsState()
            val tone by homeViewModel.tone.collectAsState()
            LaunchedEffect(liveGroup.id) { homeViewModel.loadTone(liveGroup.id) }
            Overlay(onDismiss = ::closePanel, trapped = !panelReleasing) {
                RoomPanel(
                    group = liveGroup,
                    info = rooms[liveGroup.id] ?: HomeViewModel.RoomInfo(),
                    otherGroups = sortGroups(groups, roomRanks)
                        .filter { it.id != liveGroup.id },
                    rooms = rooms,
                    playerNames = playerNames,
                    playerVolumes = volumes,
                    mutedPlayers = mutedPlayers,
                    onNormalize = { homeViewModel.normalizeGroup(liveGroup.id) },
                    groupVolumes = groupVolumes,
                    isPartying = groups.size == 1 && liveGroup.playerIds.size > 1,
                    onParty = {
                        homeViewModel.partyMode(liveGroup.id)
                        closePanel()
                    },
                    onStopParty = {
                        homeViewModel.soloGroup(liveGroup.id)
                        closePanel()
                    },
                    // Grouping stays open: building a group is several presses, and closing
                    // after each one would mean reopening the panel to make the next.
                    onRemovePlayer = { homeViewModel.removePlayerFromGroup(liveGroup.id, it) },
                    onAdjustPlayerVolume = homeViewModel::adjustPlayerVolume,
                    onAdjustGroupVolume = homeViewModel::adjustGroupVolume,
                    onSetPlayerVolume = homeViewModel::setPlayerVolume,
                    onSetGroupVolume = homeViewModel::setGroupVolume,
                    onJoin = { homeViewModel.joinGroup(it.id, liveGroup.id) },
                    onSetTvRoom = { homeViewModel.setTvSoundbar(liveGroup.id) },
                    onUseTvInput = {
                        homeViewModel.useTvInput(liveGroup.id)
                        closePanel()
                    },
                    onUseLineIn = { player ->
                        homeViewModel.useLineIn(liveGroup.id, player)
                        closePanel()
                    },
                    upnpOff = upnpOff,
                    tone = tone?.takeIf { it.playerId == liveGroup.coordinatorId },
                    onStepBass = homeViewModel::stepBass,
                    onStepTreble = homeViewModel::stepTreble,
                    onToggleLoudness = homeViewModel::toggleLoudness,
                    onToggleTrueplay = homeViewModel::toggleTrueplay,
                    onToggleSub = homeViewModel::toggleSub,
                    onStepSub = homeViewModel::stepSubLevel,
                    onToggleSurrounds = homeViewModel::toggleSurrounds,
                    onStepSurround = homeViewModel::stepSurroundLevel,
                    onStepMusicSurround = homeViewModel::stepMusicSurroundLevel,
                    onToggleStatusLight = homeViewModel::toggleStatusLight,
                    onToggleRemoteFeedback = homeViewModel::toggleRemoteFeedback,
                    onToggleIrRepeater = homeViewModel::toggleIrRepeater,
                    onToggleTouchControls = homeViewModel::toggleTouchControls,
                    onToggleCrossfade = { homeViewModel.toggleCrossfade(liveGroup.id) },
                    onSavePreset = { homeViewModel.savePreset(liveGroup.id) },
                )
            }
        }

        // Last, so it draws over the room panel: grouping keeps the panel open, and a failed
        // join must be said where the viewer is looking.
        val notice by homeViewModel.notice.collectAsState()
        notice?.let { NoticeBanner(it, Modifier.align(Alignment.BottomCenter)) }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RoomSidebar(
    state: HomeViewModel.UiState,
    selectedGroupId: String?,
    rooms: Map<String, HomeViewModel.RoomInfo>,
    sidebarFocusRequester: FocusRequester,
    detailFocusRequester: FocusRequester,
    onFocused: (Group) -> Unit,
    onOpenPanel: (Group) -> Unit,
    roomRanks: List<String>,
    /** The room picked up to be moved, if any: Up and Down carry it, Select or Back puts it down. */
    movingGroupId: String?,
    onMoving: (String?) -> Unit,
    onMove: (groupId: String, by: Int) -> Unit,
    onSettingsClick: () -> Unit,
    settingsFocusRequester: FocusRequester,
    onRetry: () -> Unit,
    onChooseHousehold: (HouseholdChoice) -> Unit,
    /** The household's UPnP switch is off: said here once, at the foot of the list. */
    upnpOff: Boolean,
    /** Whether a room row holds focus, as opposed to the gear or nothing. */
    onListFocusChanged: (Boolean) -> Unit,
) {
    val listState = rememberLazyListState()
    val groups = (state as? HomeViewModel.UiState.Success)?.groups ?: emptyList()
    val sorted = remember(groups, roomRanks) {
        sortGroups(groups, roomRanks)
    }
    // Back puts a moved room down, before anything else takes it.
    BackHandler(enabled = movingGroupId != null) { onMoving(null) }

    val selectedIndex = sorted.indexOfFirst { it.id == selectedGroupId }

    LaunchedEffect(selectedGroupId, sorted) {
        if (selectedIndex >= 0) listState.animateScrollToItem(selectedIndex)
    }

    Column(
        modifier = Modifier
            .width(SIDEBAR_WIDTH)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Spacer(Modifier.height(20.dp))

        when (state) {
            is HomeViewModel.UiState.Loading -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(if (state.reconnecting) "Reconnecting…" else "Loading…", style = MaterialTheme.typography.bodyLarge)
                }
            }
            is HomeViewModel.UiState.Error -> {
                val retryFocus = rememberAutoFocusRequester()
                Column(
                    // Margins of their own: the Authentication message runs to five lines, and
                    // without them it ran edge to edge across the sidebar (Shield, 2026-10-05).
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(start = SIDEBAR_START, end = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(state.message, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    if (state.choices.isEmpty()) {
                        AppButton(onClick = onRetry, modifier = Modifier.focusRequester(retryFocus)) {
                            Text("Retry")
                        }
                    } else {
                        // Households have no names, so each is offered by a room in it.
                        state.choices.forEachIndexed { index, choice ->
                            AppButton(
                                onClick = { onChooseHousehold(choice) },
                                modifier = if (index == 0) Modifier.focusRequester(retryFocus) else Modifier
                            ) {
                                Text("The system with ${choice.label}")
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
            is HomeViewModel.UiState.Success -> {
                LazyColumn(
                    state = listState,
                    // The rest of the height, so the footer sits at the foot of the list.
                    modifier = Modifier.weight(1f).onFocusChanged { onListFocusChanged(it.hasFocus) },
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    items(sorted, key = { it.id }) { group ->
                        val isSelected = group.id == selectedGroupId
                        val itemFocus = remember(group.id) { FocusRequester() }
                        RoomListItem(
                            group = group,
                            info = rooms[group.id] ?: HomeViewModel.RoomInfo(),
                            isSelected = isSelected,
                            focusRequester = if (isSelected) sidebarFocusRequester else itemFocus,
                            detailFocusRequester = detailFocusRequester,
                            onFocused = { onFocused(group) },
                            onOpenPanel = { onOpenPanel(group) },
                            moving = group.id == movingGroupId,
                            onMoving = { if (it) onMoving(group.id) else if (movingGroupId == group.id) onMoving(null) },
                            onMove = { by -> onMove(group.id, by) },
                        )
                    }
                }
                // Said once, here, rather than on every pane and panel: what UPnP being off costs
                // is hidden wherever it would be, and this is the one place that says why.
                if (upnpOff) {
                    Text(
                        "UPnP is off for this system, so the queue, sleep timer, TV input and sound " +
                            "settings are hidden. Turn it on in the Sonos app: Account > Privacy and " +
                            "Security > Connection Security.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = SIDEBAR_START, end = 24.dp, top = 8.dp, bottom = 8.dp),
                    )
                }
            }
        }

        // Settings at the foot, past the last room, where nothing lands on it by accident: at the
        // top it was the first thing Compose found, and focus fell onto it three different ways.
        Row(modifier = Modifier.padding(start = SIDEBAR_START, bottom = 12.dp, top = 2.dp)) {
            IconLabelButton(
                Icons.Default.Settings, "Settings", onSettingsClick,
                Modifier.focusRequester(settingsFocusRequester).exitOnKey(Key.DirectionRight, detailFocusRequester),
            )
        }
    }
}


@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun RoomListItem(
    group: Group,
    info: HomeViewModel.RoomInfo,
    isSelected: Boolean,
    focusRequester: FocusRequester,
    /** Right from a room crosses into the player pane, at its primary control. */
    detailFocusRequester: FocusRequester,
    onFocused: () -> Unit,
    onOpenPanel: () -> Unit,
    /** Picked up to be moved within the list. */
    moving: Boolean,
    onMoving: (Boolean) -> Unit,
    onMove: (by: Int) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val activity = roomActivity(group.playbackState, info.hasSource)
    AppCard(
        // Select goes into the room, to its player, as Select does on every TV list; opening the
        // panel on it put a dialog in front of the one thing a viewer pressed to get to. The panel
        // is the pane's Group button's. A hold or Menu here picks the room up, to move it within the
        // list (P-20, 2026-10-17); Select then puts it down rather than going into the room.
        onClick = { if (moving) onMoving(false) else detailFocusRequester.requestFocusSafely() },
        onLongClick = { onMoving(!moving) },
        // A remote selects a room by moving onto it and opens it with a press; a tap does both in
        // turn — the first selects, a tap on the selected room opens its panel.
        onTap = { if (isSelected) onOpenPanel() else focusRequester.requestFocusSafely() },
        // The room the pane is showing keeps a quiet tint while focus is elsewhere — in the pane,
        // say — so the list still says which room the controls beside it act on. Text takes the
        // theme's root content colour; see X2RockTheme.
        colors = CardDefaults.colors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f) else Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f),
            pressedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            // The card's edge sits inside the 48dp title-safe margin's reach and its content on it.
            // Room at the right and between rows for the focused card, which grows by a tenth:
            // with 12dp it reached the pane's edge, and its outline sat on the border (2026-10-08).
            .padding(start = SIDEBAR_START - 16.dp, end = 28.dp, top = 6.dp, bottom = 6.dp)
            .focusRequester(focusRequester)
            // Right crosses into the player pane, at its primary control. A key rather than a
            // focus property: `focusProperties` on this Card does not govern the search, because
            // the Card's own focusable sits inside the modifier chain it is given, and left to
            // geometry the press landed on the rightmost transport button.
            .exitOnKey(Key.DirectionRight, detailFocusRequester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused() else if (moving) onMoving(false)
            }
            .dpadMenuKey { onMoving(!moving) }
            // While picked up, Up and Down carry the room instead of moving focus. Seen first, so the
            // list's own focus search never runs; the row keeps focus as it moves, being keyed.
            .onPreviewKeyEvent { event ->
                if (!moving || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> { onMove(-1); true }
                    Key.DirectionDown -> { onMove(+1); true }
                    else -> false
                }
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // The art slot is always occupied, even when there is nothing to put in it, so
            // every room name starts at the same x. Rows used to shift left without art,
            // which at three metres reads as a different list rather than a missing image.
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(4.dp))
                    // Tinted only when something sits on it. An idle room leaves the slot
                    // transparent: it still holds the column, without putting an empty grey
                    // square beside every room that happens not to be playing.
                    .then(
                        if (info.artUrl == null && (info.onTvInput || info.isRadio)) {
                            Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    // Cover art, or the station logo for radio.
                    info.artUrl != null -> AsyncImage(
                        model = info.artUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        // Dimmed with the row when nothing plays, so the playing rooms stand out.
                        modifier = Modifier.fillMaxSize().alpha(if (activity.isPlaying) 1f else IDLE_ALPHA),
                    )
                    // A TV input has no art of its own — the player really does send
                    // `images: []` for `TV Audio` — so this glyph is the app's invention.
                    // It is an honest one: it says what the source is, which is data we have.
                    info.onTvInput -> Icon(
                        imageVector = Icons.Default.Tv,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                    // A station with no logo of its own — the same invention as the TV glyph
                    // and equally honest, since it names a source the player has told us about.
                    info.isRadio -> Icon(
                        imageVector = Icons.Default.Radio,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                    // And nothing at all for an idle room: an empty tile holds the column
                    // without claiming something is playing.
                    else -> Unit
                }
            }
            Spacer(Modifier.width(14.dp))

            // Not playing is drawn quieter, as the Sonos app draws it: the eye goes to the rooms
            // that are doing something. Quieter by colour, not by fading: the room's name stays at
            // full strength, and the lines under it take the theme's secondary text colour. A 62%
            // fade over the whole column measured about 3:1 against the sidebar on the Streamer,
            // under the 4.5:1 body text needs at three metres.
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val lines = if (moving) listOf("Moving · Up or Down, then Select")
                else roomRowLines(info, group.playbackState ?: PlaybackStates.IDLE)
                lines.forEachIndexed { index, line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (activity.isPlaying) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        // The focused row's first line scrolls when it does not fit, so a long
                        // title can be read without widening the list.
                        overflow = if (focused && index == 0) TextOverflow.Clip else TextOverflow.Ellipsis,
                        modifier = if (focused && index == 0) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier,
                    )
                }
            }
            // Centred, not end-aligned: the glyphs differ in width, and end-aligned their middles
            // wandered from row to row.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // A playing room's level meter. Paused and stopped have no mark: a pause or stop
                // glyph read as a button to press rather than a state (2026-10-08), and a room not
                // playing already says so by its quieted art and text.
                PlayingGlyph(activity)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    // This device's television, in the accent colour and always: no other row
                    // says which room it is, and it moves when "This is my TV" is pressed, which
                    // is that press's only sign it took (T-33). Any other room with a television
                    // attached wears it dim and neutral, a capability rather than a state, and
                    // only while *not* on that input: once it is, the art tile carries the same
                    // glyph and the line says "TV Audio", so a badge would be a third telling.
                    if (info.isThisTv || (info.hasTvInput && !info.onTvInput)) {
                        Icon(
                            imageVector = Icons.Default.Tv,
                            contentDescription = if (info.isThisTv) "This TV's room" else "Has a TV input",
                            modifier = Modifier.size(18.dp),
                            tint = if (info.isThisTv) MaterialTheme.colorScheme.primary
                            else LocalContentColor.current.copy(alpha = 0.5f),
                        )
                    }
                    // A bonded speaker gone from the network: the room plays on without it and
                    // nothing else would say so. In the error colour, being a fault to see to.
                    if (info.offlineSpeakers > 0) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = if (info.offlineSpeakers == 1) "A speaker in this room is offline"
                                else "${info.offlineSpeakers} speakers in this room are offline",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                    // Muted is a state, not a capability, so it is not dimmed like the TV badge.
                    if (info.muted) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = "Muted",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The level meter while [activity] is playing, and otherwise the same 24dp left empty, so the
 * badges under it sit at one height on every row.
 */
@Composable
private fun PlayingGlyph(activity: RoomActivity) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        if (activity == RoomActivity.PLAYING) DotEqualizer(glyphTint(), description = activity.label)
    }
}

/** The room list's glyph colour: the primary, softened, as the equalizer's lit dots are. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun glyphTint() = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)

/** How quiet a room that is not playing draws its art; its text is quieted by colour instead. */
private const val IDLE_ALPHA = 0.62f

/** Room list width. 960dp is the whole of a 1080p screen; the player pane takes the rest. */
private val SIDEBAR_WIDTH = 340.dp

/**
 * Where the sidebar's content starts: Google's 48dp title-safe margin, so a set that still
 * overscans does not clip the rooms' art.
 */
private val SIDEBAR_START = 48.dp

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsPanel(
    currentTheme: AppColorTheme,
    firstFocus: FocusRequester,
    /** False for the frames closing spends handing focus back to the Settings button. */
    trapped: Boolean,
    onOpenPreferredServices: () -> Unit,
    onOpenAlarms: () -> Unit,
    onOpenAbout: () -> Unit,
    /** Whether any room has been moved in the list, so there is an order to reset. */
    roomsPlaced: Boolean,
    onResetRoomOrder: () -> Unit,
    betaEnabled: Boolean,
    onToggleBeta: () -> Unit,
    onOpenReport: () -> Unit,
    accountOffered: Boolean,
    accountSignedIn: Boolean,
    onOpenAccount: () -> Unit,
    onThemeSelected: (AppColorTheme) -> Unit
) {
    Box(
        modifier = Modifier
            .width(380.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .modalFocusTrap(trapped)
            .keepTaps()
    ) {
        // Scrolls: the Beta section took it past the screen's height, and focus brings a row into view.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 48.dp)
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(32.dp))
            Text("Theme", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            ThemeSelector(
                currentTheme = currentTheme,
                onThemeSelected = onThemeSelected,
                modifier = Modifier.focusRequester(firstFocus)
            )
            Spacer(Modifier.height(32.dp))
            Text("Services", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            // Which services Search starts on and lists first: a screen of its own, since ordering
            // a list needs more room than this panel has.
            AppButton(onClick = onOpenPreferredServices, modifier = Modifier.fillMaxWidth()) {
                Text("Preferred services")
            }
            // Rooms are ordered in the list itself, by a hold on one; this only undoes it. Shown only
            // once there is something to undo, so it is never a row that does nothing.
            if (roomsPlaced) {
                Spacer(Modifier.height(8.dp))
                AppButton(onClick = onResetRoomOrder, modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Text("Reset room order")
                        Text("Back to alphabetical", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
            Text("Household", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            // Every room's alarms in one list: they are the household's, not one room's.
            AppButton(onClick = onOpenAlarms, modifier = Modifier.fillMaxWidth()) {
                Text("Alarms")
            }
            Spacer(Modifier.height(8.dp))
            // Every speaker, what it runs, and whether an update waits — read, never applied.
            AppButton(onClick = onOpenAbout, modifier = Modifier.fillMaxWidth()) {
                Text("About this system")
            }
            Spacer(Modifier.height(32.dp))
            Text("Beta", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            // What has never run against a real speaker, shown only when asked for (T-21): a plain
            // switch rather than a hidden gesture, so a tester needs no instructions to find it.
            AppButton(onClick = onToggleBeta, modifier = Modifier.fillMaxWidth()) {
                Text(if (betaEnabled) "Beta features   On" else "Beta features   Off")
            }
            Text(
                "Features not yet tried on real speakers, such as Line-In and a portable's battery. Each says Beta where it appears.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            if (betaEnabled) {
                AppButton(onClick = onOpenReport, modifier = Modifier.fillMaxWidth()) {
                    Text("Send a report")
                }
            }
            // Only in a build with its own Sonos integration (T-27): who is controlling, for speakers
            // that ask. Never in a build from the repo as it stands.
            if (accountOffered) {
                Spacer(Modifier.height(8.dp))
                AppButton(onClick = onOpenAccount, modifier = Modifier.fillMaxWidth()) {
                    Text(if (accountSignedIn) "Sonos account   Signed in" else "Sonos account   Sign in")
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ThemeSelector(
    currentTheme: AppColorTheme,
    onThemeSelected: (AppColorTheme) -> Unit,
    modifier: Modifier = Modifier
) {
    val themes = AppColorTheme.entries
    val currentIndex = themes.indexOf(currentTheme)

    Surface(
        onClick = {},
        modifier = modifier
            .fillMaxWidth()
            // The arrows drawn at either end, as a tap: the left half steps back, the right on.
            .pointerInput(currentIndex) {
                detectTapGestures { offset ->
                    val step = if (offset.x < size.width / 2) themes.size - 1 else 1
                    onThemeSelected(themes[(currentIndex + step) % themes.size])
                }
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        onThemeSelected(themes[(currentIndex - 1 + themes.size) % themes.size])
                        true
                    }
                    Key.DirectionRight -> {
                        onThemeSelected(themes[(currentIndex + 1) % themes.size])
                        true
                    }
                    else -> false
                }
            }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(currentTheme.swatchColor())
            )
            Text(
                text = currentTheme.displayName,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** The pane's buttons that open another screen, so Back can return focus to the one that did. */
/** How recent an Up or Down must be for the row focus reached to be the viewer's choice; see `rowFocused`. */
private const val LIST_KEY_WINDOW_MILLIS = 500L

private enum class Opener { QUEUE, BROWSE, SEARCH, NOW_PLAYING, SETTINGS }
