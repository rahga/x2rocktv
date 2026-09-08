package com.rahga.x2rock.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
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
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.rahga.x2rock.model.AppColorTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.rahga.x2rock.model.Group
import com.rahga.x2rock.model.PlaybackStates
import com.rahga.x2rock.model.Track
import com.rahga.x2rock.model.isPlaying
import com.rahga.x2rock.model.toPlaybackLabel
import com.rahga.x2rock.ui.components.Overlay
import com.rahga.x2rock.ui.components.dpadLongPress
import com.rahga.x2rock.ui.components.modalFocusTrap
import com.rahga.x2rock.ui.theme.AppButton
import com.rahga.x2rock.ui.theme.rememberAutoFocusRequester
import com.rahga.x2rock.ui.theme.requestFocusSafely
import com.rahga.x2rock.ui.theme.swatchColor
import com.rahga.x2rock.viewmodel.HomeViewModel
import com.rahga.x2rock.viewmodel.PlayerViewModel
import com.rahga.x2rock.viewmodel.sortGroups

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenQueue: (groupId: String) -> Unit = {},
    onOpenFavorites: (groupId: String) -> Unit = {},
    homeViewModel: HomeViewModel = hiltViewModel(),
    playerViewModel: PlayerViewModel = hiltViewModel()
) {
    val state by homeViewModel.uiState.collectAsState()
    val selectedTheme by homeViewModel.selectedTheme.collectAsState()
    val selectedGroupId by homeViewModel.selectedGroupId.collectAsState()
    val sidebarVisible by homeViewModel.sidebarVisible.collectAsState()
    val primaryRoomId by homeViewModel.primaryRoomId.collectAsState()
    val tvPlayerId by homeViewModel.tvPlayerId.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var panelGroup by remember { mutableStateOf<Group?>(null) }

    val detailFocusRequester = remember { FocusRequester() }
    val sidebarFocusRequester = remember { FocusRequester() }

    val groups = (state as? HomeViewModel.UiState.Success)?.groups ?: emptyList()
    val rooms = (state as? HomeViewModel.UiState.Success)?.rooms ?: emptyMap()

    // Keyed on groups too: a deep link can select a room before the group list has loaded, and
    // without the re-run the player pane would keep the empty name it resolved to first.
    LaunchedEffect(selectedGroupId, groups) {
        val id = selectedGroupId ?: return@LaunchedEffect
        val name = groups.find { it.id == id }?.name ?: return@LaunchedEffect
        playerViewModel.selectGroup(id, name)
    }

    LaunchedEffect(sidebarVisible) {
        if (!sidebarVisible) detailFocusRequester.requestFocusSafely()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (homeViewModel.sidebarVisible.value) sidebarFocusRequester.requestFocusSafely()
            else detailFocusRequester.requestFocusSafely()
        }
    }

    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = panelGroup != null) { panelGroup = null }

    val settingsFocus = remember { FocusRequester() }
    LaunchedEffect(showSettings) {
        if (showSettings) settingsFocus.requestFocusSafely()
    }

    // Modals trap focus, so closing one has to hand it back explicitly — otherwise focus is left
    // on a node that just left the composition and the remote goes dead until a direction press.
    val modalVisible = showSettings || panelGroup != null
    LaunchedEffect(modalVisible) {
        if (!modalVisible) {
            if (sidebarVisible) sidebarFocusRequester.requestFocusSafely()
            else detailFocusRequester.requestFocusSafely()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = sidebarVisible,
                enter = expandHorizontally(),
                exit = shrinkHorizontally()
            ) {
                RoomSidebar(
                    state = state,
                    selectedGroupId = selectedGroupId,
                    primaryRoomId = primaryRoomId,
                    rooms = rooms,
                    sidebarFocusRequester = sidebarFocusRequester,
                    detailFocusRequester = detailFocusRequester,
                    onFocused = { homeViewModel.selectGroup(it.id) },
                    onOpenPanel = { panelGroup = it },
                    onSettingsClick = { showSettings = true },
                    onCollapseClick = { homeViewModel.toggleSidebar() },
                    onRetry = { homeViewModel.setActive(true) }
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .focusGroup()
                    .focusProperties { left = sidebarFocusRequester }
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                            if (!sidebarVisible) homeViewModel.toggleSidebar()
                            sidebarFocusRequester.requestFocusSafely()
                            true
                        } else false
                    }
            ) {
                if (selectedGroupId == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Select a room", style = MaterialTheme.typography.titleLarge)
                    }
                } else {
                    PlayerPane(
                        viewModel = playerViewModel,
                        detailFocusRequester = detailFocusRequester,
                        sidebarFocusRequester = sidebarFocusRequester,
                        onOpenQueue = { onOpenQueue(selectedGroupId!!) },
                        onOpenFavorites = { onOpenFavorites(selectedGroupId!!) }
                    )
                }

                if (!sidebarVisible) {
                    Surface(
                        onClick = { homeViewModel.toggleSidebar() },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(16.dp)
                            .size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Text("▶", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }

        AnimatedVisibility(visible = showSettings, enter = fadeIn(), exit = fadeOut()) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
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
                onThemeSelected = { homeViewModel.setTheme(it) }
            )
        }

        val panel = panelGroup
        // Re-read from the live list rather than the captured group: a regroup mints new
        // ids, and this panel is the surface most likely to be open while one happens —
        // including one performed from it. Following the speakers keeps it on the room.
        val liveGroup = panel?.let { captured ->
            groups.firstOrNull { it.id == captured.id }
                // The coordinator before any member: if something else on the network splits
                // this group, matching any shared player would take the panel to whichever
                // fragment happens to come first in household order. It would keep its title
                // while `onRemovePlayer` and `onUseTvInput` acted on a different room.
                ?: groups.firstOrNull { captured.coordinatorId in it.playerIds }
                ?: groups.firstOrNull { g -> g.playerIds.any { it in captured.playerIds } }
        }
        if (panel != null && liveGroup == null) {
            // Its speakers went somewhere this can no longer name.
            LaunchedEffect(panel.id) { panelGroup = null }
        }
        if (liveGroup != null) {
            val playerNames = remember(liveGroup) { homeViewModel.playerNamesForGroup(liveGroup) }
            val volumes by homeViewModel.playerVolumes.collectAsState()
            val groupVolumes by homeViewModel.groupVolumes.collectAsState()
            Overlay {
                RoomPanel(
                    group = liveGroup,
                    info = rooms[liveGroup.id] ?: HomeViewModel.RoomInfo(),
                    otherGroups = sortGroups(groups, primaryRoomId)
                        .filter { it.id != liveGroup.id },
                    rooms = rooms,
                    playerNames = playerNames,
                    playerVolumes = volumes,
                    groupVolumes = groupVolumes,
                    isPrimary = liveGroup.id == primaryRoomId,
                    isTvRoom = tvPlayerId != null && tvPlayerId in liveGroup.playerIds,
                    isPartying = groups.size == 1 && liveGroup.playerIds.size > 1,
                    onParty = {
                        homeViewModel.partyMode(liveGroup.id)
                        panelGroup = null
                    },
                    onStopParty = {
                        homeViewModel.soloGroup(liveGroup.id)
                        panelGroup = null
                    },
                    // Grouping stays open: building a group is several presses, and closing
                    // after each one would mean reopening the panel to make the next.
                    onRemovePlayer = { homeViewModel.removePlayerFromGroup(liveGroup.id, it) },
                    onAdjustPlayerVolume = homeViewModel::adjustPlayerVolume,
                    onAdjustGroupVolume = homeViewModel::adjustGroupVolume,
                    onJoin = { homeViewModel.joinGroup(it.id, liveGroup.id) },
                    onSetPrimary = {
                        homeViewModel.setPrimaryRoom(
                            if (liveGroup.id == primaryRoomId) null else liveGroup.id
                        )
                    },
                    onSetTvRoom = { homeViewModel.setTvSoundbar(liveGroup.id) },
                    onUseTvInput = {
                        homeViewModel.useTvInput(liveGroup.id)
                        panelGroup = null
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RoomSidebar(
    state: HomeViewModel.UiState,
    selectedGroupId: String?,
    primaryRoomId: String?,
    rooms: Map<String, HomeViewModel.RoomInfo>,
    sidebarFocusRequester: FocusRequester,
    detailFocusRequester: FocusRequester,
    onFocused: (Group) -> Unit,
    onOpenPanel: (Group) -> Unit,
    onSettingsClick: () -> Unit,
    onCollapseClick: () -> Unit,
    onRetry: () -> Unit
) {
    val listState = rememberLazyListState()
    val groups = (state as? HomeViewModel.UiState.Success)?.groups ?: emptyList()
    val sorted = remember(groups, primaryRoomId) {
        sortGroups(groups, primaryRoomId)
    }
    val iconRowFocusRequester = remember { FocusRequester() }

    val selectedIndex = sorted.indexOfFirst { it.id == selectedGroupId }
    LaunchedEffect(selectedGroupId, sorted) {
        if (selectedIndex >= 0) listState.animateScrollToItem(selectedIndex)
    }

    Column(
        modifier = Modifier
            .width(320.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .focusProperties { right = iconRowFocusRequester }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("x2rock", style = MaterialTheme.typography.titleMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .focusRequester(iconRowFocusRequester)
                    .focusGroup()
                    .focusProperties { right = detailFocusRequester }
            ) {
                SidebarIconButton(onClick = onSettingsClick) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", modifier = Modifier.size(18.dp))
                }
                SidebarIconButton(onClick = onCollapseClick) {
                    Text("◀", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        when (state) {
            is HomeViewModel.UiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Loading…", style = MaterialTheme.typography.bodyLarge)
                }
            }
            is HomeViewModel.UiState.Error -> {
                val retryFocus = rememberAutoFocusRequester()
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(state.message, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(16.dp))
                    AppButton(onClick = onRetry, modifier = Modifier.focusRequester(retryFocus)) {
                        Text("Retry")
                    }
                }
            }
            is HomeViewModel.UiState.Success -> {
                LazyColumn(state = listState) {
                    items(sorted, key = { it.id }) { group ->
                        val isSelected = group.id == selectedGroupId
                        val itemFocus = remember(group.id) { FocusRequester() }
                        RoomListItem(
                            group = group,
                            info = rooms[group.id] ?: HomeViewModel.RoomInfo(),
                            isSelected = isSelected,
                            isPrimary = group.id == primaryRoomId,
                            focusRequester = if (isSelected) sidebarFocusRequester else itemFocus,
                            detailFocusRequester = detailFocusRequester,
                            onFocused = { onFocused(group) },
                            onOpenPanel = { onOpenPanel(group) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RoomListItem(
    group: Group,
    info: HomeViewModel.RoomInfo,
    isSelected: Boolean,
    isPrimary: Boolean,
    focusRequester: FocusRequester,
    /** Right from a room crosses into the room view, at its primary control. */
    detailFocusRequester: FocusRequester,
    onFocused: () -> Unit,
    onOpenPanel: () -> Unit
) {
    Card(
        // Click was doing nothing at all, and it is the press a remote makes on a list.
        // Long-press stays as a synonym rather than the only way in.
        onClick = onOpenPanel,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .focusRequester(focusRequester)
            // Handled as a key rather than declared as a focus property: `focusProperties`
            // on this Card does not govern the search, because the Card's own focusable
            // sits inside the modifier chain it is given. Left to geometry the press lands
            // on whichever control happens to line up — in practice the rightmost transport
            // button, which is a strange place to arrive.
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                    detailFocusRequester.requestFocusSafely()
                    true
                } else false
            }
            .onFocusChanged { if (it.isFocused) onFocused() }
            .dpadLongPress(onOpenPanel)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Cover art, or the station logo for radio. Absent for an idle room and for a
            // TV input, which have nothing to show.
            if (info.artUrl != null) {
                AsyncImage(
                    model = info.artUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
                Spacer(Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                val roomCount = group.playerIds.size
                Text(
                    text = group.name + if (roomCount > 1) " · $roomCount rooms" else "",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // A soundbar on its TV input gets the format it is receiving, then the
                // source — "Dolby Digital Surround 5.1" over "TV Audio" — because the
                // format is what changes and what a listener is checking for.
                val lines = when {
                    info.onTvInput -> listOfNotNull(
                        info.inputFormat.ifEmpty { null } ?: (group.playbackState ?: PlaybackStates.IDLE).toPlaybackLabel(),
                        info.source,
                    )
                    info.track?.name != null -> listOfNotNull(
                        info.track.name,
                        info.track.artist?.name,
                    )
                    else -> listOf((group.playbackState ?: PlaybackStates.IDLE).toPlaybackLabel())
                }
                lines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if ((group.playbackState ?: PlaybackStates.IDLE).isPlaying()) {
                    Text("▶", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                if (isPrimary) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsPanel(
    currentTheme: AppColorTheme,
    firstFocus: FocusRequester,
    onThemeSelected: (AppColorTheme) -> Unit
) {
    Box(
        modifier = Modifier
            .width(380.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .modalFocusTrap()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SidebarIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}
