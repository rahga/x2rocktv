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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.rahga.x2rock.ui.components.DotScanner
import com.rahga.x2rock.ui.components.MediaRow
import com.rahga.x2rock.ui.components.RowStatus
import com.rahga.x2rock.ui.components.ScreenHeader
import com.rahga.x2rock.ui.theme.requestFocusRetrying
import com.rahga.x2rock.ui.theme.requestFocusWhenResumed
import com.rahga.x2rock.viewmodel.AboutSystemViewModel
import com.rahga.x2rock.viewmodel.detail

/**
 * About this system, from Settings: every speaker by room, what it is and what it runs, and whether
 * an update waits. Rows are focus stops only so a long list scrolls on a remote; nothing is pressed
 * here, and updating is left to the Sonos app.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AboutSystemScreen(onBack: () -> Unit, viewModel: AboutSystemViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    BackHandler { onBack() }
    val firstFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(start = 48.dp, top = 32.dp, end = 48.dp)) {
            ScreenHeader(title = "About this system", room = null, onBack = onBack, backModifier = Modifier.focusRequester(backFocus))
            Spacer(Modifier.height(12.dp))
            when (val s = state) {
                AboutSystemViewModel.UiState.Loading -> DotScanner()
                is AboutSystemViewModel.UiState.Error -> {
                    Text(s.message, style = MaterialTheme.typography.bodyMedium)
                    LaunchedEffect(Unit) { backFocus.requestFocusRetrying() }
                }
                is AboutSystemViewModel.UiState.Ready -> {
                    // Once resumed, past the navigation's fade: see requestFocusWhenResumed.
                    val lifecycle = LocalLifecycleOwner.current.lifecycle
                    LaunchedEffect(Unit) { firstFocus.requestFocusWhenResumed(lifecycle) }
                    Text(
                        when (s.waiting) {
                            0 -> "Every speaker is up to date."
                            1 -> "1 speaker has an update waiting. Update it in the Sonos app."
                            else -> "${s.waiting} speakers have an update waiting. Update them in the Sonos app."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    LazyColumn(contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(s.speakers, key = { _, it -> it.playerId }) { index, speaker ->
                            MediaRow(
                                title = speaker.room,
                                subtitle = speaker.detail(),
                                artUrl = null,
                                onClick = {},
                                modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                            ) {
                                if (speaker.updateWaiting) RowStatus("Update available", highlighted = true)
                            }
                        }
                    }
                }
            }
        }
    }
}
