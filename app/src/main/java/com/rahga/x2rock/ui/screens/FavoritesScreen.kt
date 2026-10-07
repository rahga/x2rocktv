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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.rahga.x2rock.model.HistoryItem
import com.rahga.x2rock.store.Preset
import com.rahga.x2rock.ui.components.MediaRow
import com.rahga.x2rock.ui.components.NoticeBanner
import com.rahga.x2rock.ui.components.RowStatus
import com.rahga.x2rock.ui.components.ScreenHeader
import com.rahga.x2rock.ui.theme.AppButton
import com.rahga.x2rock.ui.theme.requestFocusSafely
import com.rahga.x2rock.viewmodel.FavoritesViewModel
import com.rahga.x2rock.viewmodel.PlayerViewModel
import com.rahga.x2rock.viewmodel.playlistKey
import com.rahga.x2rock.viewmodel.presetKey
import com.rahga.x2rock.viewmodel.recentKey

/**
 * Browse: everything there is to play in the room, in one list — this device's presets, the
 * household's Sonos favourites, its playlists, and what it played lately — with the radio directory
 * and the music services one press up. The sidebar's Browse opens it, and so does the player's.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FavoritesScreen(
    room: String,
    onBack: () -> Unit,
    onOpenRadio: (groupId: String) -> Unit,
    onOpenServices: (groupId: String) -> Unit,
    playerViewModel: PlayerViewModel,
    viewModel: FavoritesViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val presets by viewModel.presets.collectAsState()
    val deleteArmed by viewModel.deleteArmed.collectAsState()
    val loadingId by viewModel.loadingFavoriteId.collectAsState()
    val playerState by playerViewModel.uiState.collectAsState()
    val notice by viewModel.notice.collectAsState()
    BackHandler { onBack() }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                Column(Modifier.fillMaxSize().padding(start = 48.dp, top = 32.dp, end = 48.dp)) {
                    ScreenHeader(title = "Browse", room = room, onBack = onBack) {
                        // The radio directory: stations no favourite holds, with no account and no
                        // typing. And the household's own services, searched and browsed with its
                        // stored login — Apple Music among them.
                        PlaceButton(Icons.Default.Radio, "Radio") { onOpenRadio(viewModel.groupId) }
                        Spacer(Modifier.width(16.dp))
                        PlaceButton(Icons.Default.LibraryMusic, "Music Services") { onOpenServices(viewModel.groupId) }
                    }
                    Spacer(Modifier.height(20.dp))
                    when (val s = state) {
                        is FavoritesViewModel.UiState.Loading ->
                            Text("Loading…", style = MaterialTheme.typography.titleLarge)
                        is FavoritesViewModel.UiState.Error -> Column {
                            Text("Couldn't load favourites", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(8.dp))
                            Text(s.message, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(24.dp))
                            AppButton(onClick = { viewModel.reload() }) { Text("Retry") }
                        }
                        is FavoritesViewModel.UiState.Success -> BrowseList(
                            presets = presets,
                            deleteArmed = deleteArmed,
                            state = s,
                            loadingId = loadingId,
                            onPreset = { viewModel.applyPreset(it, onDone = onBack) },
                            onDeletePreset = viewModel::deletePreset,
                            onPlay = { fav -> viewModel.loadFavorite(fav.id, onDone = onBack) },
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

/** A place this screen leads to: an icon and its name. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlaceButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    AppButton(onClick = onClick) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BrowseList(
    presets: List<Preset>,
    deleteArmed: String?,
    state: FavoritesViewModel.UiState.Success,
    loadingId: String?,
    onPreset: (Preset) -> Unit,
    onDeletePreset: (Preset) -> Unit,
    onPlay: (com.rahga.x2rock.model.Favorite) -> Unit,
    onPlayPlaylist: (com.rahga.x2rock.model.Playlist) -> Unit,
    onAppendPlaylist: (com.rahga.x2rock.model.Playlist) -> Unit,
    onReplay: (HistoryItem) -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    val anything = presets.isNotEmpty() || state.items.isNotEmpty() || state.playlists.isNotEmpty() ||
        state.recent.isNotEmpty()
    // The first row of whatever is listed, so a press of the remote does something at once.
    LaunchedEffect(anything) {
        if (!anything) return@LaunchedEffect
        repeat(5) {
            if (firstFocus.requestFocusSafely()) return@LaunchedEffect
            kotlinx.coroutines.delay(50)
        }
    }
    if (!anything) {
        Text(
            "Nothing saved yet. Favourites added in the Sonos app appear here, and a room's panel can " +
                "save it as a preset. Radio and Music Services are above.",
            style = MaterialTheme.typography.bodyLarge,
        )
        return
    }
    // Only the first row of the first non-empty section takes the initial focus. Decided here,
    // not while the list is built: the builder can run again on its own.
    val firstSection = when {
        presets.isNotEmpty() -> "presets"
        state.items.isNotEmpty() -> "favorites"
        state.playlists.isNotEmpty() -> "playlists"
        else -> "recent"
    }
    fun claim(section: String): Modifier = if (section == firstSection) Modifier.focusRequester(firstFocus) else Modifier

    LazyColumn(contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (presets.isNotEmpty()) {
            section("Presets", first = true)
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
            section("Favorites", first = presets.isEmpty())
            val favFocus = claim("favorites")
            itemsIndexed(state.items, key = { _, f -> "fav:${f.id}" }) { index, fav ->
                MediaRow(
                    title = fav.name,
                    subtitle = fav.description,
                    artUrl = fav.imageUrl,
                    active = state.activeId == fav.id,
                    onClick = { onPlay(fav) },
                    modifier = if (index == 0) favFocus else Modifier,
                ) {
                    when {
                        loadingId == fav.id -> RowStatus("Starting…")
                        state.activeId == fav.id -> RowStatus("Now playing", highlighted = true)
                    }
                }
            }
        }
        // The household's saved queues. A separate namespace from favourites, and a separate
        // list in the Sonos app, so they are a section of their own here.
        if (state.playlists.isNotEmpty()) {
            section("Playlists")
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
        if (state.recent.isNotEmpty() || state.recentNote != null) section("Recently played")
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

private fun LazyListScope.section(title: String, first: Boolean = false) {
    item(key = "section:$title") { SectionTitle(title, first) }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SectionTitle(title: String, first: Boolean) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(top = if (first) 0.dp else 16.dp, bottom = 4.dp),
    )
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

/** What a recently played item is, in the Sonos app's words. A program is a radio show. */
private fun kindLabel(type: String): String? = when (type) {
    "album" -> "Album"
    "playlist" -> "Playlist"
    "program", "stream" -> "Radio"
    "track" -> "Track"
    else -> null
}
