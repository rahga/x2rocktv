package com.rahga.x2rock.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rahga.x2rock.model.Favorite
import com.rahga.x2rock.lan.SonosHousehold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val household: SonosHousehold,
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

    private val _notice = TransientNotice(viewModelScope)

    /** Why the last favourite did not load, for a few seconds. */
    val notice: StateFlow<String?> = _notice.text

    init {
        load()
    }

    fun reload() = load()

    fun loadFavorite(favoriteId: String, onDone: () -> Unit) {
        // One at a time: the rows are never disabled — a disabled tv-material3 row keeps focus
        // and loses its highlight — so this is where a second press is turned away.
        if (loadingFavoriteId.value != null) return
        viewModelScope.launch {
            loadingFavoriteId.value = favoriteId
            val loaded = runCatching { household.loadFavorite(groupId, favoriteId) }
            loadingFavoriteId.value = null
            // Back to the room only once it worked. On failure the list stays, so the reason
            // is said where the viewer is and another favourite is one press away.
            loaded
                .onSuccess { onDone() }
                .onFailure { e -> failureNotice("play that favourite", e)?.let(_notice::post) }
        }
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            runCatching {
                val favs = household.favorites()
                // What is playing comes from the subscription, so only the list is fetched.
                val containerName = household.groupState(groupId).container?.name
                val activeId = containerName?.let { name -> favs.items.find { it.name == name }?.id }
                UiState.Success(favs.items, activeId)
            }
                .onSuccess { _uiState.value = it }
                .onFailure { _uiState.value = UiState.Error(it.message ?: "Failed to load favorites") }
        }
    }
}
