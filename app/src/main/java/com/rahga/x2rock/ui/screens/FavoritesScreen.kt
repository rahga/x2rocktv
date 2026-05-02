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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.rahga.x2rock.ui.theme.AppButton
import com.rahga.x2rock.ui.theme.requestFocusSafely
import com.rahga.x2rock.model.Favorite
import com.rahga.x2rock.viewmodel.FavoritesViewModel

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FavoritesScreen(
    onBack: () -> Unit,
    viewModel: FavoritesViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val loadingId by viewModel.loadingFavoriteId.collectAsState()
    BackHandler { onBack() }

    Surface(modifier = Modifier.fillMaxSize()) {
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
                activeId = s.activeId,
                loadingId = loadingId,
                onBack = onBack,
                onPlay = { fav -> viewModel.loadFavorite(fav.id, onDone = onBack) }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FavoritesList(
    items: List<Favorite>,
    activeId: String?,
    loadingId: String?,
    onBack: () -> Unit,
    onPlay: (Favorite) -> Unit
) {
    val firstFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    LaunchedEffect(items.isNotEmpty()) {
        if (items.isNotEmpty()) {
            firstFocus.requestFocusSafely()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 48.dp, top = 40.dp, end = 48.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppButton(
                onClick = onBack,
                modifier = if (items.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier
            ) { Text("← Back") }
            Spacer(Modifier.width(24.dp))
            Text(
                text = if (items.isEmpty()) "No favorites" else "Favorites",
                style = MaterialTheme.typography.displaySmall
            )
        }
        Spacer(Modifier.height(24.dp))
        if (items.isEmpty()) return@Column

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(items) { index, fav ->
                FavoriteRow(
                    favorite = fav,
                    isActive = activeId == fav.id,
                    isLoading = loadingId == fav.id,
                    enabled = loadingId == null,
                    onClick = { onPlay(fav) },
                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FavoriteRow(
    favorite: Favorite,
    isActive: Boolean,
    isLoading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isActive) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                else Modifier
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            favorite.imageUrl?.let { url ->
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
                    text = favorite.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (favorite.description != null) {
                    Text(
                        text = favorite.description,
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
