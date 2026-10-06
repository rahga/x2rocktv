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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.rahga.x2rock.apple.AppleMusicItem
import com.rahga.x2rock.ui.components.NoticeBanner
import com.rahga.x2rock.ui.components.dpadMenuKey
import com.rahga.x2rock.ui.theme.AppButton
import com.rahga.x2rock.ui.theme.AppCard
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
    BackHandler { onBack() }
    LaunchedEffect(Unit) { fieldFocus.requestFocusSafely() }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(start = 48.dp, top = 40.dp, end = 48.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppButton(onClick = onBack) { Text("← Back") }
                    Spacer(Modifier.width(24.dp))
                    Text("Apple Music", style = MaterialTheme.typography.displaySmall)
                }
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SearchField(
                        value = query,
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
                            LazyColumn(
                                contentPadding = PaddingValues(bottom = 48.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(r.items, key = { it.objectId }) { item ->
                                    ResultRow(
                                        item = item,
                                        isStarting = starting == item.objectId,
                                        onPlay = { viewModel.play(item, onDone = onPlayed) },
                                        onQueue = { viewModel.queue(item) },
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

/**
 * Plain text entry, outlined in the theme's colours: tv-material3 has no text field, and the
 * app sets no material3 theme for one to draw in. Focusing it brings up the system keyboard.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, onSearch: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    // The keyboard goes once the search is sent: the results are under it.
    val search = {
        keyboard?.hide()
        onSearch()
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { search() }),
        // A real keyboard's Enter, too — the emulator's, a tablet's. Only the on-screen keyboard
        // sends the search action; a key is a key.
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                    search()
                    true
                } else false
            },
        decorationBox = { inner ->
            Box(
                Modifier
                    .border(2.dp, if (focused) colors.primary else colors.border, RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (value.isEmpty()) {
                    Text("Search songs or albums", style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
                }
                inner()
            }
        },
    )
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ResultRow(item: AppleMusicItem, isStarting: Boolean, onPlay: () -> Unit, onQueue: () -> Unit) {
    AppCard(
        onClick = onPlay,
        onLongClick = onQueue,
        modifier = Modifier.fillMaxWidth().dpadMenuKey(onQueue),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Box(Modifier.size(56.dp)) {
                item.artworkUrl?.let { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)),
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                item.artist?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (isStarting) Text("Starting…", style = MaterialTheme.typography.bodySmall)
        }
    }
}
