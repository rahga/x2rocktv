package com.rahga.x2rock.viewmodel

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rahga.x2rock.model.PlayModeState
import com.rahga.x2rock.model.PlaybackStates
import com.rahga.x2rock.model.RepeatModes
import com.rahga.x2rock.model.isPlaying
import com.rahga.x2rock.repository.SonosRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    val playbackState: String = PlaybackStates.IDLE,
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
    val repeat: String = RepeatModes.NONE,
    val crossfade: Boolean = false,
    val playerVolumes: List<PlayerVolumeEntry> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val sleepTimerRemainingMillis: Long? = null
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val repository: SonosRepository,
    @ApplicationContext context: Context
) : ViewModel() {

    private val _groupId = MutableStateFlow<String?>(null)
    private val pollingActive = MutableStateFlow(false)
    private var volumeDebounceJob: Job? = null
    private val playerVolumeDebounceJobs = mutableMapOf<String, Job>()
    private var sleepTimerJob: Job? = null

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var lastMetadataKey = ""
    private var lastPbStateCode = -1
    private var lastPbPositionMillis = -1L

    private val mediaSession = MediaSession(context, "x2rock").also { session ->
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { togglePlayPause() }
            override fun onPause() { togglePlayPause() }
            override fun onSkipToNext() { skipToNextTrack() }
            override fun onSkipToPrevious() { skipToPreviousTrack() }
            override fun onSeekTo(pos: Long) {
                val state = _uiState.value
                val elapsed = if (state.playbackState.isPlaying())
                    System.currentTimeMillis() - state.positionUpdatedAt else 0L
                seekBy(pos - state.positionMillis - elapsed)
            }
        })
        session.isActive = true
    }

    init {
        viewModelScope.launch {
            _uiState.collect { updateMediaSession(it) }
        }
        viewModelScope.launch {
            combine(_groupId, pollingActive) { id, active -> if (active) id else null }
                .distinctUntilChanged()
                .collectLatest { id ->
                    if (id != null) pollLoop(POLL_INTERVAL_MILLIS, ::onRefreshFailed) { refresh(id) }
                }
        }
    }

    /** Driven by the nav graph's lifecycle observer so nothing polls while the app is hidden. */
    fun setPollingActive(active: Boolean) {
        pollingActive.value = active
    }

    private fun updateMediaSession(state: PlayerUiState) {
        if (state.isLoading) return

        val metadataKey = "${state.trackName}|${state.artistName}|${state.albumName}|${state.durationMillis}"
        if (metadataKey != lastMetadataKey) {
            lastMetadataKey = metadataKey
            mediaSession.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, state.trackName ?: "")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, state.artistName ?: "")
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, state.albumName ?: "")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationMillis)
                    .build()
            )
        }

        val pbState = if (state.trackName == null) PlaybackState.STATE_NONE
            else if (state.playbackState.isPlaying()) PlaybackState.STATE_PLAYING
            else PlaybackState.STATE_PAUSED
        if (pbState == lastPbStateCode && state.positionMillis == lastPbPositionMillis) return
        lastPbStateCode = pbState
        lastPbPositionMillis = state.positionMillis
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_SEEK_TO
                )
                .setState(pbState, state.positionMillis, 1f)
                .build()
        )
    }

    override fun onCleared() {
        mediaSession.release()
        super.onCleared()
    }

    fun selectGroup(id: String, name: String) {
        if (_groupId.value == id) {
            // Same room, but the name may only just have resolved (deep link, or groups still loading).
            if (_uiState.value.groupName != name) _uiState.update { it.copy(groupName = name) }
            return
        }
        lastMetadataKey = ""
        lastPbStateCode = -1
        lastPbPositionMillis = -1L
        _uiState.value = PlayerUiState(groupName = name, isLoading = true)
        cancelSleepTimer()
        // Set last: this is what restarts the poll loop, and it must see the reset state.
        _groupId.value = id
    }

    private suspend fun refresh(id: String) = coroutineScope {
        // These are independent endpoints; issuing them serially cost one round trip each,
        // every tick, and a grouped room added one more per speaker on top.
        val playbackDeferred = async { repository.getPlaybackState(id) }
        val metadataDeferred = async { repository.getPlaybackMetadata(id) }
        val volumeDeferred = async { repository.getGroupVolume(id) }
        val playModeDeferred = async { repository.getPlayMode(id) }

        val playerIds = repository.getPlayerIdsForGroup(id)
        val playerVolumeDeferreds = if (playerIds.size > 1) {
            playerIds.map { pid -> async { pid to repository.getPlayerVolume(pid).getOrNull() } }
        } else {
            emptyList()
        }

        val playback = playbackDeferred.await().getOrThrow()
        val metadata = metadataDeferred.await().getOrNull()
        val vol = volumeDeferred.await().getOrThrow()
        val playMode = playModeDeferred.await().getOrNull()
        val playerVols = playerVolumeDeferreds.awaitAll().mapNotNull { (pid, volume) ->
            volume?.let { PlayerVolumeEntry(pid, repository.getPlayerName(pid), it.volume, it.muted) }
        }

        val track = metadata?.currentItem?.track
        _uiState.update {
            it.copy(
                isLoading = false,
                error = null,
                playbackState = playback.playbackState,
                trackName = track?.name,
                artistName = track?.artist?.name,
                albumName = track?.album?.name,
                albumArtUrl = track?.imageUrl,
                durationMillis = track?.durationMillis ?: 0,
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
    }

    private fun onRefreshFailed(e: Throwable) {
        _uiState.update { it.copy(isLoading = false, error = e.message) }
    }

    /** Post-command re-read: a failure here shouldn't blank the screen, the poll will catch up. */
    private suspend fun refreshQuietly(id: String) {
        try {
            refresh(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onRefreshFailed(e)
        }
    }

    fun togglePlayPause() {
        val id = _groupId.value ?: return
        viewModelScope.launch {
            repository.togglePlayPause(id)
            delay(300L)
            refreshQuietly(id)
        }
    }

    fun skipToNextTrack() {
        val id = _groupId.value ?: return
        viewModelScope.launch {
            repository.skipToNextTrack(id)
            delay(300L)
            refreshQuietly(id)
        }
    }

    fun skipToPreviousTrack() {
        val id = _groupId.value ?: return
        viewModelScope.launch {
            repository.skipToPreviousTrack(id)
            delay(300L)
            refreshQuietly(id)
        }
    }

    fun seekBy(deltaMillis: Long) {
        val id = _groupId.value ?: return
        val state = _uiState.value
        val elapsed = if (state.playbackState.isPlaying())
            System.currentTimeMillis() - state.positionUpdatedAt else 0L
        val current = state.positionMillis + elapsed
        val target = (current + deltaMillis).coerceIn(0, state.durationMillis)
        _uiState.update { it.copy(positionMillis = target, positionUpdatedAt = System.currentTimeMillis()) }
        viewModelScope.launch {
            repository.seek(id, target)
            delay(300L)
            refreshQuietly(id)
        }
    }

    fun toggleMute() {
        val id = _groupId.value ?: return
        val newMuted = !_uiState.value.isMuted
        _uiState.update { it.copy(isMuted = newMuted) }
        viewModelScope.launch {
            repository.setGroupMute(id, newMuted)
        }
    }

    fun adjustVolume(delta: Int) {
        val id = _groupId.value ?: return
        val newVol = (_uiState.value.volume + delta).coerceIn(0, 100)
        _uiState.update { it.copy(volume = newVol) }
        volumeDebounceJob?.cancel()
        volumeDebounceJob = viewModelScope.launch {
            delay(300L)
            repository.setGroupVolume(id, newVol)
        }
    }

    fun toggleShuffle() = updatePlayMode { it.copy(shuffle = !it.shuffle) }

    fun cycleRepeat() = updatePlayMode { mode ->
        val next = when (mode.repeat) {
            RepeatModes.NONE -> RepeatModes.ALL
            RepeatModes.ALL -> RepeatModes.ONE
            else -> RepeatModes.NONE
        }
        mode.copy(repeat = next)
    }

    fun toggleCrossfade() = updatePlayMode { it.copy(crossfade = !it.crossfade) }

    private fun updatePlayMode(transform: (PlayModeState) -> PlayModeState) {
        val id = _groupId.value ?: return
        val cur = _uiState.value
        val newMode = transform(PlayModeState(repeat = cur.repeat, shuffle = cur.shuffle, crossfade = cur.crossfade))
        _uiState.update { it.copy(repeat = newMode.repeat, shuffle = newMode.shuffle, crossfade = newMode.crossfade) }
        viewModelScope.launch { repository.setPlayMode(id, newMode) }
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
            repository.setPlayerVolume(playerId, newVol)
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
            repository.setPlayerMute(playerId, newMuted)
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
                    if (_uiState.value.playbackState.isPlaying()) {
                        repository.togglePlayPause(groupId)
                        delay(300L)
                        refreshQuietly(groupId)
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

    private companion object {
        const val POLL_INTERVAL_MILLIS = 5_000L
    }
}
