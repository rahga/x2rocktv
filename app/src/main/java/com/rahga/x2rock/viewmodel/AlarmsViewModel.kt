package com.rahga.x2rock.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rahga.x2rock.lan.Alarm
import com.rahga.x2rock.lan.AlarmProgram
import com.rahga.x2rock.lan.HouseholdClock
import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.lan.UpnpRefusedException
import com.rahga.x2rock.lan.recurrenceLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One alarm as the list draws it. */
data class AlarmRow(
    val id: Int,
    /** "06:45 · Kitchen". */
    val title: String,
    /** "Weekdays · Chime · volume 12". */
    val detail: String,
    val enabled: Boolean,
)

/** A room an alarm can ring in: a player, which is what an alarm names. */
data class AlarmRoom(val playerId: String, val name: String)

/** The new alarm being put together, a step at a time. */
data class AlarmDraft(
    val step: Step = Step.ROOM,
    val room: AlarmRoom? = null,
    val hour: Int = 7,
    val minute: Int = 0,
    val recurrence: String = "DAILY",
    /** Null for the chime. */
    val program: AlarmProgram? = null,
    val volume: Int = 20,
) {
    enum class Step { ROOM, TIME, REPEAT, SOUND, CONFIRM }

    /** `HH:MM`, on the household's clock. */
    val time: String get() = "%02d:%02d".format(java.util.Locale.ROOT, hour, minute)
}

/**
 * The household's alarms: listed, armed and disarmed, and made one at a time with the chime or a
 * Sonos favourite. Nothing pushes alarms, so the list is read on opening and again after each change
 * this screen makes — the queue's rule, not a timer.
 *
 * Times are the household's clock, which is said on screen and never converted: with no timezone
 * set the household runs UTC, and "07:00" then means 07:00 UTC (x2rock, 2026-09-04).
 */
@HiltViewModel
class AlarmsViewModel @Inject constructor(
    private val household: SonosHousehold,
) : ViewModel() {

    sealed interface UiState {
        data object Loading : UiState
        data class Ready(val alarms: List<AlarmRow>, val clock: HouseholdClock?) : UiState
        data class Error(val message: String) : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _draft = MutableStateFlow<AlarmDraft?>(null)
    val draft: StateFlow<AlarmDraft?> = _draft.asStateFlow()

    /** The favourites an alarm can play; read when the sound is chosen, not before. */
    private val _programs = MutableStateFlow<List<AlarmProgram>?>(null)
    val programs: StateFlow<List<AlarmProgram>?> = _programs.asStateFlow()

    private val _notice = TransientNotice(viewModelScope)
    val notice: StateFlow<String?> = _notice.text

    init { load() }

    fun load() {
        viewModelScope.launch {
            runCatching {
                val clock = runCatching { household.householdClock() }.getOrNull()
                UiState.Ready(household.alarms().sortedWith(compareBy({ it.startTime }, { roomName(it.roomUuid) })).map(::row), clock)
            }
                .onSuccess { _uiState.value = it }
                .onFailure { e -> if (_uiState.value !is UiState.Ready) _uiState.value = UiState.Error(failureNotice("read the alarms", e) ?: "Couldn't read the alarms") }
        }
    }

    fun toggle(id: Int) {
        val row = (_uiState.value as? UiState.Ready)?.alarms?.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            runCatching { household.setAlarmEnabled(id, !row.enabled) }
                .onFailure { _notice.failure(if (row.enabled) "turn the alarm off" else "turn the alarm on", it) }
            load()
        }
    }

    /** Every room by its own name, alphabetically, as the room list orders them. */
    fun rooms(): List<AlarmRoom> = household.state.value.groups
        .flatMap { it.playerIds }.distinct()
        .map { AlarmRoom(it, household.playerName(it)) }
        .sortedBy { it.name }

    fun startNew() { _draft.value = AlarmDraft() }

    fun cancelNew() { _draft.value = null }

    /** One step back, or out of the new alarm altogether from its first step. */
    fun back() {
        val d = _draft.value ?: return
        _draft.value = if (d.step.ordinal == 0) null else d.copy(step = AlarmDraft.Step.entries[d.step.ordinal - 1])
    }

    fun chooseRoom(room: AlarmRoom) = advance { it.copy(room = room) }

    fun chooseTime(hour: Int, minute: Int) = advance { it.copy(hour = hour, minute = minute) }

    fun chooseRecurrence(recurrence: String) = advance { it.copy(recurrence = recurrence) }

    fun chooseProgram(program: AlarmProgram?) = advance { it.copy(program = program) }

    fun setVolume(volume: Int) { _draft.value = _draft.value?.copy(volume = volume.coerceIn(0, 100)) }

    private fun advance(change: (AlarmDraft) -> AlarmDraft) {
        val d = _draft.value ?: return
        val next = change(d).let { it.copy(step = AlarmDraft.Step.entries[(it.step.ordinal + 1).coerceAtMost(AlarmDraft.Step.entries.lastIndex)]) }
        _draft.value = next
        if (next.step == AlarmDraft.Step.SOUND && _programs.value == null) {
            viewModelScope.launch { _programs.value = runCatching { household.alarmPrograms() }.getOrDefault(emptyList()) }
        }
    }

    /** Make the alarm, armed; [onDone] once it is in the list. */
    fun create(onDone: () -> Unit = {}) {
        val d = _draft.value ?: return
        val room = d.room ?: return
        val alarm = Alarm(
            id = 0,
            startTime = "${d.time}:00",
            recurrence = d.recurrence,
            enabled = true,
            roomUuid = room.playerId,
            programUri = d.program?.uri ?: Alarm.CHIME_URI,
            programMetaData = d.program?.metaData.orEmpty(),
            volume = d.volume,
        )
        viewModelScope.launch {
            runCatching { household.createAlarm(alarm) }
                .onSuccess {
                    _draft.value = null
                    load()
                    onDone()
                }
                .onFailure { e ->
                    // The player's one refusal worth wording: a room has one alarm to a start time.
                    if ((e as? UpnpRefusedException)?.upnpCode == DUPLICATE) {
                        _notice.post("${room.name} already has an alarm at ${d.time}.")
                    } else {
                        _notice.failure("make the alarm", e)
                    }
                }
        }
    }

    private fun row(alarm: Alarm) = AlarmRow(
        id = alarm.id,
        title = "${alarm.time} · ${roomName(alarm.roomUuid)}",
        detail = listOfNotNull(recurrenceLabel(alarm.recurrence), alarm.programTitle ?: "Music", "volume ${alarm.volume}")
            .joinToString(" · "),
        enabled = alarm.enabled,
    )

    /** A speaker off the network keeps its alarms, so it may not be in the household to name. */
    private fun roomName(playerId: String): String =
        if (household.state.value.groups.any { playerId in it.playerIds }) household.playerName(playerId) else "A speaker not on the network"

    private companion object {
        /** `CreateAlarm` for a room and start time that already has one (Kitchen, 2026-10-10). */
        const val DUPLICATE = "801"
    }
}
