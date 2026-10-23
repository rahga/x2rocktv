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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

/**
 * The alarm being put together, a step at a time: a new one, or with [editing] an existing one,
 * which opens on its summary and comes back to it after each change.
 */
data class AlarmDraft(
    val step: Step = Step.ROOM,
    val room: AlarmRoom? = null,
    val hour: Int = 7,
    val minute: Int = 0,
    val recurrence: String = "DAILY",
    /** Null for the chime. */
    val program: AlarmProgram? = null,
    val volume: Int = 20,
    /** The alarm as it was read, when this changes one rather than making one. */
    val editing: Alarm? = null,
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

    private var loadJob: Job? = null
    private var createJob: Job? = null
    /** The alarms as last read, so one can be opened for editing whole. */
    private var read: List<Alarm> = emptyList()

    private val _removeArmed = MutableStateFlow(false)
    /**
     * True for a few seconds after the first press of Remove, when a second press deletes the
     * alarm. Two presses, as the queue's Clear: one is easy to hit by accident on a remote, and an
     * alarm removed cannot be got back.
     */
    val removeArmed: StateFlow<Boolean> = _removeArmed.asStateFlow()
    private var disarm: Job? = null

    /** Each read replaces the one before, so an older list never lands over a newer one. */
    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            runCatching {
                val clock = runCatching { household.householdClock() }.getOrNull()
                val alarms = household.alarms().sortedWith(compareBy({ it.startTime }, { roomName(it.roomUuid) }))
                read = alarms
                UiState.Ready(alarms.map(::row), clock)
            }
                .onSuccess { _uiState.value = it }
                .onFailure { e -> if (e !is CancellationException && _uiState.value !is UiState.Ready) _uiState.value = UiState.Error(failureNotice("read the alarms", e) ?: "Couldn't read the alarms") }
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

    /** Open alarm [id] to change it, on its summary, as it was read. */
    fun startEdit(id: Int) {
        val alarm = read.firstOrNull { it.id == id } ?: return
        val (hour, minute) = alarm.time.split(':').map { it.toIntOrNull() ?: 0 }.let { it.getOrElse(0) { 0 } to it.getOrElse(1) { 0 } }
        disarmRemove()
        _draft.value = AlarmDraft(
            step = AlarmDraft.Step.CONFIRM,
            room = AlarmRoom(alarm.roomUuid, roomName(alarm.roomUuid)),
            hour = hour, minute = minute,
            recurrence = alarm.recurrence,
            program = if (alarm.isChime) null else AlarmProgram(alarm.programTitle ?: "Music", alarm.programUri, alarm.programMetaData),
            volume = alarm.volume,
            editing = alarm,
        )
    }

    /** Go to one step of an alarm being changed: it comes back to the summary when chosen. */
    fun change(step: AlarmDraft.Step) {
        val d = _draft.value ?: return
        disarmRemove()
        _draft.value = d.copy(step = step)
        if (step == AlarmDraft.Step.SOUND) readPrograms()
    }

    /**
     * One step back, or out of the new alarm altogether from its first step. Changing one, a step
     * goes back to the summary and the summary out.
     */
    fun back() {
        val d = _draft.value ?: return
        disarmRemove()
        _draft.value = when {
            d.editing != null -> if (d.step == AlarmDraft.Step.CONFIRM) null else d.copy(step = AlarmDraft.Step.CONFIRM)
            d.step.ordinal == 0 -> null
            else -> d.copy(step = AlarmDraft.Step.entries[d.step.ordinal - 1])
        }
    }

    fun chooseRoom(room: AlarmRoom) = advance { it.copy(room = room) }

    fun chooseTime(hour: Int, minute: Int) = advance { it.copy(hour = hour, minute = minute) }

    fun chooseRecurrence(recurrence: String) = advance { it.copy(recurrence = recurrence) }

    fun chooseProgram(program: AlarmProgram?) = advance { it.copy(program = program) }

    fun setVolume(volume: Int) { _draft.value = _draft.value?.copy(volume = volume.coerceIn(0, 100)) }

    private fun advance(change: (AlarmDraft) -> AlarmDraft) {
        val d = _draft.value ?: return
        val next = change(d).let {
            if (it.editing != null) it.copy(step = AlarmDraft.Step.CONFIRM)
            else it.copy(step = AlarmDraft.Step.entries[(it.step.ordinal + 1).coerceAtMost(AlarmDraft.Step.entries.lastIndex)])
        }
        _draft.value = next
        if (next.step == AlarmDraft.Step.SOUND) readPrograms()
    }

    /** Asked again while there are none, so one failed read does not leave only the chime for good. */
    private fun readPrograms() {
        if (!_programs.value.isNullOrEmpty()) return
        viewModelScope.launch {
            _programs.value = runCatching { household.alarmPrograms() }
                .onFailure { _notice.failure("read the favourites", it) }
                .getOrDefault(emptyList())
        }
    }

    /**
     * Write the alarm being changed back, its other fields as the player has them now; [onDone]
     * once the list has it. A second press while one is out is turned away, as for [create], and
     * says so: it was ignored without a word, which on another alarm opened since read as a Save
     * that did nothing (P-85, 2026-10-23).
     */
    fun save(onDone: () -> Unit = {}) {
        if (createJob?.isActive == true) return _notice.post(STILL_SAVING)
        val d = _draft.value ?: return
        val editing = d.editing ?: return
        val room = d.room ?: return
        createJob = viewModelScope.launch {
            runCatching {
                household.editAlarm(editing.id) {
                    it.copy(
                        startTime = "${d.time}:00", recurrence = d.recurrence, roomUuid = room.playerId,
                        programUri = d.program?.uri ?: Alarm.CHIME_URI, programMetaData = d.program?.metaData.orEmpty(),
                        volume = d.volume,
                    )
                }
            }
                .onSuccess {
                    closeIfStill(editing.id)
                    load()
                    onDone()
                }
                .onFailure { e ->
                    if ((e as? UpnpRefusedException)?.upnpCode == DUPLICATE) _notice.post("${room.name} already has an alarm at ${d.time}.")
                    else _notice.failure("change the alarm", e)
                }
        }
    }

    /** Remove the alarm being changed: the first press arms it, a second within a few seconds removes it. */
    fun remove() {
        val editing = _draft.value?.editing ?: return
        if (!_removeArmed.value) {
            _removeArmed.value = true
            disarm?.cancel()
            disarm = viewModelScope.launch {
                delay(REMOVE_CONFIRM_MILLIS)
                _removeArmed.value = false
            }
            return
        }
        disarmRemove()
        viewModelScope.launch {
            runCatching { household.removeAlarm(editing.id) }
                .onSuccess { closeIfStill(editing.id) }
                .onFailure { _notice.failure("remove the alarm", it) }
            load()
        }
    }

    /**
     * Close the alarm being changed once [id]'s write has landed — if it is still the one open. Back
     * while the write was out, and another alarm opened since, used to be closed by it mid-change
     * (P-85, 2026-10-23).
     */
    private fun closeIfStill(id: Int) {
        if (_draft.value?.editing?.id == id) _draft.value = null
    }

    private fun disarmRemove() {
        disarm?.cancel()
        _removeArmed.value = false
    }

    /**
     * Make the alarm, armed; [onDone] once it is in the list. A press while one is out is ignored:
     * sent twice, the second is refused as a duplicate of the first, and says so.
     */
    fun create(onDone: () -> Unit = {}) {
        if (createJob?.isActive == true) return _notice.post(STILL_SAVING)
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
        createJob = viewModelScope.launch {
            runCatching { household.createAlarm(alarm) }
                .onSuccess {
                    // Not an alarm opened to change since: only a new one is this one (P-85).
                    if (_draft.value?.editing == null) _draft.value = null
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
        const val REMOVE_CONFIRM_MILLIS = 4_000L
        /** A press while the last write is still out, said rather than ignored (P-85). */
        const val STILL_SAVING = "Saving…"
    }
}
