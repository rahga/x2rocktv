package com.rahga.x2rock.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rahga.x2rock.model.Group
import com.rahga.x2rock.model.Track
import com.rahga.x2rock.repository.SonosAuthRepository
import com.rahga.x2rock.repository.SonosRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: SonosRepository,
    private val authRepository: SonosAuthRepository
) : ViewModel() {

    sealed interface UiState {
        data object Loading : UiState
        data class Success(val groups: List<Group>, val nowPlaying: Map<String, Track?>) : UiState
        data class Error(val message: String) : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(5_000L)
            }
        }
    }

    fun loadGroups() {
        viewModelScope.launch { refresh() }
    }

    fun signOut() {
        authRepository.clearTokens()
    }

    private suspend fun refresh() {
        val showSpinner = _uiState.value is UiState.Error || _uiState.value is UiState.Loading
        if (showSpinner) _uiState.value = UiState.Loading
        repository.getGroups().fold(
            onSuccess = { groups ->
                val nowPlaying = fetchNowPlaying(groups)
                _uiState.value = UiState.Success(groups, nowPlaying)
            },
            onFailure = { e ->
                if (_uiState.value !is UiState.Success) {
                    _uiState.value = UiState.Error(e.message ?: "Unknown error")
                }
            }
        )
    }

    private suspend fun fetchNowPlaying(groups: List<Group>): Map<String, Track?> = coroutineScope {
        groups.map { group ->
            async {
                val track = repository.getPlaybackMetadata(group.id)
                    .getOrNull()?.currentItem?.track
                group.id to track
            }
        }.awaitAll().toMap()
    }
}
