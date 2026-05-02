package com.rahga.x2rock.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rahga.x2rock.model.Favorite
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
class FavoritesViewModel @Inject constructor(
    private val repository: SonosRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val groupId: String = checkNotNull(savedStateHandle["groupId"])

    sealed interface UiState {
        data object Loading : UiState
        data class Success(val items: List<Favorite>, val activeId: String?) : UiState
        data class Error(val message: String) : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val loadingFavoriteId = MutableStateFlow<String?>(null)

    init {
        load()
    }

    fun reload() = load()

    fun loadFavorite(favoriteId: String, onDone: () -> Unit) {
        viewModelScope.launch {
            loadingFavoriteId.value = favoriteId
            repository.loadFavorite(groupId, favoriteId)
            loadingFavoriteId.value = null
            onDone()
        }
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            runCatching {
                coroutineScope {
                    val favsDeferred = async { repository.getFavorites().getOrThrow() }
                    val metaDeferred = async { repository.getPlaybackMetadata(groupId).getOrNull() }
                    val favs = favsDeferred.await()
                    val containerName = metaDeferred.await()?.container?.name
                    val activeId = containerName?.let { name -> favs.items.find { it.name == name }?.id }
                    UiState.Success(favs.items, activeId)
                }
            }
                .onSuccess { _uiState.value = it }
                .onFailure { _uiState.value = UiState.Error(it.message ?: "Failed to load favorites") }
        }
    }
}
