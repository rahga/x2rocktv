package com.rahga.x2rock.ui.screens

import com.rahga.x2rock.ui.theme.requestFocusWhenResumed
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.rahga.x2rock.ui.components.DotScanner
import com.rahga.x2rock.ui.components.NoticeBanner
import com.rahga.x2rock.ui.components.MediaRow
import com.rahga.x2rock.ui.components.RowStatus
import com.rahga.x2rock.ui.components.ScreenHeader
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.rahga.x2rock.ui.theme.AppButton
import com.rahga.x2rock.ui.theme.requestFocusSafely
import com.rahga.x2rock.ui.theme.requestFocusRetrying
import com.rahga.x2rock.ui.components.ItemMenu
import com.rahga.x2rock.ui.components.MenuAction
import com.rahga.x2rock.ui.components.MenuSection
import com.rahga.x2rock.viewmodel.PlayerViewModel
import com.rahga.x2rock.viewmodel.QueueEntry
import com.rahga.x2rock.viewmodel.QueueViewModel
import com.rahga.x2rock.viewmodel.neighbourSlots
import com.rahga.x2rock.viewmodel.focusAfterPlay
import com.rahga.x2rock.viewmodel.slotAfterStaleMenu

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun QueueScreen(
    room: String,
    onBack: () -> Unit,
    playerViewModel: PlayerViewModel,
    viewModel: QueueViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val playerState by playerViewModel.uiState.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val clearArmed by viewModel.clearArmed.collectAsState()
    BackHandler { onBack() }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                when (val s = state) {
                    is QueueViewModel.UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        DotScanner(description = "Loading the queue")
                    }
                    is QueueViewModel.UiState.Error -> Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        // Retry takes first focus: with nothing asking, the first Select did nothing
                        // and the first arrow let the platform choose, which was Back (P-119).
                        val retryFocus = remember { FocusRequester() }
                        val lifecycle = LocalLifecycleOwner.current.lifecycle
                        LaunchedEffect(Unit) { retryFocus.requestFocusWhenResumed(lifecycle) }
                        Text("Failed to load queue", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(s.message, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(24.dp))
                        AppButton(onClick = { viewModel.reload() }, modifier = Modifier.focusRequester(retryFocus)) { Text("Retry") }
                    }
                    is QueueViewModel.UiState.Success -> QueueList(
                        room = room,
                        entries = s.entries,
                        currentTrackName = s.currentTrackName,
                        currentSlot = s.currentSlot,
                        inUse = s.inUse,
                        onBack = onBack,
                        onPlayItem = viewModel::playItem,
                        onRemoveItem = viewModel::removeItem,
                        onMoveUp = viewModel::moveUp,
                        onMoveDown = viewModel::moveDown,
                        queueVersion = { viewModel.queueVersion },
                        clearArmed = clearArmed,
                        onClear = viewModel::clearQueue,
                        onSave = viewModel::saveAsPlaylist,
                    )
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
private fun QueueList(
    room: String,
    entries: List<QueueEntry>,
    currentTrackName: String?,
    /** The slot playing, where known: it, not the name, then marks the row. */
    currentSlot: Int?,
    inUse: Boolean,
    onBack: () -> Unit,
    onPlayItem: (Int) -> Unit,
    onRemoveItem: (Int, String?) -> Unit,
    onMoveUp: (Int, String?) -> Unit,
    onMoveDown: (Int, String?) -> Unit,
    /** The queue's version now, noted as a menu opens so its edit can tell the queue moved under it. */
    queueVersion: () -> String,
    clearArmed: Boolean,
    onClear: () -> Unit,
    onSave: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var contextMenuEntry by remember { mutableStateOf<QueueEntry?>(null) }
    var menuOpenedAt by remember { mutableStateOf<String?>(null) }
    // The menu traps focus, so closing it has to hand focus back: to the slot of the row it was
    // opened on — where a removed track's successor now sits — or the slot a moved track went to.
    // Rows are keyed by position, so the slot keeps focus while the queue is read again under it.
    var returnTo by remember { mutableStateOf<Int?>(null) }
    // A menu's track gone from its slot, by another controller's edit: the menu closes, rather than
    // offering actions that now name something else (P-41), and says where focus goes (P-52).
    LaunchedEffect(entries, contextMenuEntry) {
        val open = contextMenuEntry ?: return@LaunchedEffect
        if (entries.firstOrNull { it.trackNumber == open.trackNumber }?.item?.track == open.item.track) return@LaunchedEffect
        returnTo = slotAfterStaleMenu(entries, open)
        contextMenuEntry = null
    }
    // A press reads the queue first and plays its track where it now is (P-57); focus follows it
    // there once the list read with it arrives, which is the first one after the press.
    var pressed by remember { mutableStateOf<Pair<QueueEntry, List<QueueEntry>>?>(null) }
    LaunchedEffect(entries, pressed) {
        val (entry, against) = pressed ?: return@LaunchedEffect
        if (entries === against) return@LaunchedEffect
        focusAfterPlay(entries, entry)?.let { returnTo = it }
        pressed = null
    }
    val returnFocus = remember { FocusRequester() }
    LaunchedEffect(contextMenuEntry, returnTo) {
        if (contextMenuEntry != null) return@LaunchedEffect
        if (returnTo == null) return@LaunchedEffect
        if (!returnFocus.requestFocusRetrying()) firstFocus.requestFocusSafely()
        returnTo = null
    }

    fun isCurrent(entry: QueueEntry) =
        if (currentSlot != null) entry.trackNumber == currentSlot
        else currentTrackName != null && entry.item.track?.name == currentTrackName
    val currentIndex = remember(entries, currentTrackName, currentSlot) { entries.indexOfFirst(::isCurrent) }
    val focusTargetIndex = if (currentIndex >= 0) currentIndex else 0

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(entries.isNotEmpty()) {
        if (entries.isNotEmpty()) {
            if (currentIndex > 0) listState.scrollToItem(currentIndex)
            // Retried: the row scrolled to is composed in the frames after the scroll, so a single
            // request found nothing in a long queue and focus fell to the buttons above.
            firstFocus.requestFocusRetrying()
        } else {
            // Empty, Back has it — on opening, and once a Clear has taken the rows and its own
            // button away. Nothing asked before, so the first Select did nothing (P-119).
            firstFocus.requestFocusWhenResumed(lifecycle)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 48.dp, top = 32.dp, end = 48.dp)
        ) {
            ScreenHeader(
                title = if (entries.isEmpty()) "Queue is empty" else "Queue (${entries.size})",
                room = room,
                onBack = onBack,
                backModifier = if (entries.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier,
            ) {
                if (entries.isNotEmpty()) {
                    AppButton(onClick = onSave) { Text("Save as playlist") }
                    Spacer(Modifier.width(12.dp))
                    // Two presses: the label says what the second will do.
                    AppButton(onClick = onClear) { Text(if (clearArmed) "Press again to clear" else "Clear") }
                }
            }
            if (!inUse && entries.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "The room is playing something else. Choose a track to go back to the queue.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Spacer(Modifier.height(24.dp))
            if (entries.isEmpty()) return@Column

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(entries) { index, entry ->
                    QueueRow(
                        entry = entry,
                        isCurrent = isCurrent(entry),
                        onClick = { pressed = entry to entries; onPlayItem(entry.trackNumber) },
                        onLongPress = { menuOpenedAt = queueVersion(); contextMenuEntry = entry },
                        modifier = Modifier
                            .then(if (index == focusTargetIndex) Modifier.focusRequester(firstFocus) else Modifier)
                            .then(if (entry.trackNumber == returnTo) Modifier.focusRequester(returnFocus) else Modifier)
                    )
                }
            }
        }

        val menuEntry = contextMenuEntry
        if (menuEntry != null) {
            val neighbours = neighbourSlots(entries, menuEntry.trackNumber)
            val track = menuEntry.item.track
            // An action names where focus returns to; the close that follows it keeps that, and
            // a plain dismiss returns to the row the menu was opened on.
            ItemMenu(
                title = track?.name ?: "Unknown track",
                subtitle = listOfNotNull(track?.artist?.name, track?.album?.name).joinToString(" · ").ifEmpty { null },
                artUrl = track?.imageUrl,
                sections = listOf(MenuSection(null, listOfNotNull(
                    MenuAction("Remove from Queue") { onRemoveItem(menuEntry.trackNumber, menuOpenedAt); returnTo = menuEntry.trackNumber },
                    // Offered only where there is somewhere to move to, rather than disabled.
                    neighbours.first?.let { up -> MenuAction("Move up") { onMoveUp(menuEntry.trackNumber, menuOpenedAt); returnTo = up } },
                    neighbours.second?.let { down -> MenuAction("Move down") { onMoveDown(menuEntry.trackNumber, menuOpenedAt); returnTo = down } },
                ))),
                onDismiss = { if (returnTo == null) returnTo = menuEntry.trackNumber; contextMenuEntry = null },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun QueueRow(
    entry: QueueEntry,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val track = entry.item.track
    MediaRow(
        title = track?.name ?: "Unknown track",
        subtitle = listOfNotNull(track?.artist?.name, track?.album?.name).joinToString(" · ").ifEmpty { null },
        artUrl = track?.imageUrl,
        leading = "${entry.trackNumber}",
        active = isCurrent,
        onClick = onClick,
        onLongClick = onLongPress,
        modifier = modifier,
    ) {
        if (isCurrent) RowStatus("Now playing", highlighted = true)
    }
}
