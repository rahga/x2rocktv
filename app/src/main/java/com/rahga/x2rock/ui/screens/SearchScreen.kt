package com.rahga.x2rock.ui.screens

import com.rahga.x2rock.ui.components.SectionTitle
import com.rahga.x2rock.ui.theme.requestFocusRetrying
import androidx.compose.runtime.getValue
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
@OptIn(ExperimentalTvMaterial3Api::class)
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
    LaunchedEffect(Unit) {
        // Not on a return from an opened album, where the results are already there to go back to.
        if (results.sections.isEmpty()) {
            fieldFocus.requestFocusSafely()
            return@LaunchedEffect
        }
        val index = rowKeys(results).indexOf(openedFrom)
        if (index >= 0) {
            listState.scrollToItem(index)
            openedFocus.requestFocusRetrying()
        }
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
                    // The first answer to arrive takes focus once; later sections slotting in
                    // above it do not pull it about while the viewer is reading.
                    LaunchedEffect(results.searched) {
                        if (openedFrom == null) resultsFocus.requestFocusRetrying()
                    }
                }
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    results.sections.forEachIndexed { sectionIndex, section ->
                        item(key = "section:${section.key}") {
                            SectionTitle(section.name, first = sectionIndex == 0)
                        }
                        items(section.hits, key = { "${section.key}:${it.key}" }) { hit ->
                            HitRow(
                                hit = hit,
                                isStarting = starting == hit.key,
                                onClick = {
                                    viewModel.select(hit, onPlayed) { linked, item ->
                                        openedFrom = "${section.key}:${hit.key}"
                                        onOpen(linked.key(), item)
                                    }
                                },
                                onMenu = { menuFrom = hit.key; viewModel.openMenu(hit) },
                                modifier = Modifier
                                    .then(if (hit.key == firstKey) Modifier.focusRequester(resultsFocus) else Modifier)
                                    .then(if (hit.key == menuFrom) Modifier.focusRequester(menuReturn) else Modifier)
                                    .then(if ("${section.key}:${hit.key}" == openedFrom) Modifier.focusRequester(openedFocus) else Modifier),
                            )
                        }
                        // Two categories and eight hits a service is what keeps one search across
                        // every service affordable; the rest is that service's own search away.
                        if (section.key != APPLE_SECTION) item(key = "more:${section.key}") {
                            MediaRow(
                                title = "More from ${section.name}",
                                subtitle = "Every kind of result, and every page",
                                artUrl = null,
                                onClick = { openedFrom = "more:${section.key}"; onSearchService(section.key, query.trim()) },
                                modifier = if ("more:${section.key}" == openedFrom) Modifier.focusRequester(openedFocus) else Modifier,
                            ) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(28.dp))
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
                    onOpenRelated = { id, title, _ ->
                        service?.let { onOpen(it.linked.key(), Item(id, title, "container", null, null, container = true)) }
                    },
                    onPlayed = onPlayed,
                )
            }
            notice?.let { NoticeBanner(it, Modifier.align(Alignment.BottomCenter)) }
        }
    }
}

/** Every row's key in list order — headings, hits and "More" rows — as the list keys them. */
private fun rowKeys(results: SearchViewModel.Results): List<String> = results.sections.flatMap { section ->
    listOf("section:${section.key}") + section.hits.map { "${section.key}:${it.key}" } +
        listOfNotNull("more:${section.key}".takeIf { section.key != APPLE_SECTION })
}

/** The section key Apple Music's hits are listed under; it has no SMAPI search to send "More" to. */
private const val APPLE_SECTION = "apple"

/** How the search stands: what is still out, what found nothing, and who did not answer. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Status(results: SearchViewModel.Results) {
    val text = when {
        !results.searched -> "Searches every music service this system has, and Apple Music, at once. " +
            "Press the microphone on the keyboard to say it instead."
        results.pending > 0 -> "Searching… ${results.pending} still to answer"
        results.sections.isEmpty() -> "Nothing found."
        else -> null
    }
    val silent = results.silent.takeIf { it > 0 && results.pending == 0 }
        ?.let { if (it == 1) "1 service didn't answer." else "$it services didn't answer." }
    listOfNotNull(text, silent).joinToString(" ").takeIf { it.isNotEmpty() }?.let {
        Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val opens = hit is SearchViewModel.Hit.Service && ServiceContent.opens(hit.item)
    MediaRow(
        title = hit.title,
        subtitle = hit.subtitle,
        artUrl = hit.artUrl,
        onClick = onClick,
        // Menu or a hold: the item's menu — play next, the queue, the service's own actions.
        onLongClick = onMenu,
        modifier = modifier,
    ) {
        when {
            isStarting -> RowStatus("Starting…")
            opens -> Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(28.dp))
        }
    }
}
