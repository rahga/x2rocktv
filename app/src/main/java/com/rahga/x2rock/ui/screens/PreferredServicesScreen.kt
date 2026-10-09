package com.rahga.x2rock.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.rahga.x2rock.ui.components.DotScanner
import com.rahga.x2rock.ui.components.ScreenHeader
import com.rahga.x2rock.ui.components.SectionTitle
import com.rahga.x2rock.ui.theme.requestFocusRetrying
import com.rahga.x2rock.viewmodel.PreferredServicesViewModel
import kotlinx.coroutines.launch

/**
 * Preferred services, from Settings: the household's services this device leads with, in order —
 * Search starts on the first of them and lists them first, and so does Music Services. The Sonos
 * app has one preferred service; this keeps an ordered tier, and every other service follows as
 * it always did.
 *
 * Reordering is the TV's usual pick-up-and-move: Select picks a preferred service up, Up and Down
 * carry it, Select (or Back) puts it down. Menu takes one out of the tier. Select on any other
 * service adds it at the foot.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PreferredServicesScreen(onBack: () -> Unit, viewModel: PreferredServicesViewModel = hiltViewModel()) {
    val services by viewModel.services.collectAsState()
    val order by viewModel.order.collectAsState()
    val moving by viewModel.moving.collectAsState()
    BackHandler { if (moving != null) viewModel.stopMoving() else onBack() }

    val backFocus = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }
    var focusId by remember { mutableStateOf<String?>(null) }
    val keyFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(start = 48.dp, top = 32.dp, end = 48.dp)) {
            ScreenHeader(title = "Preferred services", room = null, onBack = onBack, backModifier = Modifier.focusRequester(backFocus))
            Spacer(Modifier.height(20.dp))
            when (val s = services) {
                PreferredServicesViewModel.Services.Loading -> DotScanner()
                is PreferredServicesViewModel.Services.Failed -> {
                    Text(s.message, style = MaterialTheme.typography.bodyMedium)
                    LaunchedEffect(Unit) { backFocus.requestFocusRetrying() }
                }
                is PreferredServicesViewModel.Services.Ready -> {
                    val preferred = order.mapNotNull { id -> s.services.firstOrNull { it.id == id } }
                    // The household's own services before the anonymous radio, as Music Services lists them.
                    val others = s.services.filter { it.id !in order }.sortedByDescending { it.added }
                    // Focus starts on the first row there is, once.
                    LaunchedEffect(Unit) { firstFocus.requestFocusRetrying() }
                    val preferredIds = preferred.map { it.id }
                    val otherIds = others.map { it.id }
                    // A row preferred or taken out leaves its list from under the cursor, and the
                    // platform then put focus on Back. So focus goes first, to its neighbour in the
                    // list it is leaving — several can then be marked in a row — and only then does
                    // the row move; with that list emptied, the other list's first row takes it.
                    fun handOver(id: String, leaving: List<String>, beyond: List<String>, change: () -> Unit) {
                        val rest = leaving - id
                        val next = rest.getOrNull(leaving.indexOf(id)) ?: rest.lastOrNull() ?: (beyond - id).firstOrNull()
                        scope.launch {
                            if (next != null) {
                                focusId = next
                                withFrameNanos { } // for the requester to move onto that row
                                keyFocus.requestFocusRetrying()
                            }
                            change()
                        }
                    }
                    // The row being carried stays in view and keeps focus as it moves: moved past the
                    // screen's edge it left the composition, and focus went with it. Its place is
                    // two items in — the heading and the line under it — and at the top of the tier
                    // the list goes to its very top, so the heading shows too.
                    val listState = rememberLazyListState()
                    LaunchedEffect(order, moving) {
                        val id = moving ?: return@LaunchedEffect
                        val place = preferredIds.indexOf(id).takeIf { it >= 0 } ?: return@LaunchedEffect
                        val item = if (place == 0) 0 else place + 2
                        val visible = listState.layoutInfo.visibleItemsInfo
                        if (place == 0 || visible.none { it.index == place + 2 } || visible.first().index >= place + 2) listState.scrollToItem(item)
                        focusId = id
                        keyFocus.requestFocusRetrying()
                    }
                    fun tracked(id: String): Modifier = Modifier
                        .onFocusChanged { if (it.hasFocus) focusId = id }
                        .then(if (id == focusId) Modifier.focusRequester(keyFocus) else Modifier)
                    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item(key = "h:preferred") { SectionTitle("Preferred", first = true) }
                        item(key = "about") {
                            Text(
                                if (preferred.isEmpty()) "None yet. Choose a service below, and Search starts on it and lists it first."
                                else "Search starts on the first and lists these first, in this order. So does Music Services.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // One key per service in both lists, so a row keeps focus as it moves between them.
                        itemsIndexed(preferred, key = { _, it -> "svc:${it.id}" }) { index, service ->
                            val isMoving = moving == service.id
                            ServiceRow(
                                name = service.name,
                                detail = if (isMoving) "Moving: Up or Down, then Select to put it down"
                                else "${index + 1} · Select to move, Menu to remove",
                                onClick = { viewModel.toggleMoving(service.id) },
                                onMenu = { handOver(service.id, preferredIds, otherIds) { viewModel.remove(service.id) } },
                                opens = false,
                                modifier = tracked(service.id)
                                    .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                                    .onPreviewKeyEvent { event ->
                                        // While picked up, Up and Down carry it instead of moving focus.
                                        if (!isMoving || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                        when (event.key) {
                                            Key.DirectionUp -> { viewModel.move(-1); true }
                                            Key.DirectionDown -> { viewModel.move(1); true }
                                            else -> false
                                        }
                                    },
                            )
                        }
                        item(key = "h:others") { SectionTitle("Other services", first = false) }
                        itemsIndexed(others, key = { _, it -> "svc:${it.id}" }) { index, service ->
                            ServiceRow(
                                name = service.name,
                                detail = "Select to prefer",
                                onClick = { handOver(service.id, otherIds, preferredIds) { viewModel.prefer(service.id) } },
                                opens = false,
                                modifier = tracked(service.id)
                                    .then(if (preferred.isEmpty() && index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                            )
                        }
                    }
                }
            }
        }
    }
}
