package com.rahga.x2rock.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.rahga.x2rock.ui.components.NoticeBanner
import com.rahga.x2rock.ui.components.dpadMenuKey
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.rahga.x2rock.model.isPlaying
import com.rahga.x2rock.ui.theme.AppButton
import com.rahga.x2rock.ui.theme.AppCard
import com.rahga.x2rock.ui.theme.requestFocusSafely
import com.rahga.x2rock.model.Favorite
import com.rahga.x2rock.model.Playlist
import com.rahga.x2rock.viewmodel.playlistKey
import com.rahga.x2rock.viewmodel.recentKey
import com.rahga.x2rock.model.HistoryItem
import com.rahga.x2rock.viewmodel.FavoritesViewModel
import com.rahga.x2rock.viewmodel.PlayerViewModel

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FavoritesScreen(
    onBack: () -> Unit,
    onOpenRadio: (groupId: String) -> Unit,
    onOpenAppleMusic: (groupId: String) -> Unit,
    onOpenServices: (groupId: String) -> Unit,
    playerViewModel: PlayerViewModel,
    viewModel: FavoritesViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val loadingId by viewModel.loadingFavoriteId.collectAsState()
    val playerState by playerViewModel.uiState.collectAsState()
    val notice by viewModel.notice.collectAsState()
    BackHandler { onBack() }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                when (val s = state) {
                    is FavoritesViewModel.UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Loading favorites…", style = MaterialTheme.typography.titleLarge)
                    }
                    is FavoritesViewModel.UiState.Error -> Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("Failed to load favorites", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(s.message, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(24.dp))
                        AppButton(onClick = { viewModel.reload() }) { Text("Retry") }
                    }
                    is FavoritesViewModel.UiState.Success -> FavoritesList(
                        items = s.items,
                        playlists = s.playlists,
                        recent = s.recent,
                        recentNote = s.recentNote,
                        activeId = s.activeId,
                        loadingId = loadingId,
                        onBack = onBack,
                        onPlay = { fav -> viewModel.loadFavorite(fav.id, onDone = onBack) },
                        onPlayPlaylist = { playlist -> viewModel.loadPlaylist(playlist.id, onDone = onBack) },
                        onReplay = { item -> viewModel.replay(item, onDone = onBack) },
                        onAppendPlaylist = viewModel::appendPlaylist,
                        onOpenRadio = { onOpenRadio(viewModel.groupId) },
                        onOpenAppleMusic = if (s.appleMusic) ({ onOpenAppleMusic(viewModel.groupId) }) else null,
                        onOpenServices = { onOpenServices(viewModel.groupId) },
                    )
                }
                // This screen's own failures, or the now-playing bar's, which go through the
                // player — either would otherwise be said only on a pane this screen covers.
                (notice ?: playerState.notice)?.let {
                    NoticeBanner(it, Modifier.align(Alignment.BottomCenter))
                }
            }
            val nowPlayingTrack = playerState.trackName
            if (nowPlayingTrack != null) {
                NowPlayingBar(
                    trackName = nowPlayingTrack,
                    artistName = playerState.artistName,
                    isCurrentlyPlaying = playerState.playbackState.isPlaying(),
                    onPlayPause = { playerViewModel.togglePlayPause() }
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FavoritesList(
    items: List<Favorite>,
    playlists: List<Playlist>,
    recent: List<HistoryItem>,
    recentNote: String?,
    activeId: String?,
    loadingId: String?,
    onBack: () -> Unit,
    onPlay: (Favorite) -> Unit,
    onPlayPlaylist: (Playlist) -> Unit,
    onReplay: (HistoryItem) -> Unit,
    onAppendPlaylist: (Playlist) -> Unit,
    onOpenRadio: () -> Unit,
    /** Null for a household with no Apple Music to play a result through. */
    onOpenAppleMusic: (() -> Unit)?,
    onOpenServices: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }

    val anything = items.isNotEmpty() || playlists.isNotEmpty() || recent.isNotEmpty()
    LaunchedEffect(anything) {
        if (anything) firstFocus.requestFocusSafely()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 48.dp, top = 40.dp, end = 48.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppButton(
                onClick = onBack,
                modifier = if (!anything) Modifier.focusRequester(firstFocus) else Modifier
            ) { Text("← Back") }
            Spacer(Modifier.width(24.dp))
            Text(
                text = if (!anything) "No favorites" else "Favorites",
                style = MaterialTheme.typography.displaySmall
            )
            Spacer(Modifier.width(24.dp))
            // The radio directory: stations no favourite holds, with no account and no typing.
            // Here because this is where someone looking for something to play already is.
            AppButton(onClick = onOpenRadio) { Text("Radio") }
            // Apple Music's catalogue, searched through Apple's public search and played through
            // this household's own Apple Music. Only where the household has played from it, since
            // that is where its account is learned.
            onOpenAppleMusic?.let {
                Spacer(Modifier.width(16.dp))
                AppButton(onClick = it) { Text("Search Apple Music") }
            }
            // This system's other music services — Qobuz, TIDAL, Deezer and the rest — searched
            // and browsed with its own stored login. The screen itself says when there are none.
            Spacer(Modifier.width(16.dp))
            AppButton(onClick = onOpenServices) { Text("Music Services") }
        }
        Spacer(Modifier.height(24.dp))
        if (!anything) return@Column

        LazyColumn(
            contentPadding = PaddingValues(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(items) { index, fav ->
                FavoriteRow(
                    name = fav.name,
                    description = fav.description,
                    imageUrl = fav.imageUrl,
                    isActive = activeId == fav.id,
                    isLoading = loadingId == fav.id,
                    onClick = { onPlay(fav) },
                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier
                )
            }
            // The household's saved queues. A separate namespace from favourites, and a
            // separate list in the Sonos app, so they are a section of their own here.
            if (playlists.isNotEmpty()) {
                item {
                    Text(
                        "Playlists",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                }
                itemsIndexed(playlists) { index, playlist ->
                    FavoriteRow(
                        name = playlist.name,
                        description = playlist.trackCount?.let { if (it == 1) "1 track" else "$it tracks" },
                        imageUrl = null,
                        isActive = false,
                        isLoading = loadingId == playlistKey(playlist.id),
                        onClick = { onPlayPlaylist(playlist) },
                        // Hold, or the Menu key: add to the end of the queue instead of
                        // replacing it.
                        onLongPress = { onAppendPlaylist(playlist) },
                        modifier = if (items.isEmpty() && index == 0) Modifier.focusRequester(firstFocus) else Modifier
                    )
                }
            }
            // The Sonos app's "Recently played": named by service ids rather than favourites,
            // so it can name something gone; a refusal says so when pressed.
            if (recent.isNotEmpty() || recentNote != null) {
                item {
                    Text(
                        "Recently played",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                }
            }
            recentNote?.let { note ->
                item { Text(note, style = MaterialTheme.typography.bodySmall) }
            }
            itemsIndexed(recent) { index, entry ->
                FavoriteRow(
                    name = entry.name,
                    description = kindLabel(entry.type),
                    imageUrl = entry.images.firstOrNull()?.url,
                    isActive = false,
                    isLoading = loadingId == recentKey(entry),
                    onClick = { onReplay(entry) },
                    modifier = if (items.isEmpty() && playlists.isEmpty() && index == 0) Modifier.focusRequester(firstFocus) else Modifier
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FavoriteRow(
    name: String,
    description: String?,
    imageUrl: String?,
    isActive: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
) {
    AppCard(
        onClick = onClick,
        onLongClick = onLongPress,
        modifier = modifier
            .fillMaxWidth()
            .then(if (onLongPress != null) Modifier.dpadMenuKey(onLongPress) else Modifier)
            .then(
                if (isActive) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                else Modifier
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            imageUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
                Spacer(Modifier.width(16.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                description?.let { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (isActive && !isLoading) {
                Spacer(Modifier.width(16.dp))
                Text("▶ Now Playing", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if (isLoading) {
                Spacer(Modifier.width(16.dp))
                Text("Playing…", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** What a recently played item is, in the Sonos app's words. A program is a radio show. */
private fun kindLabel(type: String): String? = when (type) {
    "album" -> "Album"
    "playlist" -> "Playlist"
    "program", "stream" -> "Radio"
    "track" -> "Track"
    "container" -> null
    else -> null
}
