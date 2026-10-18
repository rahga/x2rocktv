package com.rahga.x2rock.viewmodel

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rahga.x2rock.model.QueueItem
import com.rahga.x2rock.lan.GroupState
import com.rahga.x2rock.lan.SonosHousehold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.Date
import java.text.SimpleDateFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import javax.inject.Inject

/**
 * A queue row with its 1-based slot, which is what `Seek TRACK_NR` and the queue edits take.
 */
data class QueueEntry(val trackNumber: Int, val item: QueueItem)

/** Numbers every item by its slot in the queue. */
fun queueEntries(items: List<QueueItem>): List<QueueEntry> =
    items.mapIndexed { index, item -> QueueEntry(trackNumber = index + 1, item = item) }

/** The slots of the entries shown either side of [trackNumber]; null where there is none. */
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

    private val room = RoomTarget(household, savedStateHandle)

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
    private val source = MutableStateFlow<Pair<String?, Boolean>>(null to true)
    val uiState: StateFlow<UiState> = combine(
        _uiState, household.state, household.groupStates, source,
    ) { read, _, states, source ->
        if (read is UiState.Success) {
            val id = room.currentOrNull()
            val inUse = if (source.first == id) source.second else true
            read.copy(currentTrackName = states[id]?.track?.name?.takeIf { inUse }, inUse = inUse)
        } else read
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading)

    private val _notice = TransientNotice(viewModelScope)

    /** Why the last play or remove did not work, for a few seconds. */
    val notice: StateFlow<String?> = _notice.text

    init {
        // Source and current track can change without an edit to the queue. Re-check the
        // source on metadata changes only, never on position ticks or volume events.
        viewModelScope.launch {
            combine(household.state, household.groupStates) { _, states ->
                val id = room.currentOrNull()
                Triple(id, states[id]?.container, states[id]?.track)
            }.distinctUntilChanged().collectLatest { (id, _, _) ->
                if (id != null) {
                    source.value = id to attempt { household.playingFromQueue(id) }.getOrDefault(true)
                }
            }
        }
        // The room's group and its version, as read now — taken before the read, so an edit
        // landing between the two only costs a second read. A null version is a room that has
        // not said yet, early in a session: the first to arrive is then a change, because nothing
        // says the list has it. A regroup moves the room to another group, whose queue is another
        // queue: the pair changing reads that one, as a version does.
        fun where(states: Map<String, GroupState>): Pair<String?, String?> =
            room.currentOrNull().let { id -> id to id?.let { states[it]?.queueVersion } }
        val openedAt = where(household.groupStates.value)
        load()
        // Kept current while this screen is open, which is this view model's whole life, by the
        // room's `queueVersion`. It moves on edits while idle or playing the queue. While
        // playing a station, an external queue edit may send no event at all (2026-10-11
        // capture, docs/lan-transport.md); reopening reads fresh, our own edits have a fallback.
        // Nothing is browsed on a timer or per playback tick. The
        // topology is combined in, since a regroup is what moves the room: judged off group
        // states alone it was noticed only when one happened to arrive after.
        viewModelScope.launch {
            combine(household.state, household.groupStates) { _, states -> where(states) }
                .filter { (id, version) -> id != null && version != null }
                .distinctUntilChanged()
                .filter { it != openedAt }
                .collect { load(quiet = true) }
        }
    }

    fun reload() = load()

    fun playItem(trackNumber: Int) {
        viewModelScope.launch {
            runCatching { household.skipToQueueItem(room.current(), trackNumber) }
                // Re-read on success: the queue may just have become the source again.
                .onSuccess { load(quiet = true) }
                .onFailure { _notice.failure("play that track", it) }
        }
    }

    /** Takes the track number, not an id: UPnP removes by queue position (`Q:0/<n>`). */
    /**
     * [openedAt] is the queue's version when the item's menu opened, where a menu did: an edit pressed
     * after the queue changed under the open menu is refused rather than sent to whatever now sits in
     * the slot. Another controller removing an earlier track moved every slot up one, and Remove then
     * took the next track (P-41).
     */
    fun removeItem(trackNumber: Int, openedAt: String? = null) = edit("remove that track", trackNumber, openedAt) {
        household.removeFromQueue(room.current(), trackNumber, updateId)
    }

    /**
     * Move a track past the one shown above or below it. The neighbour is found when the edit runs,
     * against the list as it then is, not as it was when pressed.
     */
    fun moveUp(trackNumber: Int, openedAt: String? = null) = edit("move that track", trackNumber, openedAt) {
        neighbourSlots(entries(), trackNumber).first?.let { household.moveInQueue(room.current(), trackNumber, it, updateId) }
    }
    fun moveDown(trackNumber: Int, openedAt: String? = null) = edit("move that track", trackNumber, openedAt) {
        neighbourSlots(entries(), trackNumber).second?.let { household.moveInQueue(room.current(), trackNumber, it, updateId) }
    }

    private fun entries() = (_uiState.value as? UiState.Success)?.entries.orEmpty()

    private val _clearArmed = MutableStateFlow(false)

    /**
     * True for a few seconds after the first press of Clear, when a second press empties the
     * queue. Two presses rather than a dialog: one is easy to hit by accident on a remote, and
     * a queue cannot be got back.
     */
    val clearArmed: StateFlow<Boolean> = _clearArmed.asStateFlow()
    private var disarm: Job? = null

    fun clearQueue() {
        if (!_clearArmed.value) {
            _clearArmed.value = true
            disarm?.cancel()
            disarm = viewModelScope.launch {
                delay(CLEAR_CONFIRM_MILLIS)
                _clearArmed.value = false
            }
            return
        }
        disarm?.cancel()
        _clearArmed.value = false
        edit("clear the queue", trackNumber = null) { household.clearQueue(room.current()) }
    }

    /**
     * Save the queue as a Sonos playlist, named for the room and the moment — there is no
     * keyboard to ask with, and the Sonos app can rename it.
     */
    fun saveAsPlaylist() {
        val roomName = household.state.value.groups.firstOrNull { it.id == room.forNavigation() }?.name ?: "Queue"
        val name = "$roomName, ${SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date())}"
        viewModelScope.launch {
            runCatching { household.saveQueue(room.current(), name) }
                .onSuccess { _notice.post("Saved as \"$name\"") }
                .onFailure { _notice.failure("save the queue", it) }
        }
    }

    /** The queue's `UpdateID` as the list on screen was read; every edit quotes it. */
    private val _updateId = MutableStateFlow("0")
    private val updateId: String get() = _updateId.value
    /** The queue's version as last read, for a menu to note when it opens; see [removeItem]. */
    val queueVersion: String get() = _updateId.value

    /** One edit at a time: see [edit]. */
    private val edits = Mutex()

    /**
     * An edit, run only once the one before it has landed and the list been re-read.
     *
     * Two presses close together used to go out together, both quoting the same `UpdateID`; the
     * player took the first and refused the second as stale (UPnP 1028). Now the second waits. And
     * since the list may have changed under it, it first checks that [trackNumber] still holds the
     * track that was pressed — slot numbers shift when a row above goes — and says so rather than
     * editing whatever moved into the slot.
     *
     * The re-read is the watcher's where it can be: edits while the queue is active push a new
     * `queueVersion`, and reading here as well costs a second Browse. A refusal still re-reads
     * here, and so does an edit with no version push (including while a station plays).
     */
    private fun edit(what: String, trackNumber: Int?, openedAt: String? = null, block: suspend () -> Unit) {
        if (openedAt != null && openedAt != updateId) {
            _notice.post("The queue changed before that could be done; choose the track again")
            return
        }
        val pressed = trackNumber?.let { n -> entries().firstOrNull { it.trackNumber == n }?.item?.track }
        viewModelScope.launch {
            edits.withLock {
                if (trackNumber != null && entries().firstOrNull { it.trackNumber == trackNumber }?.item?.track != pressed) {
                    _notice.post("The queue changed before that could be done; choose the track again")
                    return@withLock
                }
                val before = updateId
                runCatching { block() }
                    .onSuccess {
                        val pushed = household.groupStates.value[room.forNavigation()]?.queueVersion != null
                        val reread = pushed && withTimeoutOrNull(EDIT_SETTLE_MILLIS) { _updateId.first { it != before } } != null
                        if (!reread) read(quiet = true)
                    }
                    .onFailure {
                        _notice.failure(what, it)
                        read(quiet = true)
                    }
            }
        }
    }

    /**
     * [quiet] keeps the list on screen until the new one arrives: after an edit, blanking it
     * to "Loading" would throw the remote's place away on every move.
     */
    private fun load(quiet: Boolean = false) {
        viewModelScope.launch { read(quiet) }
    }

    /**
     * The latest read started. Reads overlap — a version event, Retry, a play, an edit's fallback —
     * and each used to publish when it finished, so an older one finishing last rolled the list
     * back over a newer one (outside review, 2026-10-08). Only the latest started may publish: it
     * began after whatever made it, so it is the one that can be current.
     */
    private var latestRead = 0

    private suspend fun read(quiet: Boolean) {
        val mine = ++latestRead
        if (!quiet || _uiState.value !is UiState.Success) _uiState.value = UiState.Loading
        runCatching {
            // The queue is the one thing still asked for rather than pushed; what is
            // playing is already known from the household's subscriptions.
            val queue = household.queue(room.current())
            queue.updateId to UiState.Success(queueEntries(queue.items), currentTrackName = null)
        }
            .onSuccess { (version, state) ->
                if (mine != latestRead) return@onSuccess
                _uiState.value = state
                // The version after the list it belongs to: an edit waiting on it then checks its
                // track against this list, not the one before. The other way round it woke a moment
                // early and acted on the old list.
                _updateId.value = version ?: "0"
            }
            .onFailure { if (mine == latestRead) _uiState.value = UiState.Error(it.message ?: "Failed to load queue") }
    }

    private companion object {
        const val CLEAR_CONFIRM_MILLIS = 4_000L
        /** How long an edit waits for the pushed version's re-read before reading itself. */
        const val EDIT_SETTLE_MILLIS = 3_000L
    }
}
