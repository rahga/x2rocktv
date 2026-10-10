package com.rahga.x2rock.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.model.PlaybackStates
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** What the full-screen view draws: the art, two lines, the room, and where the track stands. */
data class NowPlayingUi(
    val room: String = "",
    /** The headline: the track, else what the station says, else the source, else the room's state. */
    val title: String = "",
    /** The pane's line under a track — artist, album, station; empty where there is none. */
    val subtitle: String = "",
    val artUrl: String? = null,
    val onTvInput: Boolean = false,
    val isRadio: Boolean = false,
    val playbackState: String = PlaybackStates.IDLE,
    val positionMillis: Long = 0,
    val positionUpdatedAt: Long = 0,
    val durationMillis: Long = 0,
    /** The room has left the household; the screen has nothing to show and goes back. */
    val gone: Boolean = false,
)

/**
 * The full-screen now-playing view's room. Its own, rather than the pane's [PlayerViewModel]: that
 * one is told its room by the home screen, which is not composed while this is shown, so across a
 * regroup it would go on reading a group id that no longer exists. [RoomTarget] follows the room
 * by its speakers instead, as every other screen opened on a room does.
 */
@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    household: SonosHousehold,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val room = RoomTarget(household, checkNotNull(savedStateHandle["groupId"]))

    val uiState: StateFlow<NowPlayingUi> =
        combine(household.state, household.groupStates) { householdState, states ->
            val groupId = room.currentOrNull() ?: return@combine NowPlayingUi(gone = householdState.connected)
            val group = householdState.groups.firstOrNull { it.id == groupId }
            val state = states[groupId] ?: return@combine NowPlayingUi(room = group?.name.orEmpty())
            val track = state.track
            // The pane's rule for the line under a title, so the two never word a track differently.
            val lines = PlayerUiState(
                trackName = track?.name,
                artistName = track?.artist?.name,
                albumName = track?.album?.name,
                sourceName = state.container?.name,
                streamInfo = state.streamInfo,
                isRadio = state.isRadio,
                onTvInput = state.onTvInput,
                playbackState = state.playbackState,
            )
            NowPlayingUi(
                room = group?.name.orEmpty(),
                title = track?.name ?: state.streamInfo ?: state.container?.name
                    ?: if (state.onTvInput) "TV" else roomActivity(state.playbackState, lines.hasSource).label,
                subtitle = when {
                    track?.name != null -> lines.trackLine()
                    // A stream's own text is the headline; the station names it underneath.
                    state.streamInfo != null -> state.container?.name.orEmpty()
                    state.onTvInput -> state.inputFormat
                    else -> ""
                },
                artUrl = state.artUrl,
                onTvInput = state.onTvInput,
                isRadio = state.isRadio,
                playbackState = state.playbackState,
                positionMillis = state.positionMillis,
                positionUpdatedAt = state.positionUpdatedAt,
                durationMillis = state.durationMillis,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, NowPlayingUi())
}
