package com.rahga.x2rock.ui.screens

import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import com.rahga.x2rock.ui.components.SectionTitle
import com.rahga.x2rock.ui.theme.requestFocusRetrying
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.rahga.x2rock.smapi.Item
import com.rahga.x2rock.smapi.LinkedService
import com.rahga.x2rock.smapi.ServiceContent
import com.rahga.x2rock.ui.components.NoticeBanner
import com.rahga.x2rock.ui.components.SearchField
import com.rahga.x2rock.ui.components.MediaRow
import com.rahga.x2rock.ui.components.RowStatus
import com.rahga.x2rock.ui.components.ScreenHeader
import com.rahga.x2rock.viewmodel.key
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.tv.material3.Icon
import com.rahga.x2rock.ui.theme.AppButton
import com.rahga.x2rock.ui.theme.AppCard
import com.rahga.x2rock.viewmodel.ServiceBrowseViewModel

/**
 * The household's own music services, searched and browsed with its stored tokens and played in
 * the room through its own account. Two steps: pick a service, then search it (if it publishes
 * search categories) or browse it. A press on a track plays it in place of the queue; a press on
 * a container opens it; Back climbs out one container at a time, then back to the service list.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ServiceBrowseScreen(
    room: String,
    onOpenAppleMusic: () -> Unit,
    onBack: () -> Unit,
    onPlayed: () -> Unit,
    viewModel: ServiceBrowseViewModel = hiltViewModel(),
) {
    val services by viewModel.services.collectAsState()
    val active by viewModel.active.collectAsState()
    val notice by viewModel.notice.collectAsState()

    BackHandler { if (!viewModel.back()) onBack() }
    // The row a service was opened from, so Back lands on it rather than on the first row. Saved,
    // because Apple Music is a screen of its own and this one is rebuilt on the way back.
    var opened by rememberSaveable { mutableStateOf<String?>(null) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(start = 48.dp, top = 32.dp, end = 48.dp)) {
                val ready = services as? ServiceBrowseViewModel.Services.Ready
                ScreenHeader(
                    title = active?.title(ready?.services.orEmpty()) ?: "Music Services",
                    room = room,
                    onBack = { if (!viewModel.back()) onBack() },
                )
                Spacer(Modifier.height(20.dp))

                val service = active
                if (service == null) {
                    ServiceList(
                        services,
                        returnTo = opened,
                        onOpen = { opened = Entry.Service(it).key; viewModel.open(it) },
                        onOpenAppleMusic = { opened = Entry.Apple.key; onOpenAppleMusic() },
                    )
                } else {
                    ServiceContent(viewModel, service, onPlayed)
                }
            }
            notice?.let { NoticeBanner(it, Modifier.align(Alignment.BottomCenter)) }
        }
    }
}

/**
 * The household's services in two lists: the ones it **signed in to** — its own Deezer, TIDAL,
 * Audible, and Apple Music — and then the anonymous radio services every Sonos system carries,
 * which run to a hundred and bury the few that matter if listed together by name. Focus starts on
 * the first row, so Select opens a service rather than pressing Back — or, coming back out of a
 * service, on [returnTo], the row that opened it.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ServiceList(
    services: ServiceBrowseViewModel.Services,
    returnTo: String?,
    onOpen: (LinkedService) -> Unit,
    onOpenAppleMusic: () -> Unit,
) {
    when (services) {
        ServiceBrowseViewModel.Services.Loading ->
            Text("Finding this system's services…", style = MaterialTheme.typography.titleMedium)
        is ServiceBrowseViewModel.Services.Failed ->
            Text(services.message, style = MaterialTheme.typography.bodyMedium)
        is ServiceBrowseViewModel.Services.Ready -> {
            val (yours, others) = services.services.partition { it.token != null }
            if (yours.isEmpty() && others.isEmpty() && !services.appleMusic) {
                Text(
                    "No searchable services. Add a music service in the Sonos app, and this system's " +
                        "own login unlocks searching it here.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                return
            }
            // A row is one entry in a flat list of rows and headings, so the first one is easy to name.
            val entries = buildList {
                if (yours.isNotEmpty() || services.appleMusic) add(Entry.Heading("Your services"))
                if (services.appleMusic) add(Entry.Apple)
                yours.forEach { add(Entry.Service(it)) }
                if (others.isNotEmpty()) add(Entry.Heading("More radio"))
                others.forEach { add(Entry.Service(it)) }
            }
            val first = entries.indexOfFirst { it !is Entry.Heading }
            val target = entries.indexOfFirst { it.key == returnTo }.takeIf { it >= 0 } ?: first
            val targetFocus = remember { FocusRequester() }
            val listState = rememberLazyListState()
            LaunchedEffect(Unit) {
                // A row far down "More radio" is not composed until scrolled to, and an uncomposed
                // row cannot take focus.
                if (target != first) listState.scrollToItem(target)
                targetFocus.requestFocusRetrying()
            }
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(entries, key = { _, e -> e.key }) { index, entry ->
                    val modifier = if (index == target) Modifier.focusRequester(targetFocus) else Modifier
                    when (entry) {
                        is Entry.Heading -> SectionTitle(entry.title, first = index == 0)
                        Entry.Apple -> ServiceRow("Apple Music", "Searched through Apple", onOpenAppleMusic, modifier)
                        is Entry.Service -> ServiceRow(
                            entry.linked.service.name,
                            entry.linked.nicknameIfAmbiguous(services.services),
                            { onOpen(entry.linked) },
                            modifier,
                        )
                    }
                }
            }
        }
    }
}

private sealed interface Entry {
    val key: String
    data class Heading(val title: String) : Entry { override val key get() = "h:$title" }
    data object Apple : Entry { override val key get() = "apple" }
    data class Service(val linked: LinkedService) : Entry { override val key get() = "s:${linked.key()}" }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ServiceRow(name: String, detail: String?, onClick: () -> Unit, modifier: Modifier) {
    AppCard(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                if (detail != null) {
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(28.dp))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ServiceContent(viewModel: ServiceBrowseViewModel, service: LinkedService, onPlayed: () -> Unit) {
    val categories by viewModel.categories.collectAsState()
    val category by viewModel.category.collectAsState()
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val starting by viewModel.starting.collectAsState()
    val fieldFocus = remember { FocusRequester() }
    val resultsFocus = remember { FocusRequester() }
    // Focus the search field once a searchable service's categories have loaded — keyed on the
    // categories, not the service, because they arrive a moment after the screen opens (an async
    // fetch); keying on the service alone fired this while categories were still empty, so the
    // field never took focus and a press up from the chips reached Back instead. Retried for layout.
    val hasCategories = categories.isNotEmpty()
    LaunchedEffect(service.key(), hasCategories) {
        if (!hasCategories) return@LaunchedEffect
        fieldFocus.requestFocusRetrying()
    }

    if (categories.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SearchField(
                value = query,
                placeholder = "Search ${service.service.name}",
                onValueChange = viewModel::setQuery,
                onSearch = viewModel::search,
                modifier = Modifier.weight(1f).focusRequester(fieldFocus),
            )
            Spacer(Modifier.width(16.dp))
            AppButton(onClick = viewModel::search) { Text("Search") }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            categories.forEach { c ->
                CategoryChip(c.id, c == category) { viewModel.setCategory(c) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    when (val r = results) {
        ServiceBrowseViewModel.Results.Idle -> Text(
            if (categories.isEmpty()) "Opening ${service.service.name}…"
            else "Search ${service.service.name}. What you pick plays through this system's own account.",
            style = MaterialTheme.typography.bodyMedium,
        )
        ServiceBrowseViewModel.Results.Loading ->
            Text("Loading…", style = MaterialTheme.typography.titleMedium)
        is ServiceBrowseViewModel.Results.Failed ->
            Text(r.message, style = MaterialTheme.typography.bodyMedium)
        is ServiceBrowseViewModel.Results.Found ->
            if (r.items.isEmpty()) {
                Text("Nothing here.", style = MaterialTheme.typography.titleMedium)
            } else {
                // The remote cannot reach the results otherwise: the search field above traps a
                // downward focus search, so focus is moved into the first row when results arrive
                // (on a fresh search, or after opening a container). Verified on the Streamer — the
                // rows were unreachable by D-pad until this. UP from the first row returns to search.
                LaunchedEffect(r.items.first().id) {
                    // The row may not be laid out the instant results arrive; retry a few frames.
                    resultsFocus.requestFocusRetrying()
                }
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(r.items, key = { _, item -> item.id }) { index, item ->
                        ItemRow(
                            item = item,
                            isStarting = starting == item.id,
                            onClick = { viewModel.select(item, onPlayed) },
                            onQueue = { viewModel.queue(item) },
                            modifier = if (index == 0) Modifier.focusRequester(resultsFocus) else Modifier,
                        )
                    }
                }
            }
    }
}

/** A search category, the chosen one outlined, as the radio and Apple Music screens mark theirs. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    AppButton(
        onClick = onClick,
        modifier = if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(50)) else Modifier,
    ) { Text(label.replaceFirstChar { it.uppercase() }) }
}

@Composable
private fun ItemRow(item: Item, isStarting: Boolean, onClick: () -> Unit, onQueue: () -> Unit, modifier: Modifier = Modifier) {
    // A press plays a track or opens a container; a hold or Menu adds it to the queue, as the
    // Apple Music rows do. The view model turns away a hold on something a queue cannot hold.
    MediaRow(
        title = item.title,
        subtitle = item.summary,
        artUrl = item.artUrl,
        onClick = onClick,
        onLongClick = onQueue,
        modifier = modifier,
    ) {
        when {
            isStarting -> RowStatus("Starting…")
            // A place to open reads as such at the row's edge; an audiobook is resumed on
            // press rather than opened, so it carries no chevron.
            ServiceContent.opens(item) ->
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(28.dp))
        }
    }
}

/** The service's name, with its account nickname when one tells two accounts of it apart. */
private fun LinkedService.title(all: List<LinkedService>): String =
    listOfNotNull(service.name, nicknameIfAmbiguous(all)).joinToString(" · ")

/** This account's nickname, only where another account of the same service makes it needed. */
private fun LinkedService.nicknameIfAmbiguous(all: List<LinkedService>): String? =
    nickname.takeIf { it.isNotEmpty() && all.count { other -> other.service.id == service.id } > 1 }
