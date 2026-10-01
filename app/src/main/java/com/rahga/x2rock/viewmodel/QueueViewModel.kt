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
        load()
    }

    fun reload() = load()

    fun playItem(trackNumber: Int) {
        viewModelScope.launch {
            runCatching { household.skipToQueueItem(groupId, trackNumber) }
                // Re-read on success: the queue may just have become the source again.
                .onSuccess { load() }
                .onFailure { e -> failureNotice("play that track", e)?.let(_notice::post) }
        }
    }

    /**
     * Takes the track number, not an id: UPnP removes by queue position (`Q:0/<n>`), and
     * the queue has no event to tell us it changed, so it is re-read after.
     */
    fun removeItem(trackNumber: Int) {
        viewModelScope.launch {
            runCatching { household.removeFromQueue(groupId, trackNumber) }
                .onSuccess { load() }
                .onFailure { e -> failureNotice("remove that track", e)?.let(_notice::post) }
        }
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
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
}
