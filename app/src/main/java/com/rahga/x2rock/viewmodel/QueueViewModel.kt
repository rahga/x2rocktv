package com.rahga.x2rock.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rahga.x2rock.model.QueueItem
import com.rahga.x2rock.repository.SonosRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class QueueViewModel @Inject constructor(
    private val repository: SonosRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val groupId: String = checkNotNull(savedStateHandle["groupId"])

    sealed interface UiState {
        data object Loading : UiState
        data class Success(val items: List<QueueItem>, val currentTrackName: String?) : UiState
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
            repository.skipToQueueItem(groupId, trackNumber)
        }
    }

    fun removeItem(itemId: String) {
        viewModelScope.launch {
            repository.deleteQueueItems(groupId, listOf(itemId)).onSuccess { load() }
        }
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            runCatching {
                coroutineScope {
                    val queueDeferred = async { repository.getQueue(groupId).getOrThrow() }
                    val metaDeferred = async { repository.getPlaybackMetadata(groupId).getOrNull() }
                    val queue = queueDeferred.await()
                    val currentTrackName = metaDeferred.await()?.currentItem?.track?.name
                    UiState.Success(queue.items.filter { !it.deleted }, currentTrackName)
                }
            }
                .onSuccess { _uiState.value = it }
                .onFailure { _uiState.value = UiState.Error(it.message ?: "Failed to load queue") }
        }
    }
}
