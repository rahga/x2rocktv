package com.rahga.x2rock.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rahga.x2rock.auth.PendingRoomDeepLink
import com.rahga.x2rock.auth.RoomPreferencesStore
import com.rahga.x2rock.auth.ThemeStore
import com.rahga.x2rock.channel.ChannelSync
import com.rahga.x2rock.lan.HouseholdChoice
import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.lan.TvSoundbar
import com.rahga.x2rock.lan.Upnp
import com.rahga.x2rock.model.AppColorTheme
import com.rahga.x2rock.model.toPlaybackLabel
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/**
 * Sidebar order: Sonos's own, which is alphabetical and nothing else.
 *
 * Sonos sorts rooms alphabetically and offers no ordering of its own — it has playback, EQ
 * and home-theatre settings but nothing positional — so there is no shared order to match
 * and nothing to reorder *to*. Hoisting a room here would make this list disagree with every
 * other controller in the house for no gain.
 *
 * There were two hoists once. Favourites went first, then a pinned "primary room", which had
 * quietly become the second of two answers to "which room does this device belong to" — and
 * the louder one: setting it stopped [TvSoundbar] detection from ever choosing where the app
 * opens, because that only moves a selection still equal to the default. Naming the
 * television's room says the same thing better, and without disagreeing with Sonos.
 *
 * Kept as a function rather than inlined so the decision has somewhere to live.
 */
fun sortGroups(groups: List<Group>): List<Group> = groups.sortedBy { it.name }

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
        /** The group's volume is muted. A state, so the room list shows it as one. */
        val muted: Boolean = false,
        /** Bonded speakers of this group's rooms that have dropped off: a Sub, a surround. */
        val offlineSpeakers: Int = 0,
        /** e.g. "Dolby Digital Surround 5.1"; empty unless on a TV input with a signal. */
        val inputFormat: String = "",
        /** Whether this room has an HDMI input at all, from any of its speakers. */
        val hasTvInput: Boolean = false,
        /** The container: an album, a station, or "TV Audio". */
        val source: String? = null,
        /**
         * What the station says is playing, for a stream that carries no track of its own.
         * See `PlaybackMetadata.streamInfo`.
         */
        val streamInfo: String? = null,
        /** A station rather than a queue of tracks — it gets a radio glyph for its art. */
        val isRadio: Boolean = false,
        /** An alarm is ringing here: see `GroupState.ringingAlarm`. */
        val alarmRinging: Boolean = false,
    )

    sealed interface UiState {
        /** Not connected yet — or, with [reconnecting], again, after the speaker it ran through went. */
        data class Loading(val reconnecting: Boolean = false) : UiState
        data class Success(val groups: List<Group>, val rooms: Map<String, RoomInfo>) : UiState {
            /** The TV home-screen channels only care about what is playing. */
            val nowPlaying: Map<String, Track?> get() = rooms.mapValues { it.value.track }
        }
        /** [choices] is non-empty when the "error" is two households, and the answer is a pick. */
        data class Error(val message: String, val choices: List<HouseholdChoice> = emptyList()) : UiState
    }

    /**
     * Derived, not polled. Both sources are pushed by the speakers, so this recomputes when
     * something actually changes and at no other time.
     */
    val uiState: StateFlow<UiState> =
        combine(household.state, household.groupStates) { state, groupStates ->
            val error = state.error
            when {
                error != null -> UiState.Error(error, state.householdChoices)
                !state.connected -> UiState.Loading(reconnecting = state.reconnecting)
                else -> UiState.Success(
                    groups = state.groups,
                    rooms = state.groups.associate { group ->
                        val pushed = groupStates[group.id]
                        group.id to RoomInfo(
                            track = pushed?.track,
                            artUrl = pushed?.track?.imageUrl ?: pushed?.container?.imageUrl,
                            onTvInput = pushed?.onTvInput == true,
                            muted = pushed?.volume?.muted == true,
                            offlineSpeakers = group.playerIds.sumOf { state.offlineSpeakers[it] ?: 0 },
                            inputFormat = pushed?.inputFormat.orEmpty(),
                            streamInfo = pushed?.streamInfo,
                            isRadio = pushed?.isRadio == true,
                            hasTvInput = state.hasTvInput(group),
                            source = pushed?.container?.name,
                            alarmRinging = pushed?.ringingAlarm != null,
                        )
                    },
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading())

    // What a press just asked for, per speaker, until the player confirms it. Without this
    // the level would snap back to the pushed one between the press and the event, and a
    // second press inside the debounce window would aim from the stale value — the same bug
    // the group volume had, one scope down.
    private val _pendingPlayerVolumes = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val playerVolumeJobs = mutableMapOf<String, Job>()

    // The same, for whole groups: a row in the panel's "add another" list stands for a
    // group rather than a speaker, so it carries the group's level.
    private val _pendingGroupVolumes = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val groupVolumeJobs = mutableMapOf<String, Job>()

    /**
     * Each group's level, for the rooms a panel offers to join.
     *
     * Pushed off `groupVolume:1`, and overlaid with whatever a press just asked for the same
     * way [playerVolumes] is.
     */
    val groupVolumes: StateFlow<Map<String, Int>> =
        combine(household.groupStates, _pendingGroupVolumes) { states, pending ->
            states.mapNotNull { (id, state) -> state.volume?.let { id to it.volume } }.toMap() + pending
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * Each speaker's own level, for the room panel's "playing together" list.
     *
     * Pushed off `playerVolume:1`, so it follows someone turning a speaker up from the
     * Sonos app rather than needing a re-read. Overlaid with whatever a press just asked
     * for, until the speaker confirms it — see [setPlayerVolume].
     */
    val playerVolumes: StateFlow<Map<String, Int>> =
        combine(household.playerVolumes, _pendingPlayerVolumes) { pushed, pending ->
            pushed.mapValues { it.value.volume } + pending
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Speakers whose own volume is muted, for the room panel's member rows. */
    val mutedPlayers: StateFlow<Set<String>> = household.playerVolumes
        .map { volumes -> volumes.filterValues { it.muted }.keys }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val selectedTheme: StateFlow<AppColorTheme> = themeStore.theme
    val tvPlayerId: StateFlow<String?> = roomPrefsStore.tvPlayerId

    private val _selectedGroupId = MutableStateFlow<String?>(null)
    val selectedGroupId: StateFlow<String?> = _selectedGroupId.asStateFlow()

    private val _sidebarVisible = MutableStateFlow(true)
    val sidebarVisible: StateFlow<Boolean> = _sidebarVisible.asStateFlow()

    /** The speakers of the last selection, so it can be found again after a regroup. */
    @Volatile private var lastSelectedPlayers: Set<String> = emptySet()

    private val _navigateToRoom = MutableStateFlow(false)
    val navigateToRoom: StateFlow<Boolean> = _navigateToRoom.asStateFlow()

    init {
        // A tile names a player, and only the topology can say which group holds it — so a
        // cold start from a tile waits for it. Selecting the raw id at once, as this did, lost
        // the room: the id was in no list yet, and the default selection replaced it.
        viewModelScope.launch {
            pendingRoomDeepLink.roomId.collectLatest { roomId ->
                if (roomId == null) return@collectLatest
                val groups = household.state.map { it.groups }.first { it.isNotEmpty() }
                pendingRoomDeepLink.clear()
                val group = groups.firstOrNull { it.id == roomId } ?: TvSoundbar.groupOf(roomId, groups)
                    ?: return@collectLatest
                lastSelectedPlayers = group.playerIds.toSet()
                _selectedGroupId.value = group.id
                _navigateToRoom.value = true
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
                // Not while the household is still arriving. Subscription snapshots land one
                // group at a time, so in the instant after the first soundbar's metadata and
                // before the second's, `detect` sees exactly one room on a TV input and
                // answers confidently — and the answer is then kept. The ambiguity guard
                // only means anything once every group has said what it is doing.
                //
                // Keyed on metadata specifically, not on having *an* entry: any of the three
                // subscriptions creates one, and `onTvInput` comes from metadata alone — so a
                // group holding only its playback snapshot would satisfy a weaker guard while
                // still reporting "not on a TV input".
                val state = household.state.value
                val groupStates = household.groupStates.value
                if (state.groups.any { groupStates[it.id]?.metadataSeen != true }) return@collect
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
     * Connects on the way in. Staying connected while hidden is what keeps state warm for
     * the next frame rather than something to be avoided, so nothing undoes this.
     */
    fun connect() {
        viewModelScope.launch {
            runCatching { household.connect() }
        }
    }

    fun chooseHousehold(choice: HouseholdChoice) {
        viewModelScope.launch { runCatching { household.chooseHousehold(choice) } }
    }

    fun clearNavigateToRoom() { _navigateToRoom.value = false }

    fun setTheme(theme: AppColorTheme) = themeStore.setTheme(theme)

    fun selectGroup(id: String) { _selectedGroupId.value = id }

    fun toggleSidebar() { _sidebarVisible.value = !_sidebarVisible.value }

    // Grouping changes arrive back as a groups:1 event, so none of these re-fetch.

    fun joinGroup(sourceGroupId: String, targetGroupId: String) {
        val source = findGroup(sourceGroupId) ?: return
        viewModelScope.launch {
            runCatching { household.modifyGroupMembers(targetGroupId, add = source.playerIds, remove = emptyList()) }
                .onFailure { _notice.failure("add ${source.name}", it) }
        }
    }

    fun soloGroup(groupId: String) {
        val group = findGroup(groupId) ?: return
        // Everything but the coordinator leaves, which is what "solo" means here.
        val others = group.playerIds.filter { it != group.coordinatorId }
        if (others.isEmpty()) return
        viewModelScope.launch {
            runCatching { household.modifyGroupMembers(groupId, add = emptyList(), remove = others) }
                .onFailure { _notice.failure("separate ${group.name}", it) }
        }
    }

    fun removePlayerFromGroup(groupId: String, playerId: String) {
        viewModelScope.launch {
            runCatching { household.modifyGroupMembers(groupId, add = emptyList(), remove = listOf(playerId)) }
                .onFailure { _notice.failure("remove ${household.playerName(playerId)}", it) }
        }
    }

    /** This group's speakers, coordinator first: it is the room the panel is *about*. */
    fun playerNamesForGroup(group: Group): List<Pair<String, String>> =
        group.playerIds.sortedByDescending { it == group.coordinatorId }
            .map { it to household.playerName(it) }

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
            ?: sortGroups(groups).first()
        val joiners = groups.filter { it.id != host.id }.flatMap { it.playerIds }
        if (joiners.isEmpty()) return
        viewModelScope.launch {
            runCatching { household.modifyGroupMembers(host.id, add = joiners, remove = emptyList()) }
                .onFailure { _notice.failure("start the party", it) }
        }
    }

    /**
     * Override whatever this room is playing with its soundbar's HDMI input.
     *
     * Nothing is observed here: the switch comes back as a metadata event like any other
     * change, so the row that offered it lights up on its own.
     */
    fun useTvInput(groupId: String) {
        val named = roomPrefsStore.tvPlayerId.value
        viewModelScope.launch {
            runCatching { household.useTvInput(groupId, preferSoundbar = named) }
                .onFailure { _notice.failure("switch to the TV", it) }
        }
    }

    /**
     * Say which soundbar this television is plugged into.
     *
     * Detection is a heuristic and cannot be otherwise — Android will not say what is on the
     * far end of its HDMI — and it has been seen to answer confidently and wrongly: any
     * moment when one soundbar is on a TV input and the others are playing music looks
     * exactly like the answer. So the viewer can state it, and a stated answer wins: the
     * detector only ever fills this in while it is empty.
     *
     * King of the hill: naming a room simply moves the crown off whichever room held it, so
     * there is no un-naming to do and no row for it. The only way to be wrong is to have
     * named nothing yet, which detection covers.
     *
     * Stores the **player**, never the group, because a regroup mints new group ids while
     * the soundbar stays bolted to the same television.
     */
    fun setTvSoundbar(groupId: String) {
        val group = findGroup(groupId) ?: return
        val soundbar = TvSoundbar.soundbarOf(group, household.state.value) ?: return
        roomPrefsStore.setTvPlayer(soundbar)
    }

    /** A whole group's level, from a row that offers to join it. */
    fun adjustGroupVolume(groupId: String, delta: Int) {
        val current = groupVolumes.value[groupId] ?: return
        setGroupVolume(groupId, current + delta)
    }

    /** A group's level set outright, as a touch on its bar does; presses go through here too. */
    fun setGroupVolume(groupId: String, volume: Int) {
        if (household.groupState(groupId).volume?.fixed == true) return _notice.post(FIXED_VOLUME)
        // Unknown until the speaker has said; a level set from nothing would be a guess.
        if (groupVolumes.value[groupId] == null) return
        val target = volume.coerceIn(0, 100)
        _pendingGroupVolumes.update { it + (groupId to target) }
        groupVolumeJobs[groupId]?.cancel()
        groupVolumeJobs[groupId] = viewModelScope.launch {
            delay(VOLUME_DEBOUNCE_MILLIS)
            runCatching { household.setGroupVolume(groupId, target) }.onFailure { _notice.failure("change the volume", it) }
            // Only if it is still ours — see [setPlayerVolume].
            _pendingGroupVolumes.update { if (it[groupId] == target) it - groupId else it }
        }
    }

    /** One speaker's own level, accumulating presses the way the group volume does. */
    fun adjustPlayerVolume(playerId: String, delta: Int) {
        val current = playerVolumes.value[playerId] ?: return
        setPlayerVolume(playerId, current + delta)
    }

    /** One speaker's level set outright; see [setGroupVolume]. */
    fun setPlayerVolume(playerId: String, volume: Int) {
        if (household.playerVolumes.value[playerId]?.fixed == true) return _notice.post(FIXED_VOLUME)
        if (playerVolumes.value[playerId] == null) return
        val target = volume.coerceIn(0, 100)
        _pendingPlayerVolumes.update { it + (playerId to target) }
        playerVolumeJobs[playerId]?.cancel()
        playerVolumeJobs[playerId] = viewModelScope.launch {
            delay(VOLUME_DEBOUNCE_MILLIS)
            runCatching { household.setPlayerVolume(playerId, target) }
                .onFailure { _notice.failure("change that speaker's volume", it) }
            // Only if it is still ours. `runCatching` catches the CancellationException a
            // newer press throws in here, and this line is not a suspension point, so
            // clearing unconditionally would delete the target that press just wrote —
            // leaving the row on the stale pushed level and the press after it aiming from
            // there, which is the very accumulation this exists to prevent.
            _pendingPlayerVolumes.update { if (it[playerId] == target) it - playerId else it }
        }
    }

    /** UPnP switched off for the household: the room panel has no TV input to offer. */
    val upnpOff: StateFlow<Boolean> = household.state.map { it.upnpOff }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _notice = TransientNotice(viewModelScope)

    /**
     * Why the last grouping, party, TV or level change did not work, for a few seconds. Drawn
     * above the room panel, because the panel stays open while a group is built.
     */
    val notice: StateFlow<String?> = _notice.text


    /**
     * Every speaker in [groupId] set to the group's own level — the desktop widget's
     * *Normalize*, and x2rock's `vol normalize`. `playerVolume:1 setVolume` per member: Sonos
     * then moves the group's level to their average, which is already the level each was set
     * to, so the group stays where it was. Fixed line-outs are left alone.
     */
    fun normalizeGroup(groupId: String) {
        val group = findGroup(groupId) ?: return
        val level = household.groupState(groupId).volume?.volume ?: return
        val pushed = household.playerVolumes.value
        val members = group.playerIds.filter { pushed[it]?.fixed != true }
        viewModelScope.launch {
            members.forEach { playerId ->
                runCatching { household.setPlayerVolume(playerId, level) }
                    .onFailure { _notice.failure("even out ${household.playerName(playerId)}", it) }
            }
        }
    }

    // ------------------------------------------------------------ tone
    //
    // The room panel's Sound rows, for the room it was opened on. Per speaker, on that
    // speaker. Read from settings:1 and room calibration, written over UPnP, and nothing
    // pushes a change: so each write is shown at once and confirmed by a re-read, the way
    // the TV pane's Night Sound is.

    /** What the room panel shows for its room's tone, or `null` while it is being read. */
    data class ToneUi(
        val playerId: String,
        val bass: Int,
        val treble: Int,
        val loudness: Boolean,
        /** `null` when the speaker would not say; the row is then not offered. */
        val trueplay: com.rahga.x2rock.model.TruePlay?,
    )

    private val _tone = MutableStateFlow<ToneUi?>(null)
    val tone: StateFlow<ToneUi?> = _tone.asStateFlow()
    private var toneJob: kotlinx.coroutines.Job? = null
    /** One pending write per control, so a burst of presses sends its total once. */
    private val toneWrites = mutableMapOf<String, Job>()
    /** Writes go one at a time, in order: two in flight could land in either order. */
    private val toneSends = kotlinx.coroutines.sync.Mutex()
    /** Moves on every press, so a read that started before one is known to be stale. */
    private var toneEdits = 0

    /** Read the tone of [groupId]'s own speaker: its coordinator, the room it is named for. */
    fun loadTone(groupId: String) {
        val playerId = findGroup(groupId)?.coordinatorId ?: return
        if (_tone.value?.playerId != playerId) _tone.value = null
        toneJob?.cancel()
        toneJob = viewModelScope.launch { readTone(playerId) }
    }

    /**
     * Applied only if nothing was pressed while it was out. A read that started before a press
     * reports the level before it, and showing that would also make the next press step from
     * it, losing one.
     */
    private suspend fun readTone(playerId: String) {
        val edits = toneEdits
        // Two reads of the same speaker, one over its socket and one over UPnP, neither
        // needing the other: together, so the Sound rows wait for one latency, not two.
        val (eq, trueplay) = coroutineScope {
            val eq = async { runCatching { household.playerSettings(playerId).eq }.getOrNull() }
            val trueplay = async { runCatching { household.trueplay(playerId) }.getOrNull() }
            eq.await() to trueplay.await()
        }
        if (eq == null || edits != toneEdits) return
        _tone.value = ToneUi(playerId, eq.bass, eq.treble, eq.loudness, trueplay)
    }

    /**
     * Shows [edited] now, and writes it after the presses stop, as volume does. A press cancels
     * only a write still waiting: one already sent has reached the player whatever happens
     * here, so cancelling it would only lose its read and report a failure that was not one.
     * The read after waits for every pending write, so it cannot show one control's new level
     * with another's old one.
     */
    private fun editTone(key: String, what: String, edited: ToneUi, send: suspend (String) -> Unit) {
        toneEdits++
        _tone.value = edited
        toneWrites.remove(key)?.cancel()
        toneWrites[key] = viewModelScope.launch {
            delay(VOLUME_DEBOUNCE_MILLIS)
            toneWrites.remove(key)
            toneSends.withLock {
                runCatching { send(edited.playerId) }.onFailure { _notice.failure(what, it) }
            }
            if (toneWrites.isEmpty()) readTone(edited.playerId)
        }
    }

    fun stepBass(delta: Int) {
        val tone = _tone.value ?: return
        val target = (tone.bass + delta).coerceIn(Upnp.TONE_RANGE)
        if (target != tone.bass) editTone("bass", "change the tone", tone.copy(bass = target)) { household.setBass(it, target) }
    }

    fun stepTreble(delta: Int) {
        val tone = _tone.value ?: return
        val target = (tone.treble + delta).coerceIn(Upnp.TONE_RANGE)
        if (target != tone.treble) editTone("treble", "change the tone", tone.copy(treble = target)) { household.setTreble(it, target) }
    }

    fun toggleLoudness() {
        val tone = _tone.value ?: return
        val on = !tone.loudness
        editTone("loudness", "change Loudness", tone.copy(loudness = on)) { household.setLoudness(it, on) }
    }

    fun toggleTrueplay() {
        val tone = _tone.value ?: return
        val now = tone.trueplay ?: return
        val on = !now.enabled
        editTone("trueplay", "change TruePlay", tone.copy(trueplay = now.copy(enabled = on))) { household.setTrueplay(it, on) }
    }

    /** What the app would choose with nothing else to go on. */
    private fun defaultSelection(groups: List<Group>): Group? = sortGroups(groups).firstOrNull()

    private fun findGroup(id: String): Group? =
        (uiState.value as? UiState.Success)?.groups?.find { it.id == id }

    private companion object {
        /** The same window the player pane uses, so a held key behaves the same in both. */
        const val VOLUME_DEBOUNCE_MILLIS = 300L
    }
}

/**
 * The lines under a room's name in the room list, in order.
 *
 * A soundbar on its TV input gets the format it is receiving, then the source — "Dolby
 * Digital Surround 5.1" over "TV Audio" — because the format is what changes and what a
 * listener is checking for. A track gives its title, then the artist, falling back to what
 * the station says when that says something the title does not. A stream loaded by URL has no
 * track, so its own text is its now-playing, with the station — for a bare URL, the host —
 * beneath it. And a stream with no text of its own yet is named once, by that station or
 * host, rather than reduced to "Playing": x2rock's rule.
 */
fun roomRowLines(info: HomeViewModel.RoomInfo, playbackState: String): List<String> = when {
    // The chime has no track and no name but `x-rincon-buzzer:0`, so it would read "Playing";
    // an alarm with music would read as that music. Either way, what is happening is an alarm.
    info.alarmRinging -> listOfNotNull("Alarm", info.track?.name)
    info.onTvInput -> listOfNotNull(info.inputFormat.ifEmpty { null } ?: playbackState.toPlaybackLabel(), info.source)
    info.track?.name != null -> listOfNotNull(
        info.track.name,
        info.track.artist?.name ?: info.streamInfo?.takeIf { it != info.track.name },
    )
    info.streamInfo != null -> listOfNotNull(info.streamInfo, info.source)
    info.isRadio && info.source != null -> listOf(info.source)
    else -> listOf(playbackState.toPlaybackLabel())
}
