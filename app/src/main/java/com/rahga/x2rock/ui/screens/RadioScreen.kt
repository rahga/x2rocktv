package com.rahga.x2rock.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import com.rahga.x2rock.radio.Station
import com.rahga.x2rock.ui.components.NoticeBanner
import com.rahga.x2rock.ui.components.tapToClick
import com.rahga.x2rock.ui.theme.AppButton
import com.rahga.x2rock.ui.theme.requestFocusSafely
import com.rahga.x2rock.viewmodel.RadioViewModel

/**
 * The radio directory: categories down the left, their stations on the right. A category is
 * chosen with a press rather than on focus, so moving down the list does not send a request
 * to a third party for every row passed.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RadioScreen(
    onBack: () -> Unit,
    onPlayed: () -> Unit,
    viewModel: RadioViewModel = hiltViewModel(),
) {
    val selected by viewModel.selected.collectAsState()
    val stations by viewModel.stations.collectAsState()
    val starting by viewModel.starting.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val firstFocus = remember { FocusRequester() }
    BackHandler { onBack() }
    LaunchedEffect(Unit) { firstFocus.requestFocusSafely() }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(start = 48.dp, top = 40.dp, end = 48.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppButton(onClick = onBack) { Text("← Back") }
                    Spacer(Modifier.width(24.dp))
                    Text("Radio", style = MaterialTheme.typography.displaySmall)
                }
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier.width(240.dp).fillMaxHeight(),
                        contentPadding = PaddingValues(bottom = 48.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(viewModel.categories) { index, category ->
                            val isSelected = category == selected
                            AppButton(
                                onClick = { viewModel.select(category) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                                    .then(
                                        if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                                        else Modifier
                                    ),
                            ) { Text(category.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                    Spacer(Modifier.width(32.dp))
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        when (val s = stations) {
                            is RadioViewModel.Stations.Loading ->
                                Text("Loading stations…", style = MaterialTheme.typography.titleMedium)
                            is RadioViewModel.Stations.Failed -> Column {
                                Text(s.message, style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(16.dp))
                                AppButton(onClick = { viewModel.retry() }) { Text("Retry") }
                            }
                            is RadioViewModel.Stations.Loaded ->
                                if (s.stations.isEmpty()) {
                                    Text("No stations here.", style = MaterialTheme.typography.titleMedium)
                                } else {
                                    LazyColumn(
                                        contentPadding = PaddingValues(bottom = 48.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        items(s.stations, key = { it.url }) { station ->
                                            StationRow(
                                                station = station,
                                                isStarting = starting == station.url,
                                                onClick = { viewModel.play(station, onDone = onPlayed) },
                                            )
                                        }
                                    }
                                }
                        }
                    }
                }
            }
            notice?.let { NoticeBanner(it, Modifier.align(Alignment.BottomCenter)) }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StationRow(station: Station, isStarting: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().tapToClick(onClick)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            // Always the same width, logo or not, so names line up — as the room list does.
            Box(Modifier.size(48.dp)) {
                station.favicon?.let { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)),
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(station.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                listOfNotNull(station.format, station.countryCode).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
            if (isStarting) {
                Spacer(Modifier.width(16.dp))
                Text("Starting…", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
