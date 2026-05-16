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
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.rahga.x2rock.model.AppColorTheme
import com.rahga.x2rock.model.Group
import com.rahga.x2rock.model.Track
import com.rahga.x2rock.model.isPlaying
import com.rahga.x2rock.ui.theme.AppButton
import com.rahga.x2rock.ui.theme.rememberAutoFocusRequester
import com.rahga.x2rock.ui.theme.requestFocusSafely
import com.rahga.x2rock.ui.theme.swatchColor
import com.rahga.x2rock.viewmodel.HomeViewModel
import com.rahga.x2rock.viewmodel.PlayerViewModel

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenQueue: (groupId: String) -> Unit = {},
    onOpenFavorites: (groupId: String) -> Unit = {},
    onSignedOut: () -> Unit = {},
    homeViewModel: HomeViewModel = hiltViewModel(),
    playerViewModel: PlayerViewModel = hiltViewModel()
) {
    val state by homeViewModel.uiState.collectAsState()
    val selectedTheme by homeViewModel.selectedTheme.collectAsState()
    val selectedGroupId by homeViewModel.selectedGroupId.collectAsState()
    val sidebarVisible by homeViewModel.sidebarVisible.collectAsState()
    val primaryRoomId by homeViewModel.primaryRoomId.collectAsState()
    val favoriteRoomIds by homeViewModel.favoriteRoomIds.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var showPartyConfirmation by remember { mutableStateOf(false) }
    var contextMenuGroup by remember { mutableStateOf<Group?>(null) }
    var groupPickerSource by remember { mutableStateOf<Group?>(null) }
    var separateRoomSource by remember { mutableStateOf<Group?>(null) }

    val detailFocusRequester = remember { FocusRequester() }
    val sidebarFocusRequester = remember { FocusRequester() }

    val groups = (state as? HomeViewModel.UiState.Success)?.groups ?: emptyList()
    val nowPlaying = (state as? HomeViewModel.UiState.Success)?.nowPlaying ?: emptyMap()

    LaunchedEffect(selectedGroupId) {
        val id = selectedGroupId ?: return@LaunchedEffect
        val name = groups.find { it.id == id }?.name ?: ""
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
    BackHandler(enabled = separateRoomSource != null) { separateRoomSource = null }
    BackHandler(enabled = groupPickerSource != null) { groupPickerSource = null }
    BackHandler(enabled = contextMenuGroup != null) { contextMenuGroup = null }
    BackHandler(enabled = showPartyConfirmation) { showPartyConfirmation = false }

    val settingsFocus = remember { FocusRequester() }
    LaunchedEffect(showSettings) {
        if (showSettings) settingsFocus.requestFocusSafely()
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
                    favoriteRoomIds = favoriteRoomIds,
                    sortedGroups = { grps ->
                        homeViewModel.sortedGroups(grps, primaryRoomId, favoriteRoomIds)
                    },
                    nowPlaying = nowPlaying,
                    sidebarFocusRequester = sidebarFocusRequester,
                    detailFocusRequester = detailFocusRequester,
                    onFocused = { homeViewModel.selectGroup(it.id) },
                    onLongPress = { contextMenuGroup = it },
                    onPartyModeClick = { showPartyConfirmation = true },
                    onSettingsClick = { showSettings = true },
                    onCollapseClick = { homeViewModel.toggleSidebar() },
                    onRetry = { homeViewModel.loadGroups() }
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
                onThemeSelected = { homeViewModel.setTheme(it) },
                onSignOut = { homeViewModel.signOut(); onSignedOut() }
            )
        }

        if (showPartyConfirmation) {
            Overlay {
                PartyConfirmationDialog(
                    groupCount = groups.size,
                    onConfirm = { homeViewModel.partyMode(); showPartyConfirmation = false },
                    onDismiss = { showPartyConfirmation = false }
                )
            }
        }

        val menuGroup = contextMenuGroup
        if (menuGroup != null) {
            val playerNames = remember(menuGroup) { homeViewModel.playerNamesForGroup(menuGroup) }
            Overlay {
                RoomContextMenu(
                    group = menuGroup,
                    playerNames = playerNames,
                    isPrimary = menuGroup.id == primaryRoomId,
                    isFavorite = menuGroup.id in favoriteRoomIds,
                    otherGroups = groups.filter { it.id != menuGroup.id },
                    onSetPrimary = {
                        homeViewModel.setPrimaryRoom(if (menuGroup.id == primaryRoomId) null else menuGroup.id)
                        contextMenuGroup = null
                    },
                    onToggleFavorite = {
                        homeViewModel.toggleFavorite(menuGroup.id)
                        contextMenuGroup = null
                    },
                    onJoinGroup = {
                        groupPickerSource = menuGroup
                        contextMenuGroup = null
                    },
                    onSeparateRoom = {
                        separateRoomSource = menuGroup
                        contextMenuGroup = null
                    },
                    onSeparateAll = {
                        homeViewModel.soloGroup(menuGroup.id)
                        contextMenuGroup = null
                    },
                    onDismiss = { contextMenuGroup = null }
                )
            }
        }

        val pickerSource = groupPickerSource
        if (pickerSource != null) {
            Overlay {
                GroupPickerDialog(
                    sourceGroup = pickerSource,
                    availableGroups = groups.filter { it.id != pickerSource.id },
                    nowPlaying = nowPlaying,
                    onPick = { target ->
                        homeViewModel.joinGroup(pickerSource.id, target.id)
                        groupPickerSource = null
                    },
                    onDismiss = { groupPickerSource = null }
                )
            }
        }

        val separateSource = separateRoomSource
        if (separateSource != null) {
            val playerNames = remember(separateSource) { homeViewModel.playerNamesForGroup(separateSource) }
            Overlay {
                SeparateRoomDialog(
                    group = separateSource,
                    playerNames = playerNames,
                    onSeparate = { playerId ->
                        homeViewModel.removePlayerFromGroup(separateSource.id, playerId)
                        separateRoomSource = null
                    },
                    onDismiss = { separateRoomSource = null }
                )
            }
        }
    }
}

@Composable
private fun Overlay(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RoomSidebar(
    state: HomeViewModel.UiState,
    selectedGroupId: String?,
    primaryRoomId: String?,
    favoriteRoomIds: Set<String>,
    sortedGroups: (List<Group>) -> List<Group>,
    nowPlaying: Map<String, Track?>,
    sidebarFocusRequester: FocusRequester,
    detailFocusRequester: FocusRequester,
    onFocused: (Group) -> Unit,
    onLongPress: (Group) -> Unit,
    onPartyModeClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onCollapseClick: () -> Unit,
    onRetry: () -> Unit
) {
    val listState = rememberLazyListState()
    val groups = (state as? HomeViewModel.UiState.Success)?.groups ?: emptyList()
    val sorted = remember(groups, primaryRoomId, favoriteRoomIds) { sortedGroups(groups) }
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
                if (groups.size > 1) {
                    SidebarIconButton(onClick = onPartyModeClick) {
                        Icon(Icons.Default.Celebration, contentDescription = "Party mode", modifier = Modifier.size(18.dp))
                    }
                }
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
                        val itemFocus = remember { FocusRequester() }
                        RoomListItem(
                            group = group,
                            track = nowPlaying[group.id],
                            isSelected = isSelected,
                            isPrimary = group.id == primaryRoomId,
                            isFavorite = group.id in favoriteRoomIds,
                            focusRequester = if (isSelected) sidebarFocusRequester else itemFocus,
                            onFocused = { onFocused(group) },
                            onLongPress = { onLongPress(group) }
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
    track: Track?,
    isSelected: Boolean,
    isPrimary: Boolean,
    isFavorite: Boolean,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onLongPress: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var longPressJob by remember { mutableStateOf<Job?>(null) }

    Card(
        onClick = {},
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Menu) {
                    longPressJob?.cancel()
                    longPressJob = null
                    onLongPress()
                    return@onKeyEvent true
                }
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val roomCount = group.playerIds.size
                val statusLine = when {
                    track?.name != null -> track.name
                    else -> group.playbackState.toSidebarLabel()
                } + if (roomCount > 1) " · $roomCount rooms" else ""
                Text(
                    text = statusLine,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (group.playbackState.isPlaying()) {
                    Text("▶", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                if (isPrimary) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                } else if (isFavorite) {
                    Icon(
                        imageVector = Icons.Outlined.Star,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RoomContextMenu(
    group: Group,
    playerNames: List<Pair<String, String>>,
    isPrimary: Boolean,
    isFavorite: Boolean,
    otherGroups: List<Group>,
    onSetPrimary: () -> Unit,
    onToggleFavorite: () -> Unit,
    onJoinGroup: () -> Unit,
    onSeparateRoom: () -> Unit,
    onSeparateAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val firstFocus = rememberAutoFocusRequester()
    val roomCount = group.playerIds.size

    Column(
        modifier = Modifier
            .width(340.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(24.dp)
            .onKeyEvent { it.key != Key.Back },
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(group.name, style = MaterialTheme.typography.titleMedium)
        if (roomCount > 1) {
            Text(
                text = playerNames.joinToString(" · ") { it.second },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(4.dp))
        AppButton(
            onClick = onSetPrimary,
            modifier = Modifier.fillMaxWidth().focusRequester(firstFocus)
        ) {
            Text(if (isPrimary) "Remove as Primary" else "Set as Primary")
        }
        AppButton(onClick = onToggleFavorite, modifier = Modifier.fillMaxWidth()) {
            Text(if (isFavorite) "Remove from Favorites" else "Add to Favorites")
        }
        if (otherGroups.isNotEmpty()) {
            AppButton(onClick = onJoinGroup, modifier = Modifier.fillMaxWidth()) {
                Text("Join Group…")
            }
        }
        if (roomCount >= 3) {
            AppButton(onClick = onSeparateRoom, modifier = Modifier.fillMaxWidth()) {
                Text("Separate a Room…")
            }
        }
        if (roomCount >= 2) {
            AppButton(onClick = onSeparateAll, modifier = Modifier.fillMaxWidth()) {
                Text(if (roomCount == 2) "Separate Rooms" else "Separate All Rooms")
            }
        }
        AppButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel")
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GroupPickerDialog(
    sourceGroup: Group,
    availableGroups: List<Group>,
    nowPlaying: Map<String, Track?>,
    onPick: (Group) -> Unit,
    onDismiss: () -> Unit
) {
    val firstFocus = rememberAutoFocusRequester()

    Column(
        modifier = Modifier
            .width(340.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(24.dp)
            .onKeyEvent { it.key != Key.Back },
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Join \"${sourceGroup.name}\" with…", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        availableGroups.forEachIndexed { index, group ->
            AppButton(
                onClick = { onPick(group) },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
            ) {
                Column(horizontalAlignment = Alignment.Start) {
                    Text(group.name, style = MaterialTheme.typography.bodyLarge)
                    val track = nowPlaying[group.id]
                    val subtitle = when {
                        track?.name != null -> track.name
                        else -> group.playbackState.toSidebarLabel()
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        AppButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel")
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SeparateRoomDialog(
    group: Group,
    playerNames: List<Pair<String, String>>,
    onSeparate: (playerId: String) -> Unit,
    onDismiss: () -> Unit
) {
    val firstFocus = rememberAutoFocusRequester()
    val removable = playerNames.filter { (id, _) -> id != group.coordinatorId }

    Column(
        modifier = Modifier
            .width(340.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(24.dp)
            .onKeyEvent { it.key != Key.Back },
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Separate a room from \"${group.name}\"", style = MaterialTheme.typography.titleMedium)
        Text(
            "The remaining rooms will stay grouped.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(4.dp))
        removable.forEachIndexed { index, (id, name) ->
            AppButton(
                onClick = { onSeparate(id) },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
            ) {
                Text(name)
            }
        }
        AppButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel")
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PartyConfirmationDialog(
    groupCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val firstFocus = rememberAutoFocusRequester()

    Column(
        modifier = Modifier
            .width(340.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(24.dp)
            .onKeyEvent { it.key != Key.Back },
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Party Mode", style = MaterialTheme.typography.titleMedium)
        Text(
            "Combine all $groupCount rooms to play together?",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(4.dp))
        AppButton(
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth().focusRequester(firstFocus)
        ) {
            Text("Combine All Rooms")
        }
        AppButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel")
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsPanel(
    currentTheme: AppColorTheme,
    firstFocus: FocusRequester,
    onThemeSelected: (AppColorTheme) -> Unit,
    onSignOut: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(380.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .onKeyEvent { it.key != Key.Back }
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

            Spacer(Modifier.weight(1f))

            AppButton(
                onClick = onSignOut,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Sign Out")
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

private fun String.toSidebarLabel(): String = when (this) {
    "PLAYBACK_STATE_PLAYING" -> "Playing"
    "PLAYBACK_STATE_PAUSED" -> "Paused"
    "PLAYBACK_STATE_BUFFERING" -> "Buffering"
    else -> "Idle"
}
