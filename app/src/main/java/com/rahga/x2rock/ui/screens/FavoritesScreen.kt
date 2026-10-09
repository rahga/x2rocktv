package com.rahga.x2rock.ui.screens

import androidx.compose.runtime.getValue
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.tv.material3.Icon
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.rahga.x2rock.model.HistoryItem
import com.rahga.x2rock.store.Preset
import com.rahga.x2rock.ui.components.DotScanner
import com.rahga.x2rock.ui.components.MediaRow
import com.rahga.x2rock.ui.components.NoticeBanner
import com.rahga.x2rock.ui.components.RowStatus
import com.rahga.x2rock.ui.components.ScreenHeader
import com.rahga.x2rock.ui.theme.AppButton
import com.rahga.x2rock.ui.theme.requestFocusRetrying
import com.rahga.x2rock.ui.theme.IconLabelButton
import com.rahga.x2rock.ui.components.SectionTitle
import com.rahga.x2rock.viewmodel.favoriteGroups
import com.rahga.x2rock.viewmodel.favoriteLine
import com.rahga.x2rock.viewmodel.favoritePage
import com.rahga.x2rock.viewmodel.kindLabel
import com.rahga.x2rock.model.Favorite
import com.rahga.x2rock.model.Playlist
import com.rahga.x2rock.viewmodel.FavoritesViewModel
import com.rahga.x2rock.viewmodel.PlayerViewModel
import com.rahga.x2rock.viewmodel.playlistKey
import com.rahga.x2rock.viewmodel.presetKey
import com.rahga.x2rock.viewmodel.recentKey

/**
 * Browse: everything there is to play in the room, in one list — this device's presets, the
 * household's Sonos favourites, its playlists, and what it played lately — with the radio directory
 * and the music services one press up. The player pane's Browse button opens it.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FavoritesScreen(
    room: String,
    onBack: () -> Unit,
    onOpenRadio: (groupId: String) -> Unit,
    onOpenServices: (groupId: String) -> Unit,
    /** Open an album or playlist favourite on its service's page: the service, its id, the favourite. */
    onOpenFavorite: (groupId: String, serviceKey: String, containerId: String, favorite: Favorite) -> Unit,
    playerViewModel: PlayerViewModel,
    viewModel: FavoritesViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val presets by viewModel.presets.collectAsState()
    val deleteArmed by viewModel.deleteArmed.collectAsState()
    val loadingId by viewModel.loadingFavoriteId.collectAsState()
    val playerState by playerViewModel.uiState.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val browsable by viewModel.browsable.collectAsState()
    BackHandler { onBack() }

    // Focus starts on the first row of the first section there is, so a press of the remote does
    // something at once; with no row — loading, a failed load, nothing saved — on Back.
    val backFocus = remember { FocusRequester() }
    val rowFocus = remember { FocusRequester() }
    val firstSection = (state as? FavoritesViewModel.UiState.Success)?.let { firstSection(presets, it) }
    LaunchedEffect(firstSection, state::class) {
        (if (firstSection != null) rowFocus else backFocus).requestFocusRetrying()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                Column(Modifier.fillMaxSize().padding(start = 48.dp, top = 32.dp, end = 48.dp)) {
                    ScreenHeader(title = "Browse", room = room, onBack = onBack, backModifier = Modifier.focusRequester(backFocus)) {
                        // The radio directory: stations no favourite holds, with no account and no
                        // typing. And the household's own services, searched and browsed with its
                        // stored login — Apple Music among them.
                        IconLabelButton(Icons.Default.Radio, "Radio", { onOpenRadio(viewModel.groupId) })
                        Spacer(Modifier.width(16.dp))
                        IconLabelButton(Icons.Default.LibraryMusic, "Music Services", { onOpenServices(viewModel.groupId) })
                    }
                    Spacer(Modifier.height(20.dp))
                    when (val s = state) {
                        is FavoritesViewModel.UiState.Loading -> DotScanner()
                        is FavoritesViewModel.UiState.Error -> Column {
                            Text("Couldn't load favourites", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(8.dp))
                            Text(s.message, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(24.dp))
                            AppButton(onClick = { viewModel.reload() }) { Text("Retry") }
                        }
                        is FavoritesViewModel.UiState.Success -> BrowseList(
                            firstSection = firstSection,
                            rowFocus = rowFocus,
                            presets = presets,
                            deleteArmed = deleteArmed,
                            state = s,
                            loadingId = loadingId,
                            onPreset = { viewModel.applyPreset(it, onDone = onBack) },
                            onDeletePreset = viewModel::deletePreset,
                            onPlay = { fav ->
                                val page = favoritePage(fav, browsable)
                                if (page != null) onOpenFavorite(viewModel.groupId, page.first, page.second, fav)
                                else viewModel.loadFavorite(fav.id, onDone = onBack)
                            },
                            opens = { favoritePage(it, browsable) != null },
                            onPlayPlaylist = { playlist -> viewModel.loadPlaylist(playlist.id, onDone = onBack) },
                            onAppendPlaylist = viewModel::appendPlaylist,
                            onReplay = { item -> viewModel.replay(item, onDone = onBack) },
                        )
                    }
                }
                // This screen's own failures, or the now-playing bar's, which go through the
                // player — either would otherwise be said only on a pane this screen covers.
                (notice ?: playerState.notice)?.let {
                    NoticeBanner(it, Modifier.align(Alignment.BottomCenter))
                }
            }
            NowPlayingBar(playerState, onPlayPause = { playerViewModel.togglePlayPause() })
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BrowseList(
    /** Which section's first row takes focus, or `null` when there is nothing listed. */
    firstSection: String?,
    rowFocus: FocusRequester,
    presets: List<Preset>,
    deleteArmed: String?,
    state: FavoritesViewModel.UiState.Success,
    loadingId: String?,
    onPreset: (Preset) -> Unit,
    onDeletePreset: (Preset) -> Unit,
    onPlay: (Favorite) -> Unit,
    /** Whether a favourite opens on a page of its own rather than playing at once. */
    opens: (Favorite) -> Boolean,
    onPlayPlaylist: (Playlist) -> Unit,
    onAppendPlaylist: (Playlist) -> Unit,
    onReplay: (HistoryItem) -> Unit,
) {
    if (firstSection == null) {
        Text(
            "Nothing saved yet. Favourites added in the Sonos app appear here, and a room's panel can " +
                "save it as a preset. Radio and Music Services are above.",
            style = MaterialTheme.typography.bodyLarge,
        )
        return
    }
    // The first row of the first section takes the initial focus. Decided above, not while the
    // list is built: the builder can run again on its own.
    fun claim(section: String): Modifier = if (section == firstSection) Modifier.focusRequester(rowFocus) else Modifier
    fun LazyListScope.section(title: String, key: String) =
        item(key = "section:$title") { SectionTitle(title, first = key == firstSection) }

    LazyColumn(contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (presets.isNotEmpty()) {
            section("Presets", "presets")
            val presetFocus = claim("presets")
            itemsIndexed(presets, key = { _, p -> "preset:${p.id}" }) { index, preset ->
                MediaRow(
                    title = preset.name,
                    subtitle = presetSummary(preset),
                    artUrl = null,
                    onClick = { onPreset(preset) },
                    // Menu, twice, deletes: the first press arms and says so.
                    onLongClick = { onDeletePreset(preset) },
                    modifier = if (index == 0) presetFocus else Modifier,
                ) {
                    when {
                        loadingId == presetKey(preset.id) -> RowStatus("Starting…")
                        deleteArmed == preset.id -> RowStatus("Menu again to delete", highlighted = true)
                    }
                }
            }
        }
        if (state.items.isNotEmpty()) {
            val favFocus = claim("favorites")
            favoriteGroups(state.items).forEachIndexed { groupIndex, (title, favorites) ->
                item(key = "section:$title") { SectionTitle(title, first = groupIndex == 0 && firstSection == "favorites") }
                itemsIndexed(favorites, key = { _, f -> "fav:${f.id}" }) { index, fav ->
                    MediaRow(
                        title = fav.name,
                        subtitle = favoriteLine(fav),
                        artUrl = fav.imageUrl,
                        active = state.activeId == fav.id,
                        onClick = { onPlay(fav) },
                        modifier = if (groupIndex == 0 && index == 0) favFocus else Modifier,
                    ) {
                        when {
                            loadingId == fav.id -> RowStatus("Starting…")
                            state.activeId == fav.id -> RowStatus("Now playing", highlighted = true)
                            // An album or playlist opens on its tracks, with Play and Shuffle at the head.
                            opens(fav) -> Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(28.dp))
                        }
                    }
                }
            }
        }
        // The household's saved queues. A separate namespace from favourites, and a separate
        // list in the Sonos app, so they are a section of their own here.
        if (state.playlists.isNotEmpty()) {
            section("Playlists", "playlists")
            val listFocus = claim("playlists")
            itemsIndexed(state.playlists, key = { _, p -> "playlist:${p.id}" }) { index, playlist ->
                MediaRow(
                    title = playlist.name,
                    subtitle = playlist.trackCount?.let { if (it == 1) "1 track" else "$it tracks" },
                    artUrl = null,
                    onClick = { onPlayPlaylist(playlist) },
                    // Hold, or the Menu key: add to the end of the queue instead of replacing it.
                    onLongClick = { onAppendPlaylist(playlist) },
                    modifier = if (index == 0) listFocus else Modifier,
                ) {
                    if (loadingId == playlistKey(playlist.id)) RowStatus("Starting…")
                }
            }
        }
        // The Sonos app's "Recently played": named by service ids rather than favourites, so it
        // can name something gone; a refusal says so when pressed.
        if (state.recent.isNotEmpty() || state.recentNote != null) section("Recently played", "recent")
        state.recentNote?.let { note -> item { Text(note, style = MaterialTheme.typography.bodyMedium) } }
        if (state.recent.isNotEmpty()) {
            val recentFocus = claim("recent")
            itemsIndexed(state.recent, key = { i, e -> "recent:$i:${e.id.objectId}" }) { index, entry ->
                MediaRow(
                    title = entry.name,
                    subtitle = kindLabel(entry.type),
                    artUrl = entry.images.firstOrNull()?.url,
                    onClick = { onReplay(entry) },
                    modifier = if (index == 0) recentFocus else Modifier,
                ) {
                    if (loadingId == recentKey(entry)) RowStatus("Starting…")
                }
            }
        }
    }
}

/** The first section with rows — where focus starts — or `null` when Browse lists nothing. */
private fun firstSection(presets: List<Preset>, state: FavoritesViewModel.UiState.Success): String? = when {
    presets.isNotEmpty() -> "presets"
    state.items.isNotEmpty() -> "favorites"
    state.playlists.isNotEmpty() -> "playlists"
    state.recent.isNotEmpty() -> "recent"
    else -> null
}

/** What a preset brings back, in a line: how many rooms, and at what levels. */
private fun presetSummary(preset: Preset): String {
    val rooms = if (preset.playerIds.size == 1) "1 room" else "${preset.playerIds.size} rooms"
    val levels = preset.volumes.values.distinct().sorted()
    val level = when {
        levels.isEmpty() -> null
        levels.size == 1 -> "level ${levels.single()}"
        else -> "levels ${levels.first()}–${levels.last()}"
    }
    val music = if (preset.favoriteId == null) "keeps what plays" else null
    return listOfNotNull(rooms, level, music).joinToString(" · ")
}
