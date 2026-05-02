package com.rahga.x2rock.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rahga.x2rock.auth.RoomPreferencesStore
import com.rahga.x2rock.auth.ThemeStore
import com.rahga.x2rock.model.AppColorTheme
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
    private val authRepository: SonosAuthRepository,
    private val themeStore: ThemeStore,
    private val roomPrefsStore: RoomPreferencesStore
) : ViewModel() {

    sealed interface UiState {
        data object Loading : UiState
        data class Success(val groups: List<Group>, val nowPlaying: Map<String, Track?>) : UiState
        data class Error(val message: String) : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val selectedTheme: StateFlow<AppColorTheme> = themeStore.theme
    val primaryRoomId: StateFlow<String?> = roomPrefsStore.primaryRoomId
    val favoriteRoomIds: StateFlow<Set<String>> = roomPrefsStore.favoriteRoomIds

    private val _selectedGroupId = MutableStateFlow<String?>(null)
    val selectedGroupId: StateFlow<String?> = _selectedGroupId.asStateFlow()

    private val _sidebarVisible = MutableStateFlow(true)
    val sidebarVisible: StateFlow<Boolean> = _sidebarVisible.asStateFlow()

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

    fun setTheme(theme: AppColorTheme) = themeStore.setTheme(theme)

    fun selectGroup(id: String) {
        _selectedGroupId.value = id
    }

    fun toggleSidebar() {
        _sidebarVisible.value = !_sidebarVisible.value
    }

    fun setPrimaryRoom(id: String?) = roomPrefsStore.setPrimaryRoom(id)

    fun toggleFavorite(id: String) = roomPrefsStore.toggleFavorite(id)

    fun joinGroup(sourceGroupId: String, targetGroupId: String) {
        val source = findGroup(sourceGroupId) ?: return
        viewModelScope.launch { repository.joinGroup(source, targetGroupId); refresh() }
    }

    fun soloGroup(groupId: String) {
        val group = findGroup(groupId) ?: return
        viewModelScope.launch { repository.soloGroup(group); refresh() }
    }

    fun partyMode() {
        val groups = (_uiState.value as? UiState.Success)?.groups ?: return
        if (groups.size < 2) return
        val sorted = sortedGroups(groups, roomPrefsStore.primaryRoomId.value, roomPrefsStore.favoriteRoomIds.value)
        viewModelScope.launch { repository.partyMode(sorted.first(), sorted.drop(1)); refresh() }
    }

    private fun findGroup(id: String): Group? =
        (_uiState.value as? UiState.Success)?.groups?.find { it.id == id }

    fun sortedGroups(groups: List<Group>, primaryId: String?, favoriteIds: Set<String>): List<Group> {
        val primary = groups.filter { it.id == primaryId }
        val favorites = groups.filter { it.id != primaryId && it.id in favoriteIds }.sortedBy { it.name }
        val rest = groups.filter { it.id != primaryId && it.id !in favoriteIds }.sortedBy { it.name }
        return primary + favorites + rest
    }

    private suspend fun refresh() {
        val showSpinner = _uiState.value is UiState.Error || _uiState.value is UiState.Loading
        if (showSpinner) _uiState.value = UiState.Loading
        repository.getGroups().fold(
            onSuccess = { groups ->
                val nowPlaying = fetchNowPlaying(groups)
                _uiState.value = UiState.Success(groups, nowPlaying)
                if (_selectedGroupId.value == null && groups.isNotEmpty()) {
                    val sorted = sortedGroups(groups, roomPrefsStore.primaryRoomId.value, roomPrefsStore.favoriteRoomIds.value)
                    _selectedGroupId.value = sorted.first().id
                }
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
