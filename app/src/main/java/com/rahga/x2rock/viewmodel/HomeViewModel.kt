package com.rahga.x2rock.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rahga.x2rock.auth.PendingRoomDeepLink
import com.rahga.x2rock.auth.RoomPreferencesStore
import com.rahga.x2rock.auth.ThemeStore
import com.rahga.x2rock.channel.ChannelSync
import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.lan.TvSoundbar
import com.rahga.x2rock.model.AppColorTheme
import com.rahga.x2rock.model.Group
import com.rahga.x2rock.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Sidebar order: the pinned room first, then favourites, then the rest, each alphabetical.
 * Pure, so the ordering is testable without standing the view model up.
 *
 * The television's own room is deliberately *not* hoisted here. Sonos lists rooms
 * alphabetically and offers no ordering of its own, so reordering would make this list
 * disagree with every other controller in the house for no gain. The TV room is where the
 * app *opens* instead — see the selection below.
 */
fun sortGroups(
    groups: List<Group>,
    primaryId: String?,
    favoriteIds: Set<String>,
): List<Group> {
    val pinned = groups.firstOrNull { it.id == primaryId }
    val pinnedId = pinned?.id
    val favorites = groups.filter { it.id != pinnedId && it.id in favoriteIds }.sortedBy { it.name }
    val rest = groups.filter { it.id != pinnedId && it.id !in favoriteIds }.sortedBy { it.name }
    return listOfNotNull(pinned) + favorites + rest
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val household: SonosHousehold,
    private val themeStore: ThemeStore,
    private val roomPrefsStore: RoomPreferencesStore,
    private val channelSync: ChannelSync,
    private val pendingRoomDeepLink: PendingRoomDeepLink
) : ViewModel() {

    /**
     * Everything one row of the room list shows.
     *
     * A soundbar on its TV input is three lines rather than two — the room, then what the
     * HDMI is carrying ("Dolby Digital Surround 5.1"), then the source ("TV Audio") — so
     * the row needs more than a track.
     */
    data class RoomInfo(
        val track: Track? = null,
        /** Track art, falling back to the container's: radio has a station logo, not a cover. */
        val artUrl: String? = null,
        val onTvInput: Boolean = false,
        /** e.g. "Dolby Digital Surround 5.1"; empty unless on a TV input with a signal. */
        val inputFormat: String = "",
        /** Whether this room has an HDMI input at all, from any of its speakers. */
        val hasTvInput: Boolean = false,
        /** The container: an album, a station, or "TV Audio". */
        val source: String? = null,
    )

    sealed interface UiState {
        data object Loading : UiState
        data class Success(val groups: List<Group>, val rooms: Map<String, RoomInfo>) : UiState {
            /** The TV home-screen channels only care about what is playing. */
            val nowPlaying: Map<String, Track?> get() = rooms.mapValues { it.value.track }
        }
        data class Error(val message: String) : UiState
    }

    /**
     * Derived, not polled. Both sources are pushed by the speakers, so this recomputes when
     * something actually changes and at no other time.
     */
    val uiState: StateFlow<UiState> =
        combine(household.state, household.groupStates) { state, groupStates ->
            val error = state.error
            when {
                error != null -> UiState.Error(error)
                !state.connected -> UiState.Loading
                else -> UiState.Success(
                    groups = state.groups,
                    rooms = state.groups.associate { group ->
                        val pushed = groupStates[group.id]
                        group.id to RoomInfo(
                            track = pushed?.track,
                            artUrl = pushed?.track?.imageUrl ?: pushed?.container?.imageUrl,
                            onTvInput = pushed?.onTvInput == true,
                            inputFormat = pushed?.inputFormat.orEmpty(),
                            hasTvInput = state.hasTvInput(group),
                            source = pushed?.container?.name,
                        )
                    },
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading)

    // What a press just asked for, per speaker, until the player confirms it. Without this
    // the level would snap back to the pushed one between the press and the event, and a
    // second press inside the debounce window would aim from the stale value — the same bug
    // the group volume had, one scope down.
    private val _pendingPlayerVolumes = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val playerVolumeJobs = mutableMapOf<String, Job>()

    /**
     * Each speaker's own level, for the room panel's "playing together" list.
     *
     * Pushed off `playerVolume:1`, so it follows someone turning a speaker up from the
     * Sonos app rather than needing a re-read. Overlaid with whatever a press just asked
     * for, until the speaker confirms it — see [adjustPlayerVolume].
     */
    val playerVolumes: StateFlow<Map<String, Int>> =
        combine(household.playerVolumes, _pendingPlayerVolumes) { pushed, pending ->
            pushed.mapValues { it.value.volume } + pending
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val selectedTheme: StateFlow<AppColorTheme> = themeStore.theme
    val primaryRoomId: StateFlow<String?> = roomPrefsStore.primaryRoomId
    val tvPlayerId: StateFlow<String?> = roomPrefsStore.tvPlayerId
    val favoriteRoomIds: StateFlow<Set<String>> = roomPrefsStore.favoriteRoomIds

    private val _selectedGroupId = MutableStateFlow<String?>(null)
    val selectedGroupId: StateFlow<String?> = _selectedGroupId.asStateFlow()

    private val _sidebarVisible = MutableStateFlow(true)
    val sidebarVisible: StateFlow<Boolean> = _sidebarVisible.asStateFlow()

    /** The speakers of the last selection, so it can be found again after a regroup. */
    @Volatile private var lastSelectedPlayers: Set<String> = emptySet()

    private val _navigateToRoom = MutableStateFlow(false)
    val navigateToRoom: StateFlow<Boolean> = _navigateToRoom.asStateFlow()

    init {
        viewModelScope.launch {
            pendingRoomDeepLink.groupId.collect { groupId ->
                if (groupId != null) {
                    _selectedGroupId.value = groupId
                    _navigateToRoom.value = true
                    pendingRoomDeepLink.clear()
                }
            }
        }
        // Keep a room selected, including through regroupings this app did not perform.
        viewModelScope.launch {
            household.state.map { it.groups }.distinctUntilChanged().collect { groups ->
                if (groups.isEmpty()) return@collect
                val current = _selectedGroupId.value
                if (current != null && groups.any { it.id == current }) return@collect

                // A regroup mints new group ids, so the selected one can simply cease to
                // exist — anything else on the network can do that at any moment. Follow
                // the speakers rather than the id: whichever group now holds them is the
                // same room to a listener, even though it is a different group.
                val followed = lastSelectedPlayers
                    .takeIf { it.isNotEmpty() }
                    ?.let { players -> groups.firstOrNull { g -> g.playerIds.any { it in players } } }

                _selectedGroupId.value = (
                    followed
                        ?: TvSoundbar.groupOf(roomPrefsStore.tvPlayerId.value, groups)
                        ?: defaultSelection(groups)
                    )?.id
            }
        }

        // Remembered so a vanished selection can be followed to wherever its speakers went.
        viewModelScope.launch {
            combine(household.state, _selectedGroupId) { state, id ->
                state.groups.firstOrNull { it.id == id }?.playerIds.orEmpty()
            }.collect { players -> if (players.isNotEmpty()) lastSelectedPlayers = players.toSet() }
        }
        // Which soundbar this television feeds, learned once and then remembered. Only
        // detectable while that room is on its TV input, so this watches rather than asking
        // at startup — and never overwrites an answer, because a second television coming on
        // makes the signal ambiguous rather than wrong.
        viewModelScope.launch {
            combine(household.state, household.groupStates) { state, groupStates ->
                TvSoundbar.detect(state, groupStates)
            }.collect { detected ->
                if (detected == null || roomPrefsStore.tvPlayerId.value != null) return@collect
                roomPrefsStore.setTvPlayer(detected)

                // On a first run the room was picked before this was known, so move to the
                // television's — but only if the selection is still the one this code chose
                // for itself. Intent cannot be tracked through `selectGroup`, because the
                // sidebar selects on *focus* and focusing the current row programmatically
                // looks identical to a viewer pressing towards it.
                val groups = household.state.value.groups
                val untouched = _selectedGroupId.value == defaultSelection(groups)?.id
                if (untouched) {
                    TvSoundbar.groupOf(detected, groups)?.let { _selectedGroupId.value = it.id }
                }
            }
        }

        // The TV home-screen channels follow whatever the household last said.
        viewModelScope.launch(Dispatchers.IO) {
            uiState.collect { state ->
                if (state is UiState.Success) channelSync.sync(state.groups, state.nowPlaying)
            }
        }
    }

    /**
     * Connects on the way in. Previously this gated a poll loop; now it is the connection
     * itself, and staying connected while hidden is what keeps state warm for the next
     * frame rather than something to be avoided.
     */
    fun setActive(active: Boolean) {
        if (!active) return
        viewModelScope.launch {
            runCatching { household.connect() }
        }
    }

    fun clearNavigateToRoom() { _navigateToRoom.value = false }

    fun setTheme(theme: AppColorTheme) = themeStore.setTheme(theme)

    fun selectGroup(id: String) { _selectedGroupId.value = id }

    fun toggleSidebar() { _sidebarVisible.value = !_sidebarVisible.value }

    fun setPrimaryRoom(id: String?) = roomPrefsStore.setPrimaryRoom(id)

    fun toggleFavorite(id: String) = roomPrefsStore.toggleFavorite(id)

    // Grouping changes arrive back as a groups:1 event, so none of these re-fetch.

    fun joinGroup(sourceGroupId: String, targetGroupId: String) {
        val source = findGroup(sourceGroupId) ?: return
        viewModelScope.launch {
            runCatching { household.modifyGroupMembers(targetGroupId, add = source.playerIds, remove = emptyList()) }
        }
    }

    fun soloGroup(groupId: String) {
        val group = findGroup(groupId) ?: return
        // Everything but the coordinator leaves, which is what "solo" means here.
        val others = group.playerIds.filter { it != group.coordinatorId }
        if (others.isEmpty()) return
        viewModelScope.launch {
            runCatching { household.modifyGroupMembers(groupId, add = emptyList(), remove = others) }
        }
    }

    fun removePlayerFromGroup(groupId: String, playerId: String) {
        viewModelScope.launch {
            runCatching { household.modifyGroupMembers(groupId, add = emptyList(), remove = listOf(playerId)) }
        }
    }

    fun playerNamesForGroup(group: Group): List<Pair<String, String>> =
        group.playerIds.map { it to household.playerName(it) }

    /**
     * Every other room joins [hostGroupId] and plays what it plays.
     *
     * The host is named rather than chosen, because party mode hinges on a source: the room
     * whose panel this was opened from is the one the house follows. Passing nothing keeps
     * the old behaviour of hosting from the top of the list.
     */
    fun partyMode(hostGroupId: String? = null) {
        val groups = (uiState.value as? UiState.Success)?.groups ?: return
        if (groups.size < 2) return
        val host = hostGroupId?.let { id -> groups.firstOrNull { it.id == id } }
            ?: sortGroups(
                groups,
                roomPrefsStore.primaryRoomId.value,
                roomPrefsStore.favoriteRoomIds.value,
            ).first()
        val joiners = groups.filter { it.id != host.id }.flatMap { it.playerIds }
        if (joiners.isEmpty()) return
        viewModelScope.launch {
            runCatching { household.modifyGroupMembers(host.id, add = joiners, remove = emptyList()) }
        }
    }

    /**
     * Override whatever this room is playing with its soundbar's HDMI input.
     *
     * Nothing is observed here: the switch comes back as a metadata event like any other
     * change, so the row that offered it lights up on its own.
     */
    fun useTvInput(groupId: String) {
        viewModelScope.launch { runCatching { household.useTvInput(groupId) } }
    }

    /**
     * Say which soundbar this television is plugged into, or take it back.
     *
     * Detection is a heuristic and cannot be otherwise — Android will not say what is on the
     * far end of its HDMI — and it has been seen to answer confidently and wrongly: any
     * moment when one soundbar is on a TV input and the others are playing music looks
     * exactly like the answer. So the viewer can state it, and a stated answer wins: the
     * detector only ever fills this in while it is empty.
     *
     * Stores the **player**, never the group, because a regroup mints new group ids while
     * the soundbar stays bolted to the same television.
     */
    fun setTvSoundbar(groupId: String) {
        val group = findGroup(groupId) ?: return
        val soundbar = TvSoundbar.soundbarOf(group, household.state.value) ?: return
        roomPrefsStore.setTvPlayer(if (roomPrefsStore.tvPlayerId.value == soundbar) null else soundbar)
    }

    /** One speaker's own level, accumulating presses the way the group volume does. */
    fun adjustPlayerVolume(playerId: String, delta: Int) {
        val current = playerVolumes.value[playerId] ?: return
        val target = (current + delta).coerceIn(0, 100)
        _pendingPlayerVolumes.update { it + (playerId to target) }
        playerVolumeJobs[playerId]?.cancel()
        playerVolumeJobs[playerId] = viewModelScope.launch {
            delay(VOLUME_DEBOUNCE_MILLIS)
            runCatching { household.setPlayerVolume(playerId, target) }
            _pendingPlayerVolumes.update { it - playerId }
        }
    }

    /** What the app would choose with nothing else to go on. */
    private fun defaultSelection(groups: List<Group>): Group? = sortGroups(
        groups,
        roomPrefsStore.primaryRoomId.value,
        roomPrefsStore.favoriteRoomIds.value,
    ).firstOrNull()

    private fun findGroup(id: String): Group? =
        (uiState.value as? UiState.Success)?.groups?.find { it.id == id }

    private companion object {
        /** The same window the player pane uses, so a held key behaves the same in both. */
        const val VOLUME_DEBOUNCE_MILLIS = 300L
    }
}
