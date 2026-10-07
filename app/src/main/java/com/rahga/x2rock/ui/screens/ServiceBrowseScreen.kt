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
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.rahga.x2rock.smapi.Category
import com.rahga.x2rock.smapi.Item
import com.rahga.x2rock.smapi.LinkedService
import com.rahga.x2rock.smapi.ServiceContent
import com.rahga.x2rock.ui.components.NoticeBanner
import com.rahga.x2rock.ui.components.dpadMenuKey
import com.rahga.x2rock.ui.theme.AppButton
import com.rahga.x2rock.ui.theme.AppCard
import com.rahga.x2rock.ui.theme.requestFocusSafely
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
    onBack: () -> Unit,
    onPlayed: () -> Unit,
    viewModel: ServiceBrowseViewModel = hiltViewModel(),
) {
    val services by viewModel.services.collectAsState()
    val active by viewModel.active.collectAsState()
    val notice by viewModel.notice.collectAsState()

    BackHandler { if (!viewModel.back()) onBack() }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(start = 48.dp, top = 40.dp, end = 48.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppButton(onClick = { if (!viewModel.back()) onBack() }) { Text("← Back") }
                    Spacer(Modifier.width(24.dp))
                    Text(active?.title() ?: "Music Services", style = MaterialTheme.typography.displaySmall)
                }
                Spacer(Modifier.height(24.dp))

                val service = active
                if (service == null) {
                    ServiceList(services, onOpen = viewModel::open)
                } else {
                    ServiceContent(viewModel, service, onPlayed)
                }
            }
            notice?.let { NoticeBanner(it, Modifier.align(Alignment.BottomCenter)) }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ServiceList(services: ServiceBrowseViewModel.Services, onOpen: (LinkedService) -> Unit) {
    when (services) {
        ServiceBrowseViewModel.Services.Loading ->
            Text("Finding this system's services…", style = MaterialTheme.typography.titleMedium)
        is ServiceBrowseViewModel.Services.Failed ->
            Text(services.message, style = MaterialTheme.typography.bodyMedium)
        is ServiceBrowseViewModel.Services.Ready ->
            if (services.services.isEmpty()) {
                Text(
                    "No searchable services. Add a music service in the Sonos app, and this system's " +
                        "own login unlocks searching it here.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(services.services, key = { it.key() }) { s ->
                        AppCard(onClick = { onOpen(s) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                                Text(s.service.name, style = MaterialTheme.typography.titleMedium)
                                if (s.nickname.isNotEmpty()) {
                                    Text(s.nickname, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
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
        repeat(5) {
            if (fieldFocus.requestFocusSafely()) return@LaunchedEffect
            kotlinx.coroutines.delay(50)
        }
    }

    if (categories.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ServiceSearchField(
                value = query,
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
                    repeat(5) {
                        if (resultsFocus.requestFocusSafely()) return@LaunchedEffect
                        kotlinx.coroutines.delay(50)
                    }
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

/** Plain text entry, outlined in the theme's colours — the same one Apple Music's search uses. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ServiceSearchField(value: String, onValueChange: (String) -> Unit, onSearch: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
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
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                    search(); true
                } else false
            },
        decorationBox = { inner ->
            Box(
                Modifier
                    .border(2.dp, if (focused) colors.primary else colors.border, RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (value.isEmpty()) {
                    Text("Search", style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
                }
                inner()
            }
        },
    )
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ItemRow(item: Item, isStarting: Boolean, onClick: () -> Unit, onQueue: () -> Unit, modifier: Modifier = Modifier) {
    // A press plays a track or opens a container; a hold or Menu adds it to the queue, as the
    // Apple Music rows do. The view model turns away a hold on something a queue cannot hold.
    AppCard(onClick = onClick, onLongClick = onQueue, modifier = modifier.fillMaxWidth().dpadMenuKey(onQueue)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Box(Modifier.size(56.dp)) {
                item.artUrl?.let { url ->
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
                item.summary?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            when {
                isStarting -> Text("Starting…", style = MaterialTheme.typography.bodySmall)
                // A place to open reads as such at the row's edge; an audiobook is resumed on
                // press rather than opened, so it carries no chevron.
                item.container && !ServiceContent.isResumable(item) ->
                    Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The service's name, with its account nickname when one tells two accounts apart. */
private fun LinkedService.title(): String =
    if (nickname.isEmpty()) service.name else "${service.name} · $nickname"

/** A stable key for a row: the service, plus the account so two accounts of one are distinct. */
private fun LinkedService.key(): String = "${service.id}:${accountId ?: "anon"}"
