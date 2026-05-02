package com.rahga.x2rock.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rahga.x2rock.model.PlayModeState
import com.rahga.x2rock.repository.SonosRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
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
    val crossfade: Boolean = false,
    val playerVolumes: List<PlayerVolumeEntry> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val sleepTimerRemainingMillis: Long? = null
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val repository: SonosRepository
) : ViewModel() {

    private val _groupId = MutableStateFlow<String?>(null)
    private var pollingJob: Job? = null
    private var volumeDebounceJob: Job? = null
    private val playerVolumeDebounceJobs = mutableMapOf<String, Job>()
    private var sleepTimerJob: Job? = null

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    fun selectGroup(id: String, name: String) {
        if (_groupId.value == id) return
        _groupId.value = id
        _uiState.value = PlayerUiState(groupName = name, isLoading = true)
        pollingJob?.cancel()
        cancelSleepTimer()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(5_000L)
            }
        }
    }

    private suspend fun refresh() {
        val id = _groupId.value ?: return
        runCatching {
            val playback = repository.getPlaybackState(id).getOrThrow()
            val metadata = repository.getPlaybackMetadata(id).getOrNull()
            val vol = repository.getGroupVolume(id).getOrThrow()
            val playMode = repository.getPlayMode(id).getOrNull()

            val playerIds = repository.getPlayerIdsForGroup(id)
            val playerVols = if (playerIds.size > 1) {
                playerIds.mapNotNull { pid ->
                    repository.getPlayerVolume(pid).getOrNull()?.let { v ->
                        PlayerVolumeEntry(pid, repository.getPlayerName(pid), v.volume, v.muted)
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
                    crossfade = playMode?.playMode?.crossfade ?: it.crossfade,
                    playerVolumes = playerVols
                )
            }
        }.onFailure { e ->
            _uiState.update { it.copy(isLoading = false, error = e.message) }
        }
    }

    fun togglePlayPause() {
        val id = _groupId.value ?: return
        viewModelScope.launch {
            runCatching { repository.togglePlayPause(id) }
            delay(300L)
            refresh()
        }
    }

    fun skipToNextTrack() {
        val id = _groupId.value ?: return
        viewModelScope.launch {
            runCatching { repository.skipToNextTrack(id) }
            delay(300L)
            refresh()
        }
    }

    fun skipToPreviousTrack() {
        val id = _groupId.value ?: return
        viewModelScope.launch {
            runCatching { repository.skipToPreviousTrack(id) }
            delay(300L)
            refresh()
        }
    }

    fun seekBy(deltaMillis: Long) {
        _groupId.value ?: return
        val state = _uiState.value
        val elapsed = if (state.playbackState == "PLAYBACK_STATE_PLAYING")
            System.currentTimeMillis() - state.positionUpdatedAt else 0L
        val current = state.positionMillis + elapsed
        val target = (current + deltaMillis).coerceIn(0, state.durationMillis)
        _uiState.update { it.copy(positionMillis = target, positionUpdatedAt = System.currentTimeMillis()) }
        val id = _groupId.value ?: return
        viewModelScope.launch {
            runCatching { repository.seek(id, target) }
            delay(300L)
            refresh()
        }
    }

    fun toggleMute() {
        val id = _groupId.value ?: return
        val newMuted = !_uiState.value.isMuted
        _uiState.update { it.copy(isMuted = newMuted) }
        viewModelScope.launch {
            runCatching { repository.setGroupMute(id, newMuted) }
        }
    }

    fun adjustVolume(delta: Int) {
        val id = _groupId.value ?: return
        val newVol = (_uiState.value.volume + delta).coerceIn(0, 100)
        _uiState.update { it.copy(volume = newVol) }
        volumeDebounceJob?.cancel()
        volumeDebounceJob = viewModelScope.launch {
            delay(300L)
            runCatching { repository.setGroupVolume(id, newVol) }
        }
    }

    fun toggleShuffle() = updatePlayMode { it.copy(shuffle = !it.shuffle) }

    fun cycleRepeat() = updatePlayMode { mode ->
        val next = when (mode.repeat) {
            "REPEAT_NONE" -> "REPEAT_ALL"
            "REPEAT_ALL" -> "REPEAT_ONE"
            else -> "REPEAT_NONE"
        }
        mode.copy(repeat = next)
    }

    fun toggleCrossfade() = updatePlayMode { it.copy(crossfade = !it.crossfade) }

    private fun updatePlayMode(transform: (PlayModeState) -> PlayModeState) {
        val id = _groupId.value ?: return
        val cur = _uiState.value
        val newMode = transform(PlayModeState(repeat = cur.repeat, shuffle = cur.shuffle, crossfade = cur.crossfade))
        _uiState.update { it.copy(repeat = newMode.repeat, shuffle = newMode.shuffle, crossfade = newMode.crossfade) }
        viewModelScope.launch { runCatching { repository.setPlayMode(id, newMode) } }
    }

    fun adjustPlayerVolume(playerId: String, delta: Int) {
        _groupId.value ?: return
        val entry = _uiState.value.playerVolumes.find { it.playerId == playerId } ?: return
        val newVol = (entry.volume + delta).coerceIn(0, 100)
        _uiState.update {
            it.copy(playerVolumes = it.playerVolumes.map { e ->
                if (e.playerId == playerId) e.copy(volume = newVol) else e
            })
        }
        playerVolumeDebounceJobs[playerId]?.cancel()
        playerVolumeDebounceJobs[playerId] = viewModelScope.launch {
            delay(300L)
            runCatching { repository.setPlayerVolume(playerId, newVol) }
        }
    }

    fun togglePlayerMute(playerId: String) {
        _groupId.value ?: return
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

    fun setSleepTimer(minutes: Int) {
        val groupId = _groupId.value ?: return
        sleepTimerJob?.cancel()
        val endMs = System.currentTimeMillis() + minutes * 60_000L
        _uiState.update { it.copy(sleepTimerRemainingMillis = minutes * 60_000L) }
        sleepTimerJob = viewModelScope.launch {
            while (isActive) {
                val remaining = endMs - System.currentTimeMillis()
                if (remaining <= 0) {
                    _uiState.update { it.copy(sleepTimerRemainingMillis = null) }
                    sleepTimerJob = null
                    if (_uiState.value.playbackState == "PLAYBACK_STATE_PLAYING") {
                        runCatching { repository.togglePlayPause(groupId) }
                        delay(300L)
                        refresh()
                    }
                    break
                }
                _uiState.update { it.copy(sleepTimerRemainingMillis = remaining) }
                delay(1_000L)
            }
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _uiState.update { it.copy(sleepTimerRemainingMillis = null) }
    }
}
