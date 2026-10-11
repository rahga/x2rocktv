package com.rahga.x2rock.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.first
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.rahga.x2rock.lan.recurrenceLabel
import com.rahga.x2rock.ui.components.DotScanner
import com.rahga.x2rock.ui.components.MediaRow
import com.rahga.x2rock.ui.components.NoticeBanner
import com.rahga.x2rock.ui.components.ScreenHeader
import com.rahga.x2rock.ui.components.SectionTitle
import com.rahga.x2rock.ui.theme.AppButton
import com.rahga.x2rock.ui.theme.requestFocusRetrying
import com.rahga.x2rock.viewmodel.AlarmDraft
import com.rahga.x2rock.viewmodel.AlarmsViewModel

/**
 * The household's alarms, from Settings. Select on one arms or disarms it; "New alarm" walks a room,
 * a time, how often, and what it plays, one step to a screen, and Back steps back. Times are the
 * household's clock, said at the top and never converted.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AlarmsScreen(onBack: () -> Unit, viewModel: AlarmsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val draft by viewModel.draft.collectAsState()
    val notice by viewModel.notice.collectAsState()
    BackHandler { if (draft != null) viewModel.back() else onBack() }

    val firstFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(start = 48.dp, top = 32.dp, end = 48.dp)) {
                ScreenHeader(
                    title = if (draft == null) "Alarms" else "New alarm",
                    room = draft?.room?.name,
                    onBack = { if (draft != null) viewModel.back() else onBack() },
                    backModifier = Modifier.focusRequester(backFocus),
                )
                Spacer(Modifier.height(12.dp))
                val d = draft
                if (d != null) {
                    NewAlarm(d, viewModel, firstFocus)
                } else when (val s = state) {
                    AlarmsViewModel.UiState.Loading -> DotScanner()
                    is AlarmsViewModel.UiState.Error -> {
                        Text(s.message, style = MaterialTheme.typography.bodyMedium)
                        LaunchedEffect(Unit) { backFocus.requestFocusRetrying() }
                    }
                    is AlarmsViewModel.UiState.Ready -> {
                        // Only once this screen is resumed, which is when its navigation's fade has ended.
                        // The list is read fast enough to ask during the fade, while the home screen is
                        // still composed and handing focus back to its Settings button; when that screen
                        // left, focus fell to Back (Shield, 2026-10-10). Preferred services is spared only
                        // by loading more slowly. The list on the way back from a new alarm asks the same.
                        val lifecycle = LocalLifecycleOwner.current.lifecycle
                        LaunchedEffect(Unit) {
                            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
                            firstFocus.requestFocusRetrying()
                        }
                        s.clock?.let { clock ->
                            Text(
                                "Times are this system's clock, now ${clock.time}." +
                                    if (clock.timeZoneSet) "" else " It has no time zone set, so they are UTC.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                        LazyColumn(contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            item(key = "new") {
                                MediaRow(
                                    title = "New alarm",
                                    subtitle = "A room, a time, how often, and what it plays",
                                    artUrl = null,
                                    onClick = { viewModel.startNew() },
                                    modifier = Modifier.focusRequester(firstFocus),
                                )
                            }
                            if (s.alarms.isEmpty()) item(key = "none") {
                                Text("No alarms yet.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                            }
                            items(s.alarms, key = { it.id }) { alarm ->
                                MediaRow(
                                    title = alarm.title,
                                    subtitle = alarm.detail,
                                    artUrl = null,
                                    onClick = { viewModel.toggle(alarm.id) },
                                ) {
                                    Spacer(Modifier.width(16.dp))
                                    Text(
                                        if (alarm.enabled) "On" else "Off",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (alarm.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
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
private fun NewAlarm(draft: AlarmDraft, viewModel: AlarmsViewModel, firstFocus: FocusRequester) {
    val programs by viewModel.programs.collectAsState()
    LaunchedEffect(draft.step) { firstFocus.requestFocusRetrying() }
    when (draft.step) {
        AlarmDraft.Step.ROOM -> {
            val rooms = remember { viewModel.rooms() }
            Choices("Which room?") {
                itemsIndexed(rooms, key = { _, room -> room.playerId }) { i, room ->
                    MediaRow(
                        title = room.name, subtitle = null, artUrl = null,
                        onClick = { viewModel.chooseRoom(room) },
                        modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
            }
        }
        AlarmDraft.Step.TIME -> TimeStep(draft, viewModel, firstFocus)
        AlarmDraft.Step.REPEAT -> Choices("How often?") {
            items(RECURRENCES, key = { it }) { recurrence ->
                MediaRow(
                    title = recurrenceLabel(recurrence), subtitle = null, artUrl = null,
                    onClick = { viewModel.chooseRecurrence(recurrence) },
                    modifier = if (recurrence == RECURRENCES.first()) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
        AlarmDraft.Step.SOUND -> Choices("What should it play?") {
            item(key = "chime") {
                MediaRow(
                    title = "Chime", subtitle = "The speaker's own", artUrl = null,
                    onClick = { viewModel.chooseProgram(null) },
                    modifier = Modifier.focusRequester(firstFocus),
                )
            }
            val list = programs
            if (list == null) item(key = "loading") { DotScanner() }
            else items(list, key = { it.uri }) { program ->
                MediaRow(title = program.title, subtitle = "A Sonos favourite", artUrl = null, onClick = { viewModel.chooseProgram(program) })
            }
        }
        AlarmDraft.Step.CONFIRM -> ConfirmStep(draft, viewModel, firstFocus)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Choices(question: String, content: LazyListScope.() -> Unit) {
    SectionTitle(question, first = true)
    LazyColumn(contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/**
 * Hours and minutes, each a button that Up and Down turn, as a television's own time pickers do;
 * Select on either goes on. Held, the keys repeat.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TimeStep(draft: AlarmDraft, viewModel: AlarmsViewModel, firstFocus: FocusRequester) {
    var hour by remember { mutableIntStateOf(draft.hour) }
    var minute by remember { mutableIntStateOf(draft.minute) }
    SectionTitle("What time?", first = true)
    Text(
        "Up and Down to change it, Select to go on.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Wheel("%02d".format(java.util.Locale.ROOT, hour), { hour = (hour + it + 24) % 24 }, { viewModel.chooseTime(hour, minute) }, Modifier.focusRequester(firstFocus))
        Text(":", style = MaterialTheme.typography.displayMedium, modifier = Modifier.padding(horizontal = 12.dp))
        Wheel("%02d".format(java.util.Locale.ROOT, minute), { minute = (minute + it + 60) % 60 }, { viewModel.chooseTime(hour, minute) })
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Wheel(value: String, step: (Int) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    AppButton(
        onClick = onDone,
        modifier = modifier.widthIn(min = 120.dp).onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionUp -> { step(1); true }
                Key.DirectionDown -> { step(-1); true }
                else -> false
            }
        },
    ) {
        Text(value, style = MaterialTheme.typography.displayMedium)
    }
}

/** What is about to be made, its volume on Left and Right, and the button that makes it. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ConfirmStep(draft: AlarmDraft, viewModel: AlarmsViewModel, firstFocus: FocusRequester) {
    SectionTitle("${draft.time} · ${draft.room?.name.orEmpty()}", first = true)
    Text(
        listOf(recurrenceLabel(draft.recurrence), draft.program?.title ?: "Chime").joinToString(" · "),
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(24.dp))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppButton(
            onClick = {},
            modifier = Modifier.widthIn(min = 320.dp).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { viewModel.setVolume(draft.volume - 1); true }
                    Key.DirectionRight -> { viewModel.setVolume(draft.volume + 1); true }
                    else -> false
                }
            },
        ) { Text("Volume ${draft.volume}  ·  Left and Right to change") }
        AppButton(onClick = { viewModel.create() }, modifier = Modifier.widthIn(min = 320.dp).focusRequester(firstFocus)) {
            Text("Make this alarm")
        }
    }
}

private val RECURRENCES = listOf("DAILY", "WEEKDAYS", "WEEKENDS", "ONCE")
