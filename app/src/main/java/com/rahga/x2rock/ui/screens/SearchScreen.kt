package com.rahga.x2rock.ui.screens

import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import coil.compose.AsyncImage
import com.rahga.x2rock.ui.serviceLogo
import com.rahga.x2rock.ui.components.SectionTitle
import com.rahga.x2rock.ui.theme.requestFocusRetrying
import androidx.compose.runtime.getValue
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rahga.x2rock.ui.components.ServiceItemMenuOverlay
import com.rahga.x2rock.viewmodel.ServiceItemMenu
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.rahga.x2rock.smapi.Item
import com.rahga.x2rock.smapi.ServiceContent
import com.rahga.x2rock.ui.components.MediaRow
import com.rahga.x2rock.ui.components.NoticeBanner
import com.rahga.x2rock.ui.components.RowStatus
import com.rahga.x2rock.ui.components.ScreenHeader
import com.rahga.x2rock.ui.components.SearchField
import com.rahga.x2rock.ui.theme.AppButton
import com.rahga.x2rock.ui.theme.requestFocusSafely
import com.rahga.x2rock.viewmodel.SearchViewModel
import com.rahga.x2rock.viewmodel.key

/**
 * One search across the household's services: type or dictate once, and every service that can
 * search answers in its own section. See [SearchViewModel].
 *
 * Opens on the field, so the system keyboard and its microphone are up at once. When the first
 * answers arrive, focus moves to the first result — the field above otherwise traps a downward
 * press, as the service screen's did on the Streamer — and up from the first result returns to it.
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen(
    room: String,
    onBack: () -> Unit,
    onPlayed: () -> Unit,
    /** Open a service's container in its own browser: the service's key, and the container. */
    onOpen: (serviceKey: String, item: Item) -> Unit,
    /** Open one service's own search for [query]: every category, every page. */
    onSearchService: (serviceKey: String, query: String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val starting by viewModel.starting.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val fieldFocus = remember { FocusRequester() }
    val resultsFocus = remember { FocusRequester() }
    val menuTarget by viewModel.menu.open.collectAsState()
    val fromQueue by viewModel.menu.fromQueue.collectAsState()
    val details by viewModel.menu.details.collectAsState()
    // The row a menu was opened from, so focus goes back to it when the menu closes.
    var menuFrom by remember { mutableStateOf<String?>(null) }
    val menuReturn = remember { FocusRequester() }
    LaunchedEffect(menuTarget) {
        if (menuTarget == null && menuFrom != null) menuReturn.requestFocusRetrying()
    }
    BackHandler { onBack() }
    // The row that opened another screen — an album, a service's own search — so Back lands on it.
    // Saved, because this screen is rebuilt on the way back.
    var openedFrom by rememberSaveable { mutableStateOf<String?>(null) }
    val openedFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    // Coming back to results already there: bring the opener's service into view, and its grid
    // (below) brings the cell itself, which a grid only composes once scrolled to.
    var restoring by remember { mutableStateOf(openedFrom != null && results.sections.isNotEmpty()) }
    LaunchedEffect(Unit) {
        // Not on a return from an opened album, where the results are already there to go back to.
        if (results.sections.isEmpty()) {
            fieldFocus.requestFocusSafely()
            return@LaunchedEffect
        }
        val section = results.sections.indexOfFirst { cellKeys(it).contains(openedFrom) }
        if (section >= 0) listState.scrollToItem(section) else restoring = false
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(start = 48.dp, top = 32.dp, end = 48.dp)) {
                ScreenHeader(title = "Search", room = room, onBack = onBack)
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SearchField(
                        value = query,
                        placeholder = "Songs, albums, artists, stations",
                        onValueChange = viewModel::setQuery,
                        onSearch = viewModel::search,
                        modifier = Modifier.weight(1f).focusRequester(fieldFocus),
                    )
                    Spacer(Modifier.width(16.dp))
                    AppButton(onClick = viewModel::search) { Text("Search") }
                }
                Spacer(Modifier.height(16.dp))
                Status(results)
                Spacer(Modifier.height(8.dp))

                val firstKey = results.sections.firstOrNull()?.hits?.firstOrNull()?.key
                if (firstKey != null) {
                    // Focus goes to the top section once, when it is settled: at once with no
                    // preferred services, else when none that would rank above it is still out —
                    // so it starts on the top preferred service, not on whichever answered first.
                    // Later sections slotting in above it do not pull it about while the viewer
                    // is reading.
                    LaunchedEffect(results.searched, results.leadSettled) {
                        if (results.leadSettled && openedFrom == null) resultsFocus.requestFocusRetrying()
                    }
                }
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // One block per service, as the Sonos app lays its search out: its name, then
                    // its hits three to a column, sideways, "More from" it in the last cell — wider
                    // than the screen, so the next column showing at the edge says there is more.
                    itemsIndexed(results.sections, key = { _, section -> "section:${section.key}" }) { sectionIndex, section ->
                        val cells = cellKeys(section)
                        val gridState = rememberLazyGridState()
                        LaunchedEffect(restoring) {
                            val index = cells.indexOf(openedFrom)
                            if (!restoring || index < 0) return@LaunchedEffect
                            gridState.scrollToItem(index)
                            openedFocus.requestFocusRetrying()
                            restoring = false
                        }
                        Column {
                            ServiceHeading(section.name, first = sectionIndex == 0)
                            val rows = cells.size.coerceAtMost(GRID_ROWS)
                            LazyHorizontalGrid(
                                rows = GridCells.Fixed(rows),
                                state = gridState,
                                // Right off a block's last column stays in it: it used to find the
                                // Search button, the nearest thing that way. Properties before the
                                // group, as for a modal's focus trap.
                                modifier = Modifier.height(CELL_HEIGHT * rows + CELL_GAP * (rows - 1) + 8.dp)
                                    .focusProperties { exit = { if (it == FocusDirection.Right) FocusRequester.Cancel else FocusRequester.Default } }
                                    .focusGroup(),
                                // A little room at the sides for the focused cell's outline, which the edge cut off.
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalArrangement = Arrangement.spacedBy(CELL_GAP),
                            ) {
                                items(section.hits, key = { "${section.key}:${it.key}" }) { hit ->
                                    val key = "${section.key}:${hit.key}"
                                    HitRow(
                                        hit = hit,
                                        isStarting = starting == hit.key,
                                        onClick = {
                                            viewModel.select(hit, onPlayed) { linked, item ->
                                                openedFrom = key
                                                onOpen(linked.key(), item)
                                            }
                                        },
                                        onMenu = { menuFrom = hit.key; viewModel.openMenu(hit) },
                                        modifier = Modifier.width(CELL_WIDTH)
                                            .then(if (hit.key == firstKey) Modifier.focusRequester(resultsFocus) else Modifier)
                                            .then(if (hit.key == menuFrom) Modifier.focusRequester(menuReturn) else Modifier)
                                            .then(if (key == openedFrom) Modifier.focusRequester(openedFocus) else Modifier),
                                    )
                                }
                                // Two categories and eight hits a service is what keeps one search
                                // across every service affordable; the rest is its own search away.
                                if (section.key != APPLE_SECTION) item(key = "more:${section.key}") {
                                    val more = { openedFrom = "more:${section.key}"; onSearchService(section.key, query.trim()) }
                                    MediaRow(
                                        title = "More from ${section.name}",
                                        subtitle = "Every kind, every page",
                                        artUrl = serviceLogo(section.name),
                                        onClick = more,
                                        // Right goes on into it, as its chevron says: past the last
                                        // column, "more" is the only way there is.
                                        modifier = Modifier.width(CELL_WIDTH)
                                            .onKeyEvent { event ->
                                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) { more(); true } else false
                                            }
                                            .then(if ("more:${section.key}" == openedFrom) Modifier.focusRequester(openedFocus) else Modifier),
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(28.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            menuTarget?.let { target ->
                val service = target as? ServiceItemMenu.Target.Service
                ServiceItemMenuOverlay(
                    menu = viewModel.menu, target = target, room = room, fromQueue = fromQueue, details = details,
                    here = null,
                    onOpen = service?.let { { onOpen(it.linked.key(), it.item) } },
                    // An album says so, so the page it opens on has Play and Shuffle at its head.
                    onOpenRelated = { id, title, album ->
                        val kind = if (album) "album" else "container"
                        service?.let { onOpen(it.linked.key(), Item(id, title, kind, null, null, container = true)) }
                    },
                    onPlayed = onPlayed,
                )
            }
            notice?.let { NoticeBanner(it, Modifier.align(Alignment.BottomCenter)) }
        }
    }
}

/** A service block's cells in order — its hits, then "More" — as its grid keys them. */
private fun cellKeys(section: SearchViewModel.Section): List<String> =
    section.hits.map { "${section.key}:${it.key}" } + listOfNotNull("more:${section.key}".takeIf { section.key != APPLE_SECTION })

/** A service's name over its block, with its logo beside it as the Sonos app has. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ServiceHeading(name: String, first: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = if (first) 0.dp else 16.dp, bottom = 8.dp),
    ) {
        serviceLogo(name)?.let { logo ->
            AsyncImage(
                model = logo, contentDescription = null,
                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.width(12.dp))
        }
        Text(name, style = MaterialTheme.typography.titleLarge)
    }
}

/** Three cells to a column, as the Sonos app's search has them. */
private const val GRID_ROWS = 3

/** A cell is a list row: the 56dp art slot and its padding. */
private val CELL_HEIGHT = 76.dp
private val CELL_GAP = 8.dp

/**
 * About two and a half cells across the 864dp a television leaves this list: the third column
 * showing cut off at the edge is what says the block goes on sideways.
 */
private val CELL_WIDTH = 340.dp

/** The section key Apple Music's hits are listed under; it has no SMAPI search to send "More" to. */
private const val APPLE_SECTION = "apple"

/**
 * How the search stands. Before one, what Search does. Then two ends of one line: on the left a
 * running total of what has been found, counting up as each service answers; on the right what
 * is still out, and once nothing is, who did not answer.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Status(results: SearchViewModel.Results) {
    val style = MaterialTheme.typography.bodyLarge
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    if (!results.searched) {
        Text(
            "Searches every music service this system has, and Apple Music, at once. " +
                "Press the microphone on the keyboard to say it instead.",
            style = style, color = color,
        )
        return
    }
    val found = results.sections.sumOf { it.hits.size }
    val services = results.sections.size
    val total = when {
        found == 0 -> if (results.pending > 0) "Nothing yet" else "Nothing found"
        else -> "$found ${if (found == 1) "result" else "results"} from $services " +
            if (services == 1) "service" else "services"
    }
    val outstanding = when {
        results.pending > 0 -> "Searching… ${results.pending} still to answer"
        results.silent == 1 -> "1 service didn't answer"
        results.silent > 1 -> "${results.silent} services didn't answer"
        else -> null
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(total, style = style, color = color, modifier = Modifier.weight(1f))
        outstanding?.let { Text(it, style = style, color = color) }
    }
}

@Composable
private fun HitRow(
    hit: SearchViewModel.Hit,
    isStarting: Boolean,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier,
) {
    MediaRow(
        title = hit.title,
        subtitle = hit.subtitle,
        artUrl = hit.artUrl,
        onClick = onClick,
        // Menu or a hold: the item's menu — play next, the queue, the service's own actions.
        onLongClick = onMenu,
        modifier = modifier,
    ) {
        // No chevron here: in a block of cells running sideways, one reads as "this way", and the
        // only thing that way is the block's own "More from". An album still opens on a press.
        if (isStarting) RowStatus("Starting…")
    }
}
