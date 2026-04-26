package com.rahga.x2rock.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rahga.x2rock.model.PlayModeState
import com.rahga.x2rock.repository.SonosRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlayerVolumeEntry(
    val playerId: String,
    val playerName: String,
    val volume: Int,
    val muted: Boolean
)

data class PlayerUiState(
    val groupName: String = "",
    val playbackState: String = "PLAYBACK_STATE_IDLE",
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val albumArtUrl: String? = null,
    val positionMillis: Long = 0,
    val durationMillis: Long = 0,
    val positionUpdatedAt: Long = 0,
    val volume: Int = 0,
    val isMuted: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: String = "REPEAT_NONE",
    val playerVolumes: List<PlayerVolumeEntry> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val repository: SonosRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val groupId: String = checkNotNull(savedStateHandle["groupId"])

    private val _uiState = MutableStateFlow(
        PlayerUiState(groupName = savedStateHandle["groupName"] ?: "")
    )
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(5_000L)
            }
        }
    }

    private suspend fun refresh() {
        runCatching {
            val playback = repository.getPlaybackState(groupId).getOrThrow()
            val metadata = repository.getPlaybackMetadata(groupId).getOrNull()
            val vol = repository.getGroupVolume(groupId).getOrThrow()
            val playMode = repository.getPlayMode(groupId).getOrNull()

            val playerIds = repository.getPlayerIdsForGroup(groupId)
            val playerVols = if (playerIds.size > 1) {
                playerIds.mapNotNull { id ->
                    repository.getPlayerVolume(id).getOrNull()?.let { v ->
                        PlayerVolumeEntry(id, repository.getPlayerName(id), v.volume, v.muted)
                    }
                }
            } else emptyList()

            _uiState.update {
                it.copy(
                    isLoading = false,
                    error = null,
                    playbackState = playback.playbackState,
                    trackName = metadata?.currentItem?.track?.name,
                    artistName = metadata?.currentItem?.track?.artist?.name,
                    albumName = metadata?.currentItem?.track?.album?.name,
                    albumArtUrl = metadata?.currentItem?.track?.imageUrl,
                    durationMillis = metadata?.currentItem?.track?.durationMillis ?: 0,
                    positionMillis = playback.positionMillis,
                    positionUpdatedAt = System.currentTimeMillis(),
                    volume = vol.volume,
                    isMuted = vol.muted,
                    shuffle = playMode?.playMode?.shuffle ?: it.shuffle,
                    repeat = playMode?.playMode?.repeat ?: it.repeat,
                    playerVolumes = playerVols
                )
            }
        }.onFailure { e ->
            _uiState.update { it.copy(isLoading = false, error = e.message) }
        }
    }

    fun togglePlayPause() {
        viewModelScope.launch {
            runCatching { repository.togglePlayPause(groupId) }
            delay(300L)
            refresh()
        }
    }

    fun skipToNextTrack() {
        viewModelScope.launch {
            runCatching { repository.skipToNextTrack(groupId) }
            delay(300L)
            refresh()
        }
    }

    fun skipToPreviousTrack() {
        viewModelScope.launch {
            runCatching { repository.skipToPreviousTrack(groupId) }
            delay(300L)
            refresh()
        }
    }

    fun seekBy(deltaMillis: Long) {
        val state = _uiState.value
        val elapsed = if (state.playbackState == "PLAYBACK_STATE_PLAYING")
            System.currentTimeMillis() - state.positionUpdatedAt else 0L
        val current = state.positionMillis + elapsed
        val target = (current + deltaMillis).coerceIn(0, state.durationMillis)
        _uiState.update { it.copy(positionMillis = target, positionUpdatedAt = System.currentTimeMillis()) }
        viewModelScope.launch {
            runCatching { repository.seek(groupId, target) }
            delay(300L)
            refresh()
        }
    }

    fun toggleMute() {
        val newMuted = !_uiState.value.isMuted
        _uiState.update { it.copy(isMuted = newMuted) }
        viewModelScope.launch {
            runCatching { repository.setGroupMute(groupId, newMuted) }
        }
    }

    fun adjustVolume(delta: Int) {
        val newVol = (_uiState.value.volume + delta).coerceIn(0, 100)
        _uiState.update { it.copy(volume = newVol) }
        viewModelScope.launch {
            runCatching { repository.setGroupVolume(groupId, newVol) }
        }
    }

    fun toggleShuffle() {
        val newShuffle = !_uiState.value.shuffle
        _uiState.update { it.copy(shuffle = newShuffle) }
        viewModelScope.launch {
            runCatching {
                repository.setPlayMode(
                    groupId,
                    PlayModeState(repeat = _uiState.value.repeat, shuffle = newShuffle)
                )
            }
        }
    }

    fun cycleRepeat() {
        val next = when (_uiState.value.repeat) {
            "REPEAT_NONE" -> "REPEAT_ALL"
            "REPEAT_ALL" -> "REPEAT_ONE"
            else -> "REPEAT_NONE"
        }
        _uiState.update { it.copy(repeat = next) }
        viewModelScope.launch {
            runCatching {
                repository.setPlayMode(
                    groupId,
                    PlayModeState(repeat = next, shuffle = _uiState.value.shuffle)
                )
            }
        }
    }

    fun adjustPlayerVolume(playerId: String, delta: Int) {
        val entry = _uiState.value.playerVolumes.find { it.playerId == playerId } ?: return
        val newVol = (entry.volume + delta).coerceIn(0, 100)
        _uiState.update {
            it.copy(playerVolumes = it.playerVolumes.map { e ->
                if (e.playerId == playerId) e.copy(volume = newVol) else e
            })
        }
        viewModelScope.launch {
            runCatching { repository.setPlayerVolume(playerId, newVol) }
        }
    }

    fun togglePlayerMute(playerId: String) {
        val entry = _uiState.value.playerVolumes.find { it.playerId == playerId } ?: return
        val newMuted = !entry.muted
        _uiState.update {
            it.copy(playerVolumes = it.playerVolumes.map { e ->
                if (e.playerId == playerId) e.copy(muted = newMuted) else e
            })
        }
        viewModelScope.launch {
            runCatching { repository.setPlayerMute(playerId, newMuted) }
        }
    }
}
