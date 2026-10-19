package com.rahga.x2rock.ui.screens

import android.text.format.DateFormat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.rahga.x2rock.model.isPlaying
import com.rahga.x2rock.ui.theme.rememberArtColors
import com.rahga.x2rock.ui.theme.requestFocusRetrying
import com.rahga.x2rock.viewmodel.NowPlayingUi
import com.rahga.x2rock.viewmodel.NowPlayingViewModel
import kotlinx.coroutines.delay
import java.util.Date

/**
 * The room's music on the whole screen: the art as tall as the screen allows, the track, the room,
 * a progress line and the clock, and nothing to press. Any key but Back closes it as well, since
 * on a remote a screen with nothing to focus otherwise reads as stuck; the media keys never get
 * here (`MainActivity` takes them first), so play/pause works without leaving.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NowPlayingScreen(onBack: () -> Unit, viewModel: NowPlayingViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    // Once only: the screen stays composed, and focused, through the navigation's fade, and a
    // second key released then would pop the home screen too, leaving nothing but a blank.
    var closed by remember { mutableStateOf(false) }
    val close = { if (!closed) { closed = true; onBack() } }
    LaunchedEffect(state.gone) { if (state.gone) close() }

    // Kept awake while the room plays, which is what this view is for; paused or idle, the
    // television's own screensaver may take over as it would anywhere else.
    val view = LocalView.current
    val playing = state.playbackState.isPlaying()
    DisposableEffect(view, playing) {
        view.keepScreenOn = playing
        onDispose { view.keepScreenOn = false }
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocusRetrying() }

    // A few dp of drift a minute, so nothing sits on the same pixels for an hour.
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); step++ } }
    val (dx, dy) = DRIFT[step % DRIFT.size]
    val driftX by animateDpAsState(dx.dp, tween(3_000), label = "driftX")
    val driftY by animateDpAsState(dy.dp, tween(3_000), label = "driftY")

    val surface = MaterialTheme.colorScheme.surface
    val tint by animateColorAsState(
        rememberArtColors(state.artUrl)?.shade?.takeIf { !state.onTvInput } ?: surface,
        tween(800), label = "tint",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(tint, surface)))
            .focusRequester(focus)
            // Closes on the key's release, not its press: a press closing it would leave the
            // release to land on the pane's button underneath, which clicks on release.
            .onKeyEvent { event ->
                when {
                    event.key in PASS_THROUGH -> false
                    event.type == KeyEventType.KeyUp -> { close(); true }
                    else -> true
                }
            }
            .focusable()
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .offset(driftX, driftY)
                .padding(horizontal = 64.dp, vertical = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(56.dp),
        ) {
            Art(state, Modifier.fillMaxHeight().aspectRatio(1f))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Clock(Modifier.align(Alignment.End))
                Spacer(Modifier.weight(1f))
                Text(state.room, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Text(state.title, style = MaterialTheme.typography.displayMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (state.subtitle.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(state.subtitle, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.weight(1f))
                Progress(state)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Art(state: NowPlayingUi, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        when {
            state.artUrl != null -> AsyncImage(
                model = state.artUrl,
                contentDescription = "Album art",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // The pane's glyphs, for the same sources and the same reason: they send no image.
            state.onTvInput || state.isRadio -> Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (state.onTvInput) Icons.Filled.Tv else Icons.Filled.Radio,
                    contentDescription = null,
                    modifier = Modifier.size(160.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/** The time of day, in the television's own 12- or 24-hour form, turning over on the minute. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Clock(modifier: Modifier) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - now % 60_000)
            now = System.currentTimeMillis()
        }
    }
    Text(
        DateFormat.getTimeFormat(context).format(Date(now)),
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** A thin line and the two times, as the pane's bar is when not focused; blank with no duration. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Progress(state: NowPlayingUi) {
    if (state.durationMillis <= 0) return
    val position by rememberLivePosition(state.positionMillis, state.positionUpdatedAt, state.playbackState, state.durationMillis)
    Column(Modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { (position.toFloat() / state.durationMillis).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = MaterialTheme.colorScheme.primary,
            // The theme's, as the pane's seek bar: material3's own default is a light-theme grey.
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(position.toTimeString(), style = MaterialTheme.typography.bodyMedium)
            Text(state.durationMillis.toTimeString(), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Keys this screen leaves alone: Back is the navigation's, and the volume keys the television's. */
private val PASS_THROUGH = setOf(Key.Back, Key.VolumeUp, Key.VolumeDown, Key.VolumeMute)

/** Where the content sits, minute by minute, in dp from centre. */
private val DRIFT = listOf(0 to 0, 6 to 4, -4 to 6, 4 to -6, -6 to -4)
