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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.rahga.x2rock.ui.theme.AppButton
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
    var contextMenuGroup by remember { mutableStateOf<Group?>(null) }
    var groupPickerSource by remember { mutableStateOf<Group?>(null) }

    val detailFocusRequester = remember { FocusRequester() }
    val sidebarFocusRequester = remember { FocusRequester() }

    // Wire selected group into PlayerViewModel
    val groups = (state as? HomeViewModel.UiState.Success)?.groups ?: emptyList()
    LaunchedEffect(selectedGroupId) {
        val id = selectedGroupId ?: return@LaunchedEffect
        val name = groups.find { it.id == id }?.name ?: ""
        playerViewModel.selectGroup(id, name)
    }

    // When sidebar hides, move focus to detail pane
    LaunchedEffect(sidebarVisible) {
        if (!sidebarVisible) {
            try { detailFocusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = groupPickerSource != null) { groupPickerSource = null }
    BackHandler(enabled = contextMenuGroup != null) { contextMenuGroup = null }

    val settingsFocus = remember { FocusRequester() }
    LaunchedEffect(showSettings) {
        if (showSettings) try { settingsFocus.requestFocus() } catch (_: Exception) {}
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Left sidebar
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
                    nowPlaying = (state as? HomeViewModel.UiState.Success)?.nowPlaying ?: emptyMap(),
                    sidebarFocusRequester = sidebarFocusRequester,
                    detailFocusRequester = detailFocusRequester,
                    onFocused = { homeViewModel.selectGroup(it.id) },
                    onLongPress = { contextMenuGroup = it },
                    onPartyModeClick = { homeViewModel.partyMode() },
                    onSettingsClick = { showSettings = true },
                    onCollapseClick = { homeViewModel.toggleSidebar() },
                    onCollapseByKey = { homeViewModel.toggleSidebar() }
                )
            }

            // Detail pane
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .focusProperties { left = sidebarFocusRequester }
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

                // Show sidebar toggle when sidebar is hidden
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

        // Scrim
        AnimatedVisibility(visible = showSettings, enter = fadeIn(), exit = fadeOut()) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
        }

        // Settings panel
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

        // Context menu
        val menuGroup = contextMenuGroup
        if (menuGroup != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                RoomContextMenu(
                    group = menuGroup,
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
                    onGroupWith = {
                        groupPickerSource = menuGroup
                        contextMenuGroup = null
                    },
                    onGoSolo = {
                        homeViewModel.soloGroup(menuGroup.id)
                        contextMenuGroup = null
                    },
                    onDismiss = { contextMenuGroup = null }
                )
            }
        }

        val pickerSource = groupPickerSource
        if (pickerSource != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                GroupPickerOverlay(
                    sourceGroup = pickerSource,
                    availableGroups = groups.filter { it.id != pickerSource.id },
                    onPick = { target ->
                        homeViewModel.joinGroup(pickerSource.id, target.id)
                        groupPickerSource = null
                    },
                    onDismiss = { groupPickerSource = null }
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
    onCollapseByKey: () -> Unit
) {
    val listState = rememberLazyListState()
    val groups = (state as? HomeViewModel.UiState.Success)?.groups ?: emptyList()
    val sorted = remember(groups, primaryRoomId, favoriteRoomIds) { sortedGroups(groups) }

    // Scroll to keep selected item visible when sort order changes
    val selectedIndex = sorted.indexOfFirst { it.id == selectedGroupId }
    LaunchedEffect(selectedGroupId, sorted) {
        if (selectedIndex >= 0) listState.animateScrollToItem(selectedIndex)
    }

    Column(
        modifier = Modifier
            .width(320.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .focusProperties { right = detailFocusRequester }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                    onCollapseByKey(); true
                } else false
            }
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("x2rock", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SidebarIconButton(onClick = onPartyModeClick) {
                    Icon(Icons.Default.Celebration, contentDescription = "Party mode", modifier = Modifier.size(18.dp))
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
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(state.message, style = MaterialTheme.typography.bodySmall)
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
                val statusLine = when {
                    track?.name != null -> track.name
                    else -> group.playbackState.toSidebarLabel()
                }
                Text(
                    text = statusLine,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (isPrimary) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = "Primary room",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                } else if (isFavorite) {
                    Icon(
                        imageVector = Icons.Outlined.Star,
                        contentDescription = "Favorite room",
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
    isPrimary: Boolean,
    isFavorite: Boolean,
    otherGroups: List<Group>,
    onSetPrimary: () -> Unit,
    onToggleFavorite: () -> Unit,
    onGroupWith: () -> Unit,
    onGoSolo: () -> Unit,
    onDismiss: () -> Unit
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { firstFocus.requestFocus() } catch (_: Exception) {} }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp)
                .onKeyEvent { it.key != Key.Back },
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(group.name, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            AppButton(
                onClick = onSetPrimary,
                modifier = Modifier.fillMaxWidth().focusRequester(firstFocus)
            ) {
                Text(if (isPrimary) "Remove as Primary" else "Set as Primary")
            }
            AppButton(
                onClick = onToggleFavorite,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isFavorite) "Remove from Favorites" else "Add to Favorites")
            }
            if (otherGroups.isNotEmpty()) {
                AppButton(onClick = onGroupWith, modifier = Modifier.fillMaxWidth()) {
                    Text("Group With…")
                }
            }
            if (group.playerIds.size > 1) {
                AppButton(onClick = onGoSolo, modifier = Modifier.fillMaxWidth()) {
                    Text("Go Solo")
                }
            }
            AppButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cancel")
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GroupPickerOverlay(
    sourceGroup: Group,
    availableGroups: List<Group>,
    onPick: (Group) -> Unit,
    onDismiss: () -> Unit
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { firstFocus.requestFocus() } catch (_: Exception) {} }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp)
                .onKeyEvent { it.key != Key.Back },
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Group \"${sourceGroup.name}\" with…", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            availableGroups.forEachIndexed { index, group ->
                AppButton(
                    onClick = { onPick(group) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                ) {
                    Text(group.name)
                }
            }
            AppButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
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
                contentDescription = "Previous theme",
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
                contentDescription = "Next theme",
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
