package com.rahga.x2rock.ui.screens

import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import com.rahga.x2rock.ui.components.DotScanner
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
import com.rahga.x2rock.ui.components.ServiceItemMenuOverlay
import com.rahga.x2rock.ui.components.ItemMenu
import com.rahga.x2rock.ui.components.MenuAction
import com.rahga.x2rock.ui.components.MenuSection
import com.rahga.x2rock.ui.components.dpadMenuKey
import com.rahga.x2rock.store.isPrimary
import com.rahga.x2rock.store.withPrimariesFirst
import com.rahga.x2rock.viewmodel.ServiceItemMenu
import com.rahga.x2rock.ui.components.NoticeBanner
import com.rahga.x2rock.ui.components.ArtSlot
import com.rahga.x2rock.ui.serviceLogo
import com.rahga.x2rock.ui.components.SearchField
import com.rahga.x2rock.ui.components.MediaRow
import com.rahga.x2rock.ui.components.RowStatus
import com.rahga.x2rock.ui.components.ScreenHeader
import com.rahga.x2rock.viewmodel.key
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import com.rahga.x2rock.ui.theme.IconLabelButton
import com.rahga.x2rock.viewmodel.WHOLE
import androidx.tv.material3.Icon
import com.rahga.x2rock.ui.theme.AppButton
import com.rahga.x2rock.ui.theme.AppCard
import com.rahga.x2rock.viewmodel.ServiceBrowseViewModel

/**
 * The household's own music services, searched and browsed with its stored tokens and played in
 * the room through its own account. Pick a service and it opens on its own library, with its search
 * a button above it. A press on a track plays it in place of the queue; a press on a container
 * opens it; Back climbs out one level at a time, then back to the service list.
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

    val primaries by viewModel.primaries.collectAsState()
    val serviceMenu by viewModel.serviceMenu.collectAsState()
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
                        primaries = primaries,
                        returnTo = opened,
                        onOpen = { opened = Entry.Service(it).key; viewModel.open(it) },
                        onMenu = { opened = Entry.Service(it).key; viewModel.openServiceMenu(it) },
                        menuOpen = serviceMenu != null,
                        onOpenAppleMusic = { opened = Entry.Apple.key; onOpenAppleMusic() },
                    )
                } else {
                    ServiceContent(viewModel, service, room, onPlayed)
                }
            }
            // Over the whole screen, not inside the column: there the list takes every pixel of
            // height, and a menu drawn below it had none — invisible, yet holding focus.
            serviceMenu?.let { linked ->
                val primary = isPrimary(linked, primaries)
                ItemMenu(
                    title = linked.service.name,
                    subtitle = linked.nickname.ifEmpty { null },
                    artUrl = serviceLogo(linked.service.name),
                    sections = listOf(MenuSection(null, if (primary) emptyList() else listOf(
                        MenuAction("Make primary", "Listed first, and the one Search asks") { viewModel.makePrimary(linked) },
                    ))),
                    onDismiss = viewModel::closeServiceMenu,
                    note = if (primary) "This is ${linked.service.name}'s primary account here." else null,
                )
            }
            notice?.let { NoticeBanner(it, Modifier.align(Alignment.BottomCenter)) }
        }
    }
}

/**
 * The household's services in two lists: the ones it **added** — its own Deezer, TIDAL, Audible,
 * Apple Music, and any radio service it chose — and then the anonymous radio services every Sonos
 * system carries, which run to a hundred and bury the few that matter if listed together by name.
 * Focus starts on the first row, so Select opens a service rather than pressing Back — or, coming
 * back out of a service, on [returnTo], the row that opened it.
 *
 * A service with more than one account lists each, its [primaries] entry first and marked; a hold
 * or Menu on one offers to make it primary.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ServiceList(
    services: ServiceBrowseViewModel.Services,
    primaries: Map<String, String>,
    returnTo: String?,
    onOpen: (LinkedService) -> Unit,
    onMenu: (LinkedService) -> Unit,
    /** Whether a row's menu is over the list; focus goes back to that row when it closes. */
    menuOpen: Boolean,
    onOpenAppleMusic: () -> Unit,
) {
    when (services) {
        ServiceBrowseViewModel.Services.Loading ->
            Text("Finding this system's services…", style = MaterialTheme.typography.titleMedium)
        is ServiceBrowseViewModel.Services.Failed ->
            Text(services.message, style = MaterialTheme.typography.bodyMedium)
        is ServiceBrowseViewModel.Services.Ready -> {
            val (yours, others) = withPrimariesFirst(services.services, primaries).partition { it.added }
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
            LaunchedEffect(menuOpen) {
                if (menuOpen) return@LaunchedEffect
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
                            entry.linked.nicknameIfAmbiguous(services.services)
                                ?.let { if (isPrimary(entry.linked, primaries)) "$it · Primary" else it },
                            { onOpen(entry.linked) },
                            modifier,
                            onMenu = { onMenu(entry.linked) },
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
private fun ServiceRow(name: String, detail: String?, onClick: () -> Unit, modifier: Modifier, onMenu: (() -> Unit)? = null) {
    AppCard(
        onClick = onClick,
        onLongClick = onMenu,
        modifier = modifier.fillMaxWidth().then(if (onMenu != null) Modifier.dpadMenuKey(onMenu) else Modifier),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            // Sonos's own logo for it, in the slot every list row keeps; see serviceLogo.
            ArtSlot(serviceLogo(name))
            Spacer(Modifier.width(16.dp))
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
private fun ServiceContent(viewModel: ServiceBrowseViewModel, service: LinkedService, room: String, onPlayed: () -> Unit) {
    val categories by viewModel.categories.collectAsState()
    val category by viewModel.category.collectAsState()
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val searching by viewModel.searching.collectAsState()
    val place by viewModel.place.collectAsState()
    val playsWhole by viewModel.playsWhole.collectAsState()
    val starting by viewModel.starting.collectAsState()
    val returnTo by viewModel.returnTo.collectAsState()
    val fieldFocus = remember { FocusRequester() }
    val resultsFocus = remember { FocusRequester() }
    // The field takes focus, and with it the keyboard, only on stepping into the search — never on
    // opening the service, which used to cover half the screen with a keyboard before anything was
    // asked for. Retried, because the field is composed in the same frame the flag turns.
    LaunchedEffect(service.key(), searching) {
        if (searching) fieldFocus.requestFocusRetrying()
    }

    if (searching) {
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
    } else {
        // Above the library, one press up from its first row: searching is there when wanted, and
        // the library is what a remote meets first.
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (categories.isNotEmpty()) {
                AppButton(onClick = viewModel::openSearch) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Search ${service.service.name}")
                }
                Spacer(Modifier.width(24.dp))
            }
            // An album or playlist is played whole from its head, as the Sonos app's page does.
            if (playsWhole) {
                IconLabelButton(Icons.Default.PlayArrow, "Play", { viewModel.playWhole(shuffle = false, onPlayed) })
                Spacer(Modifier.width(16.dp))
                IconLabelButton(Icons.Default.Shuffle, "Shuffle", { viewModel.playWhole(shuffle = true, onPlayed) })
                Spacer(Modifier.width(24.dp))
            }
            place?.let { Text(it, style = MaterialTheme.typography.titleMedium, maxLines = 1) }
            if (starting == WHOLE) RowStatus("Starting…")
        }
        if (categories.isNotEmpty() || place != null || playsWhole) Spacer(Modifier.height(16.dp))
    }

    val menuTarget by viewModel.menu.open.collectAsState()
    val menuItem = (menuTarget as? ServiceItemMenu.Target.Service)?.item
    // The row a menu was opened from, so focus goes back to it when the menu closes. Without this
    // it fell to Back, and the next presses walked the list and played whatever they landed on.
    var menuFrom by remember { mutableStateOf<String?>(null) }
    val menuReturn = remember { FocusRequester() }
    LaunchedEffect(menuItem) {
        if (menuItem != null) menuFrom = menuItem?.id
        else if (menuFrom != null) menuReturn.requestFocusRetrying()
    }
    val fromQueue by viewModel.menu.fromQueue.collectAsState()
    val details by viewModel.menu.details.collectAsState()
    val here by viewModel.here.collectAsState()
    (menuTarget as? ServiceItemMenu.Target.Service)?.let { target ->
        ServiceItemMenuOverlay(
            menu = viewModel.menu, target = target, room = room, fromQueue = fromQueue, details = details,
            here = here, onOpen = { viewModel.select(target.item, onPlayed) }, onOpenRelated = viewModel::openRelated,
            onPlayed = onPlayed,
        )
    }

    when (val r = results) {
        ServiceBrowseViewModel.Results.Idle -> Text(
            if (searching) "Search ${service.service.name}. What you pick plays through this system's own account."
            else "Opening ${service.service.name}…",
            style = MaterialTheme.typography.bodyMedium,
        )
        ServiceBrowseViewModel.Results.Loading -> DotScanner()
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
                // Keyed on the first row, so a further page arriving below leaves focus where it is.
                // Back onto a level lands on the row that was opened from it, not the first.
                val target = r.items.indexOfFirst { it.id == returnTo }.coerceAtLeast(0)
                val listState = rememberLazyListState()
                LaunchedEffect(r.items.first().id, target) {
                    // A row far down is not composed until scrolled to, and cannot take focus before.
                    // To the first row too: one list serves every level, and keeps the last's scroll.
                    listState.scrollToItem(target)
                    // The row may not be laid out the instant results arrive; retry a few frames.
                    resultsFocus.requestFocusRetrying()
                }
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(r.items, key = { _, item -> item.id }) { index, item ->
                        // Ten rows from the end is about a screen's worth: the next page is asked for
                        // while there is still something to read, not once the list has run out.
                        if (r.hasMore && index >= r.items.size - 10) {
                            LaunchedEffect(r.items.size) { viewModel.loadMore() }
                        }
                        ItemRow(
                            item = item,
                            isStarting = starting == item.id,
                            onClick = { viewModel.select(item, onPlayed) },
                            onMenu = { viewModel.openMenu(item) },
                            modifier = Modifier
                                .then(if (index == target) Modifier.focusRequester(resultsFocus) else Modifier)
                                .then(if (item.id == menuFrom) Modifier.focusRequester(menuReturn) else Modifier),
                        )
                    }
                    if (r.hasMore) {
                        item(key = "more") {
                            Text(
                                "Loading more of ${r.total}…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
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
private fun ItemRow(item: Item, isStarting: Boolean, onClick: () -> Unit, onMenu: () -> Unit, modifier: Modifier = Modifier) {
    // A press plays a track or opens a container; a hold or Menu opens everything else that can
    // be done with it — play next, add to the queue, the service's own actions.
    MediaRow(
        title = item.title,
        subtitle = item.summary,
        artUrl = item.artUrl,
        onClick = onClick,
        onLongClick = onMenu,
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
