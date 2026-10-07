package com.rahga.x2rock.ui.screens

import androidx.compose.runtime.getValue
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.rahga.x2rock.apple.AppleMusicItem
import com.rahga.x2rock.ui.components.NoticeBanner
import com.rahga.x2rock.ui.components.SearchField
import com.rahga.x2rock.ui.components.MediaRow
import com.rahga.x2rock.ui.components.RowStatus
import com.rahga.x2rock.ui.components.ScreenHeader
import com.rahga.x2rock.ui.theme.AppButton
import com.rahga.x2rock.ui.theme.requestFocusSafely
import com.rahga.x2rock.viewmodel.AppleMusicViewModel

/**
 * Apple Music's catalogue, searched and played in the room. Opens on the search field, so the
 * system keyboard — with its microphone, for dictating from the remote — is up at once. A press
 * on a result plays it in place of the queue; a hold, or Menu, adds it to the end.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppleMusicScreen(
    room: String,
    onBack: () -> Unit,
    onPlayed: () -> Unit,
    viewModel: AppleMusicViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsState()
    val kind by viewModel.kind.collectAsState()
    val results by viewModel.results.collectAsState()
    val starting by viewModel.starting.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val fieldFocus = remember { FocusRequester() }
    val resultsFocus = remember { FocusRequester() }
    BackHandler { onBack() }
    LaunchedEffect(Unit) { fieldFocus.requestFocusSafely() }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(start = 48.dp, top = 32.dp, end = 48.dp)) {
                ScreenHeader(title = "Apple Music", room = room, onBack = onBack)
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SearchField(
                        value = query,
                        placeholder = "Search songs or albums",
                        onValueChange = viewModel::setQuery,
                        onSearch = viewModel::search,
                        modifier = Modifier.weight(1f).focusRequester(fieldFocus),
                    )
                    Spacer(Modifier.width(16.dp))
                    AppButton(onClick = viewModel::search) { Text("Search") }
                    Spacer(Modifier.width(16.dp))
                    KindButton("Songs", kind == AppleMusicItem.Kind.SONG) { viewModel.setKind(AppleMusicItem.Kind.SONG) }
                    Spacer(Modifier.width(8.dp))
                    KindButton("Albums", kind == AppleMusicItem.Kind.ALBUM) { viewModel.setKind(AppleMusicItem.Kind.ALBUM) }
                }
                Spacer(Modifier.height(24.dp))
                when (val r = results) {
                    AppleMusicViewModel.Results.Idle -> Text(
                        "Search Apple's catalogue. What you pick plays through this system's own Apple Music.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    AppleMusicViewModel.Results.Searching ->
                        Text("Searching…", style = MaterialTheme.typography.titleMedium)
                    is AppleMusicViewModel.Results.Failed ->
                        Text(r.message, style = MaterialTheme.typography.bodyMedium)
                    is AppleMusicViewModel.Results.Found ->
                        if (r.items.isEmpty()) {
                            Text("Nothing found.", style = MaterialTheme.typography.titleMedium)
                        } else {
                            // The search field above traps a downward focus search, so the remote
                            // cannot reach the results on its own — move focus into the first row
                            // when results arrive. Same fix as the service-browse screen.
                            LaunchedEffect(r.items.first().objectId) {
                                repeat(5) {
                                    if (resultsFocus.requestFocusSafely()) return@LaunchedEffect
                                    kotlinx.coroutines.delay(50)
                                }
                            }
                            LazyColumn(
                                contentPadding = PaddingValues(bottom = 48.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                itemsIndexed(r.items, key = { _, item -> item.objectId }) { index, item ->
                                    ResultRow(
                                        item = item,
                                        isStarting = starting == item.objectId,
                                        onPlay = { viewModel.play(item, onDone = onPlayed) },
                                        onQueue = { viewModel.queue(item) },
                                        modifier = if (index == 0) Modifier.focusRequester(resultsFocus) else Modifier,
                                    )
                                }
                            }
                        }
                }
            }
            notice?.let { NoticeBanner(it, Modifier.align(Alignment.BottomCenter)) }
        }
    }
}

/** Songs or Albums, the chosen one outlined, as the radio screen marks its category. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun KindButton(label: String, selected: Boolean, onClick: () -> Unit) {
    AppButton(
        onClick = onClick,
        modifier = if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(50)) else Modifier,
    ) { Text(label) }
}

@Composable
private fun ResultRow(item: AppleMusicItem, isStarting: Boolean, onPlay: () -> Unit, onQueue: () -> Unit, modifier: Modifier = Modifier) {
    MediaRow(
        title = item.title,
        subtitle = item.artist,
        artUrl = item.artworkUrl,
        onClick = onPlay,
        onLongClick = onQueue,
        modifier = modifier,
    ) {
        if (isStarting) RowStatus("Starting…")
    }
}
