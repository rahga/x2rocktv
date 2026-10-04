package com.rahga.x2rock.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rahga.x2rock.model.QueueItem
import com.rahga.x2rock.lan.SonosHousehold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A queue row paired with its position in the *unfiltered* queue. The API's seek command takes a
 * 1-based track number over the whole queue, so it can't be derived from the displayed list —
 * deleted tombstones are hidden but still occupy their slot.
 */
data class QueueEntry(val trackNumber: Int, val item: QueueItem)

/** Numbers every item by its slot in the full queue, then hides the tombstones. */
fun queueEntries(items: List<QueueItem>): List<QueueEntry> =
    items.mapIndexed { index, item -> QueueEntry(trackNumber = index + 1, item = item) }
        .filter { !it.item.deleted }

/**
 * The slots of the entries shown either side of [trackNumber]; null where there is none. Not
 * `trackNumber ± 1`: a tombstone keeps its slot, so the neighbour on screen can be slots away,
 * and a move by one landed on the tombstone and looked like nothing happened.
 */
fun neighbourSlots(entries: List<QueueEntry>, trackNumber: Int): Pair<Int?, Int?> {
    val index = entries.indexOfFirst { it.trackNumber == trackNumber }
    if (index < 0) return null to null
    return entries.getOrNull(index - 1)?.trackNumber to entries.getOrNull(index + 1)?.trackNumber
}

@HiltViewModel
class QueueViewModel @Inject constructor(
    private val household: SonosHousehold,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val groupId: String = checkNotNull(savedStateHandle["groupId"])

    sealed interface UiState {
        data object Loading : UiState
        data class Success(
            val entries: List<QueueEntry>,
            /** Only while the queue is the source: a station's track must not light a queue row. */
            val currentTrackName: String?,
            /**
             * Whether the room is playing from this queue. After a station, a stream or the TV
             * input it is not, though the tracks are all still here; choosing one switches back.
             */
            val inUse: Boolean = true,
        ) : UiState
        data class Error(val message: String) : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _notice = TransientNotice(viewModelScope)

    /** Why the last play or remove did not work, for a few seconds. */
    val notice: StateFlow<String?> = _notice.text

    init {
        // The version the list is read at. Taken before the read, so an edit landing between the
        // two only costs a second read. Null when the room has not said yet, early in a session:
        // the first version to arrive is then a change, because nothing says the list has it.
        val openedAt = household.groupStates.value[groupId]?.queueVersion
        load()
        // Kept current while this screen is open, which is this view model's whole life, by the
        // room's `queueVersion`. Verified at home on 2026-10-01: each edit moved it (26, 27, 28
        // for a move and its undo) and each arrived as a playback event, on an idle room too.
        // So nothing is browsed on a timer or per event; the version moving is the signal.
        viewModelScope.launch {
            household.groupStates.map { it[groupId]?.queueVersion }
                .filterNotNull()
                .distinctUntilChanged()
                .filter { it != openedAt }
                .collect { load(quiet = true) }
        }
    }

    fun reload() = load()

    fun playItem(trackNumber: Int) {
        viewModelScope.launch {
            runCatching { household.skipToQueueItem(groupId, trackNumber) }
                // Re-read on success: the queue may just have become the source again.
                .onSuccess { load(quiet = true) }
                .onFailure { _notice.failure("play that track", it) }
        }
    }

    /**
     * Takes the track number, not an id: UPnP removes by queue position (`Q:0/<n>`), and
     * the queue has no event to tell us it changed, so it is re-read after.
     */
    fun removeItem(trackNumber: Int) {
        viewModelScope.launch {
            runCatching { household.removeFromQueue(groupId, trackNumber) }
                .onSuccess { load(quiet = true) }
                .onFailure { _notice.failure("remove that track", it) }
        }
    }

    /** Move a track past the one shown above or below it. The queue is not pushed, so it is re-read. */
    fun moveUp(trackNumber: Int) = neighbourSlots(entries(), trackNumber).first?.let { move(trackNumber, it) }
    fun moveDown(trackNumber: Int) = neighbourSlots(entries(), trackNumber).second?.let { move(trackNumber, it) }

    private fun entries() = (uiState.value as? UiState.Success)?.entries.orEmpty()

    private fun move(from: Int, to: Int) {
        edit("move that track") { household.moveInQueue(groupId, from, to) }
    }

    private val _clearArmed = MutableStateFlow(false)

    /**
     * True for a few seconds after the first press of Clear, when a second press empties the
     * queue. Two presses rather than a dialog: one is easy to hit by accident on a remote, and
     * a queue cannot be got back.
     */
    val clearArmed: StateFlow<Boolean> = _clearArmed.asStateFlow()
    private var disarm: kotlinx.coroutines.Job? = null

    fun clearQueue() {
        if (!_clearArmed.value) {
            _clearArmed.value = true
            disarm?.cancel()
            disarm = viewModelScope.launch {
                kotlinx.coroutines.delay(CLEAR_CONFIRM_MILLIS)
                _clearArmed.value = false
            }
            return
        }
        disarm?.cancel()
        _clearArmed.value = false
        edit("clear the queue") { household.clearQueue(groupId) }
    }

    /**
     * Save the queue as a Sonos playlist, named for the room and the moment — there is no
     * keyboard to ask with, and the Sonos app can rename it.
     */
    fun saveAsPlaylist() {
        val room = household.state.value.groups.firstOrNull { it.id == groupId }?.name ?: "Queue"
        val name = "$room, ${java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}"
        viewModelScope.launch {
            runCatching { household.saveQueue(groupId, name) }
                .onSuccess { _notice.post("Saved as \"$name\"") }
                .onFailure { _notice.failure("save the queue", it) }
        }
    }

    /** An edit, then a re-read either way: on a refusal the queue may have moved under it. */
    private fun edit(what: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure { _notice.failure(what, it) }
            load(quiet = true)
        }
    }

    /**
     * [quiet] keeps the list on screen until the new one arrives: after an edit, blanking it
     * to "Loading" would throw the remote's place away on every move.
     */
    private fun load(quiet: Boolean = false) {
        viewModelScope.launch {
            if (!quiet || _uiState.value !is UiState.Success) _uiState.value = UiState.Loading
            runCatching {
                // The queue is the one thing still asked for rather than pushed; what is
                // playing is already known from the household's subscriptions.
                val queue = household.queue(groupId)
                // Unknown counts as in use: a failed read must not hide the marker it can't
                // disprove, and the play path checks again for itself.
                val inUse = runCatching { household.playingFromQueue(groupId) }.getOrDefault(true)
                UiState.Success(
                    queueEntries(queue.items),
                    household.groupState(groupId).track?.name?.takeIf { inUse },
                    inUse,
                )
            }
                .onSuccess { _uiState.value = it }
                .onFailure { _uiState.value = UiState.Error(it.message ?: "Failed to load queue") }
        }
    }

    private companion object {
        const val CLEAR_CONFIRM_MILLIS = 4_000L
    }
}
