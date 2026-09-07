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
        data class Success(val entries: List<QueueEntry>, val currentTrackName: String?) : UiState
        data class Error(val message: String) : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun reload() = load()

    fun playItem(trackNumber: Int) {
        viewModelScope.launch {
            runCatching { household.skipToQueueItem(groupId, trackNumber) }
        }
    }

    /**
     * Takes the track number, not an id: UPnP removes by queue position (`Q:0/<n>`), and
     * the queue has no event to tell us it changed, so it is re-read after.
     */
    fun removeItem(trackNumber: Int) {
        viewModelScope.launch {
            runCatching { household.removeFromQueue(groupId, trackNumber) }.onSuccess { load() }
        }
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            runCatching {
                // The queue is the one thing still asked for rather than pushed; what is
                // playing is already known from the household's subscriptions.
                val queue = household.queue(groupId)
                UiState.Success(queueEntries(queue.items), household.groupState(groupId).track?.name)
            }
                .onSuccess { _uiState.value = it }
                .onFailure { _uiState.value = UiState.Error(it.message ?: "Failed to load queue") }
        }
    }
}
