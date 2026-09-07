package com.rahga.x2rock.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.media.NowPlayingPublisher
import com.rahga.x2rock.model.PlayModeState
import com.rahga.x2rock.model.PlaybackStates
import com.rahga.x2rock.model.RepeatModes
import com.rahga.x2rock.model.hasLoadedContent
import com.rahga.x2rock.model.isPlaying
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
    /**
     * Null until the group's `groupVolume:1` snapshot arrives — which is a moment or two
     * after launch, and is not the same thing as zero. Rendering it as 0 in the meantime
     * both told the user something false and made a Vol+ press compute from 0, quietly
     * turning a speaker *down*.
     */
    val volume: Int? = null,
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
    private val household: SonosHousehold,
    private val nowPlaying: NowPlayingPublisher,
) : ViewModel() {

    private val _groupId = MutableStateFlow<String?>(null)
    private val _groupName = MutableStateFlow("")
    private val _sleepRemaining = MutableStateFlow<Long?>(null)

    private var volumeDebounceJob: Job? = null
    private val playerVolumeDebounceJobs = mutableMapOf<String, Job>()
    private var sleepTimerJob: Job? = null
    private var seekDebounceJob: Job? = null

    // What the last press asked for, before the speaker has said anything back.
    //
    // Necessary because uiState is now purely pushed: without it, five volume presses
    // inside the debounce window all read the same unchanged pushed value and the speaker
    // moves one step instead of five. Cleared once the command has gone.
    private var pendingVolume: Int? = null
    private var pendingSeekMillis: Long? = null
    private val pendingPlayerVolumes = mutableMapOf<String, Int>()

    private var lastMetadataKey = ""
    private var lastPbStateCode = -1
    private var lastPbPositionMillis = -1L

    /**
     * Everything visible is derived from pushed state. There is no refresh: a command's
     * effect arrives as an event, which is why nothing here waits 300ms and re-reads.
     */
    val uiState: StateFlow<PlayerUiState> =
        combine(
            _groupId,
            _groupName,
            household.state,
            household.groupStates,
            household.playerVolumes,
        ) { groupId, groupName, householdState, groupStates, playerVolumes ->
            val group = householdState.groups.firstOrNull { it.id == groupId }
            val state = groupStates[groupId]
            if (groupId == null || state == null) {
                // A teardown clears groupStates, so this is also the "connection lost"
                // branch — carry the error, or a failing household spins forever.
                PlayerUiState(
                    groupName = groupName,
                    isLoading = householdState.error == null && !householdState.connected,
                    error = householdState.error,
                )
            } else {
                PlayerUiState(
                    groupName = group?.name ?: groupName,
                    playbackState = state.playbackState,
                    trackName = state.track?.name,
                    artistName = state.track?.artist?.name,
                    albumName = state.track?.album?.name,
                    // Radio has no per-track art but the station has a logo, which is what
                    // a listener recognises — so fall back to the container rather than
                    // showing an empty pane.
                    albumArtUrl = state.track?.imageUrl ?: state.container?.imageUrl,
                    positionMillis = state.positionMillis,
                    durationMillis = state.durationMillis,
                    positionUpdatedAt = state.positionUpdatedAt,
                    volume = state.volume?.volume,
                    isMuted = state.volume?.muted ?: false,
                    shuffle = state.playMode.shuffle,
                    repeat = state.playMode.repeat,
                    crossfade = state.playMode.crossfade,
                    // Per-speaker rows only mean anything once a group has more than one.
                    playerVolumes = group?.playerIds
                        ?.takeIf { it.size > 1 }
                        ?.mapNotNull { id ->
                            playerVolumes[id]?.let {
                                PlayerVolumeEntry(id, household.playerName(id), it.volume, it.muted)
                            }
                        }
                        .orEmpty(),
                    isLoading = false,
                    error = householdState.error,
                )
            }
        }.combine(_sleepRemaining) { state, remaining ->
            state.copy(sleepTimerRemainingMillis = remaining)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, PlayerUiState())

    private val controls = object : NowPlayingPublisher.Controls {
        override fun togglePlayPause() = this@PlayerViewModel.togglePlayPause()
        override fun next() = skipToNextTrack()
        override fun previous() = skipToPreviousTrack()
        override fun seekTo(positionMillis: Long) {
            val state = uiState.value
            val elapsed = if (state.playbackState.isPlaying())
                System.currentTimeMillis() - state.positionUpdatedAt else 0L
            seekBy(positionMillis - state.positionMillis - elapsed)
        }
    }

    init {
        nowPlaying.attach(controls)
    }

    init {
        viewModelScope.launch { uiState.collect { publish(it) } }
    }

    fun selectGroup(id: String, name: String) {
        _groupId.value = id
        _groupName.value = name
    }

    /** Metadata and state are pushed separately, and only when they actually change. */
    private fun publish(state: PlayerUiState) {
        if (state.isLoading) return

        val metadataKey = "${state.trackName}|${state.artistName}|${state.albumName}|${state.durationMillis}"
        if (metadataKey != lastMetadataKey) {
            lastMetadataKey = metadataKey
            nowPlaying.publish(state.trackName, state.artistName, state.albumName, state.durationMillis)
        }

        val playing = state.playbackState.isPlaying()
        // Idle is a playback state, not the absence of a title. A soundbar on TV audio has
        // no track metadata and is still playing.
        val idle = !state.playbackState.hasLoadedContent()
        val code = if (idle) 0 else if (playing) 1 else 2
        if (code == lastPbStateCode && state.positionMillis == lastPbPositionMillis) return
        lastPbStateCode = code
        lastPbPositionMillis = state.positionMillis
        nowPlaying.publishState(playing, idle, state.positionMillis)
    }

    // ------------------------------------------------------------ transport
    //
    // Fire and forget. The player answers with an event, and the flows above pick it up.

    fun togglePlayPause() = command { household.togglePlayPause(it) }
    fun skipToNextTrack() = command { household.skipToNextTrack(it) }
    fun skipToPreviousTrack() = command { household.skipToPreviousTrack(it) }

    /** Debounced and accumulating, so holding skip moves once by the total, not once by one step. */
    fun seekBy(deltaMillis: Long) {
        val groupId = _groupId.value ?: return
        val state = uiState.value
        val base = pendingSeekMillis ?: run {
            val elapsed = if (state.playbackState.isPlaying())
                System.currentTimeMillis() - state.positionUpdatedAt else 0L
            state.positionMillis + elapsed
        }
        val target = (base + deltaMillis)
            .coerceIn(0, state.durationMillis.takeIf { it > 0 } ?: Long.MAX_VALUE)
        pendingSeekMillis = target
        seekDebounceJob?.cancel()
        seekDebounceJob = viewModelScope.launch {
            delay(VOLUME_DEBOUNCE_MILLIS)
            runCatching { household.seek(groupId, target) }
            pendingSeekMillis = null
        }
    }

    fun toggleMute() {
        if (uiState.value.volume == null) return
        val muted = !uiState.value.isMuted
        command { household.setGroupMute(it, muted) }
    }

    /** Debounced: a held D-pad key would otherwise send one command per repeat. */
    fun adjustVolume(delta: Int) {
        val groupId = _groupId.value ?: return
        // Nothing to adjust relative to until the speaker has told us where it is.
        val current = pendingVolume ?: uiState.value.volume ?: return
        val target = (current + delta).coerceIn(0, 100)
        pendingVolume = target
        volumeDebounceJob?.cancel()
        volumeDebounceJob = viewModelScope.launch {
            delay(VOLUME_DEBOUNCE_MILLIS)
            runCatching { household.setGroupVolume(groupId, target) }
            pendingVolume = null
        }
    }

    fun toggleShuffle() = updatePlayMode { it.copy(shuffle = !it.shuffle) }

    fun cycleRepeat() = updatePlayMode { mode ->
        mode.copy(
            repeat = when (mode.repeat) {
                RepeatModes.NONE -> RepeatModes.ALL
                RepeatModes.ALL -> RepeatModes.ONE
                else -> RepeatModes.NONE
            }
        )
    }

    fun toggleCrossfade() = updatePlayMode { it.copy(crossfade = !it.crossfade) }

    private fun updatePlayMode(transform: (PlayModeState) -> PlayModeState) {
        val groupId = _groupId.value ?: return
        val current = PlayModeState(
            repeat = uiState.value.repeat,
            shuffle = uiState.value.shuffle,
            crossfade = uiState.value.crossfade,
        )
        viewModelScope.launch { runCatching { household.setPlayMode(groupId, transform(current)) } }
    }

    fun adjustPlayerVolume(playerId: String, delta: Int) {
        val current = uiState.value.playerVolumes.firstOrNull { it.playerId == playerId } ?: return
        val target = ((pendingPlayerVolumes[playerId] ?: current.volume) + delta).coerceIn(0, 100)
        pendingPlayerVolumes[playerId] = target
        playerVolumeDebounceJobs[playerId]?.cancel()
        playerVolumeDebounceJobs[playerId] = viewModelScope.launch {
            delay(VOLUME_DEBOUNCE_MILLIS)
            runCatching { household.setPlayerVolume(playerId, target) }
            pendingPlayerVolumes.remove(playerId)
        }
    }

    fun togglePlayerMute(playerId: String) {
        val current = uiState.value.playerVolumes.firstOrNull { it.playerId == playerId } ?: return
        viewModelScope.launch { runCatching { household.setPlayerMute(playerId, !current.muted) } }
    }

    // ------------------------------------------------------------ sleep timer

    fun setSleepTimer(minutes: Int) {
        val groupId = _groupId.value ?: return
        sleepTimerJob?.cancel()
        val endMs = System.currentTimeMillis() + minutes * 60_000L
        _sleepRemaining.value = minutes * 60_000L
        sleepTimerJob = viewModelScope.launch {
            while (isActive) {
                val remaining = endMs - System.currentTimeMillis()
                if (remaining <= 0) {
                    _sleepRemaining.value = null
                    sleepTimerJob = null
                    if (uiState.value.playbackState.isPlaying()) {
                        runCatching { household.pause(groupId) }
                    }
                    break
                }
                _sleepRemaining.value = remaining
                delay(1_000L)
            }
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepRemaining.value = null
    }

    override fun onCleared() {
        // Detach, not release: the publisher is application-scoped and outlives this.
        nowPlaying.detach(controls)
        super.onCleared()
    }

    private fun command(block: suspend (String) -> Unit) {
        val groupId = _groupId.value ?: return
        viewModelScope.launch { runCatching { block(groupId) } }
    }

    private companion object {
        const val VOLUME_DEBOUNCE_MILLIS = 300L
    }
}
