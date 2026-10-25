package com.rahga.x2rock.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rahga.x2rock.store.BetaFeatures
import com.rahga.x2rock.store.PendingRoomDeepLink
import com.rahga.x2rock.store.RoomPreferencesStore
import com.rahga.x2rock.store.ThemeStore
import com.rahga.x2rock.channel.ChannelSync
import com.rahga.x2rock.channel.RoomTile
import com.rahga.x2rock.lan.HouseholdChoice
import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.lan.Battery
import com.rahga.x2rock.lan.RemoteSettings
import com.rahga.x2rock.lan.TvSoundbar
import com.rahga.x2rock.lan.Upnp
import com.rahga.x2rock.model.Satellites
import com.rahga.x2rock.model.AppColorTheme
import com.rahga.x2rock.model.Group
import com.rahga.x2rock.model.hasLoadedContent
import com.rahga.x2rock.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import com.rahga.x2rock.model.TruePlay
import kotlinx.coroutines.sync.withLock
import com.rahga.x2rock.store.Preset
import com.rahga.x2rock.store.PresetStore
import java.util.UUID
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Sidebar order: the rooms this device has placed ([ranks], player ids, first to last), then every
 * other room alphabetically, which is Sonos's own order. A group stands where its best-placed
 * speaker does, so grouping two rooms never sinks either. The Sonos app lets its rooms be
 * reordered now (P-20); this order is this device's, set by a hold in the list.
 *
 * Two hoists were removed from here once — favourites, then a pinned "primary room" that quietly
 * switched off [TvSoundbar] detection. Neither came back: placing a room is the viewer's own
 * order, and the first-launch default ([defaultSelection]) stays alphabetical regardless.
 */
fun sortGroups(groups: List<Group>, ranks: List<String> = emptyList()): List<Group> {
    fun rank(group: Group) = group.playerIds.mapNotNull { id -> ranks.indexOf(id).takeIf { it >= 0 } }.minOrNull()
    return groups.sortedWith(compareBy<Group>({ rank(it) ?: Int.MAX_VALUE }, { it.name }))
}

/**
 * The ranks after moving [groupId] [by] places in the list as shown. Every room from the top down to
 * where it lands — and down to the last room already placed — is placed, in the order now shown,
 * so the list after a move is exactly the list the viewer saw: a room put among the unplaced ones
 * does not jump above them, and the unplaced below it stay alphabetical. Each group writes all its
 * speakers, so a room keeps its place when a group splits; speakers not in any group now keep theirs.
 */
fun movedRanks(groups: List<Group>, ranks: List<String>, groupId: String, by: Int): List<String> {
    val shown = sortGroups(groups, ranks).toMutableList()
    val from = shown.indexOfFirst { it.id == groupId }.takeIf { it >= 0 } ?: return ranks
    val to = (from + by).coerceIn(0, shown.lastIndex)
    if (to == from) return ranks
    shown.add(to, shown.removeAt(from))
    val lastPlaced = shown.indexOfLast { g -> g.playerIds.any { it in ranks } }
    val placed = shown.take(maxOf(to, lastPlaced) + 1).flatMap { it.playerIds }
    val away = ranks.filter { id -> groups.none { id in it.playerIds } }
    return placed + away
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val household: SonosHousehold,
    private val themeStore: ThemeStore,
    private val roomPrefsStore: RoomPreferencesStore,
    private val channelSync: ChannelSync,
    private val pendingRoomDeepLink: PendingRoomDeepLink,
    private val presetStore: PresetStore,
    private val betaFeatures: BetaFeatures,
    private val appleMusic: com.rahga.x2rock.store.AppleMusicSetting,
    saved: SavedStateHandle = SavedStateHandle(),
    private val shared: SharedNotice = SharedNotice(MainScope()),
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
        /** This device's television's room: it holds the soundbar stated or detected (T-33). */
        val isThisTv: Boolean = false,
        /**
         * The line-ins this room can play: its own speaker's first, then every other room's. Empty
         * unless Beta features is on, since none has run on hardware: see `LINE_IN` (T-21).
         */
        val lineIns: List<LineIn> = emptyList(),
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
        /**
         * Crossfade, on or off; `null` where it means nothing — nothing loaded, or a source that
         * cannot crossfade (a live stream). A room setting, so it lives in the room panel.
         */
        val crossfade: Boolean? = null,
    )

    /** A speaker's analog line-in, offered to a room: [room] is that speaker's, and [own] if it is this room's. */
    data class LineIn(val playerId: String, val room: String, val own: Boolean)

    sealed interface UiState {
        /** Not connected yet — or, with [reconnecting], again, after the speaker it ran through went. */
        data class Loading(val reconnecting: Boolean = false) : UiState
        data class Success(val groups: List<Group>, val rooms: Map<String, RoomInfo>) : UiState {
            /** The TV home-screen channel's tiles, one per room. */
            val tiles: Map<String, RoomTile> get() = rooms.mapValues { RoomTile(it.value.track?.name, it.value.artUrl) }
        }
        /** [choices] is non-empty when the "error" is two households, and the answer is a pick. */
        data class Error(val message: String, val choices: List<HouseholdChoice> = emptyList()) : UiState
    }

    /**
     * Derived, not polled. Both sources are pushed by the speakers, so this recomputes when
     * something actually changes and at no other time.
     */
    val uiState: StateFlow<UiState> =
        combine(household.state, household.groupStates, betaFeatures.enabled, roomPrefsStore.tvPlayerId) { state, groupStates, beta, tvPlayer ->
            val error = state.error
            when {
                error != null -> UiState.Error(plainConnectionProblem(error, state.householdChoices.isNotEmpty()), state.householdChoices)
                !state.connected -> UiState.Loading(reconnecting = state.reconnecting)
                else -> UiState.Success(
                    groups = state.groups,
                    rooms = state.groups.associate { group ->
                        val pushed = groupStates[group.id]
                        group.id to RoomInfo(
                            track = pushed?.track,
                            artUrl = pushed?.artUrl,
                            onTvInput = pushed?.onTvInput == true,
                            muted = pushed?.volume?.muted == true,
                            offlineSpeakers = group.playerIds.sumOf { state.offlineSpeakers[it] ?: 0 },
                            inputFormat = pushed?.inputFormat.orEmpty(),
                            streamInfo = pushed?.streamInfo,
                            isRadio = pushed?.isRadio == true,
                            hasTvInput = state.hasTvInput(group),
                            isThisTv = tvPlayer != null && tvPlayer in group.playerIds,
                            lineIns = if (!beta) emptyList() else state.lineInPlayers
                                .map { LineIn(it.id, it.name, own = it.id in group.playerIds) }
                                .sortedByDescending { it.own },
                            source = pushed?.container?.name,
                            alarmRinging = pushed?.ringingAlarm != null,
                            crossfade = pushed
                                ?.takeIf { it.actions.canCrossfade && it.playbackState.hasLoadedContent() }
                                ?.playMode?.crossfade,
                        )
                    },
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading())

    val selectedTheme: StateFlow<AppColorTheme> = themeStore.theme

    /** Settings → Beta features: see [BetaFeatures]. */
    val betaEnabled: StateFlow<Boolean> = betaFeatures.enabled

    fun setBetaEnabled(on: Boolean) = betaFeatures.setEnabled(on)

    /**
     * Settings → Advanced → Apple Music, for a household whose speakers store no Apple Music sign-in;
     * [appleStored] is `null` until read, true where the switch is not offered. See [AppleMusicSetting].
     */
    val appleForced: StateFlow<Boolean> = appleMusic.forced
    private val _appleStored = MutableStateFlow<Boolean?>(null)
    val appleStored: StateFlow<Boolean?> = _appleStored.asStateFlow()
    fun setAppleForced(on: Boolean) = appleMusic.setForced(on)
    /** Asked as Settings opens: the sign-ins are read once a connection, so this is cheap after the first. */
    fun readAppleStored() {
        viewModelScope.launch { _appleStored.value = runCatching { appleMusic.stored() }.getOrNull() }
    }

    private val _selectedGroupId = MutableStateFlow<String?>(null)
    val selectedGroupId: StateFlow<String?> = _selectedGroupId.asStateFlow()


    /**
     * The room chosen, by its speakers, so it can be found again after a regroup — and kept for a
     * restore after the app was reclaimed, so Back opens on the room the viewer left. Chosen, not
     * merely followed: see [SavedRoom] for why a party another speaker hosts does not move it (P-71).
     */
    private val savedSelection = SavedRoom(saved, "home.selected")

    /**
     * A choice of one speaker's room: a tile names a player, and so does the TV room. Anchored on the
     * speaker alone, not on the group holding it, which may be a party it only joined: anchored on the
     * party's host, the selection stayed with the host when the party split (P-116). The room's own
     * group is kept once it is the one selected (see the refresh in `init`).
     */
    private fun chooseSpeaker(playerId: String) = savedSelection.adopt(playerId, listOf(playerId))

    /**
     * The selected room as a screen opened on it is handed it: [groupId], and the speakers of the room
     * chosen, which that screen follows it by (P-71, P-88).
     */
    fun route(groupId: String): RoomRoute = RoomRoute(groupId, savedSelection.coordinator, savedSelection.players)

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
                val named = groups.firstOrNull { it.id == roomId }
                val group = named ?: TvSoundbar.groupOf(roomId, groups)
                if (group != null) {
                    if (named != null) savedSelection.remember(group) else chooseSpeaker(roomId)
                    _selectedGroupId.value = group.id
                    if (pendingRoomDeepLink.navigates()) _navigateToRoom.value = true
                }
                // On the shared line, which the room's pane draws, and Browse too when a preset was
                // started with the Stay pin held and the viewer is still there.
                pendingRoomDeepLink.takeNotice()?.let { shared.post(it) }
                // Last: clearing emits null, which cancels this block once it next suspends.
                pendingRoomDeepLink.clear()
            }
        }
        // Keep a room selected, including through regroupings this app did not perform.
        viewModelScope.launch {
            household.state.map { it.groups }.distinctUntilChanged().collect { groups ->
                if (groups.isEmpty()) return@collect
                val current = _selectedGroupId.value
                // A regroup mints new group ids, so the selected one can simply cease to
                // exist — anything else on the network can do that at any moment. Follow
                // the speakers rather than the id: whichever group now holds them is the
                // same room to a listener, even though it is a different group. By the one
                // rule every screen follows a room by, which puts the coordinator first: any
                // shared speaker used to do, and a split sent the selection to whichever
                // fragment the household listed first. And by the room chosen, so a party the
                // room only joined is left when it splits, though its host keeps its id (P-71).
                val followed = savedSelection.findIn(groups)
                if (current != null && groups.any { it.id == current } && (followed == null || followed.id == current)) return@collect

                _selectedGroupId.value = (
                    followed
                        ?: roomPrefsStore.tvPlayerId.value?.let { tv -> TvSoundbar.groupOf(tv, groups)?.also { chooseSpeaker(tv) } }
                        ?: defaultSelection(groups)?.also { savedSelection.remember(it) }
                    )?.id
            }
        }

        // Kept up to date as the chosen room grows or shrinks, so it can be followed to wherever its
        // speakers go; not moved onto a group another speaker coordinates, which it only joined.
        viewModelScope.launch {
            combine(household.state, _selectedGroupId) { state, id -> state.groups.firstOrNull { it.id == id } }
                .collect { group -> if (group != null) savedSelection.refresh(group) }
        }
        // Which soundbar this television feeds, learned once and then remembered. Only
        // detectable while that room is on its TV input, so this watches rather than asking
        // at startup — and only fills in an empty answer: a stated one wins, see [setTvSoundbar].
        viewModelScope.launch {
            combine(household.state, household.groupStates) { state, groupStates ->
                // Not while the household is still arriving. Subscription snapshots land one
                // group at a time, so in the instant after the first soundbar's metadata and
                // before the second's, `detect` sees exactly one room on a TV input and
                // answers confidently — and the answer is then kept. The ambiguity guard
                // only means anything once every group has said what it is doing.
                //
                // Keyed on metadata specifically, not on having *an* entry: any of the three
                // subscriptions creates one, and `onTvInput` comes from metadata alone — so a
                // group holding only its playback snapshot would satisfy a weaker guard while
                // still reporting "not on a TV input". Judged on the snapshot `detect` saw.
                if (state.groups.any { groupStates[it.id]?.metadataSeen != true }) null
                else TvSoundbar.detect(state, groupStates)
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
                    TvSoundbar.groupOf(detected, groups)?.let {
                        chooseSpeaker(detected)
                        _selectedGroupId.value = it.id
                    }
                }
            }
        }

        // The TV home-screen channels follow whatever the household last said: the rooms in the
        // list's order, each with its tile. Without its playback state, which every play or pause
        // anywhere moved and so rewrote every tile — a tile changes for its subtitle and art only.
        viewModelScope.launch(Dispatchers.IO) {
            // In this device's room order, which is the viewer's own and moves only when they move it.
            combine(uiState, roomPrefsStore.roomRanks) { state, ranks -> state to ranks }.collect { (state, ranks) ->
                if (state is UiState.Success) {
                    channelSync.sync(sortGroups(state.groups, ranks).map { it.copy(playbackState = null) }, state.tiles)
                }
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

    /** The rooms this device has placed, in order; see [sortGroups]. */
    val roomRanks: StateFlow<List<String>> = roomPrefsStore.roomRanks

    /** Move [groupId] [by] places in the room list; see [movedRanks]. */
    fun moveRoom(groupId: String, by: Int) {
        roomPrefsStore.setRoomRanks(movedRanks(household.state.value.groups, roomPrefsStore.roomRanks.value, groupId, by))
    }

    /** Back to Sonos's alphabetical order. */
    fun resetRoomOrder() = roomPrefsStore.setRoomRanks(emptyList())

    /**
     * The viewer chose [id]: it is the room now. The same id again is no choice — focus coming back
     * to the selected row — and leaves the room chosen as it was, whatever group it is following.
     */
    fun selectGroup(id: String) {
        if (_selectedGroupId.value == id) return
        findGroup(id)?.let { savedSelection.remember(it) }
        _selectedGroupId.value = id
    }


    // Grouping changes arrive back as a groups:1 event, so none of these re-fetch.

    fun joinGroup(sourceGroupId: String, targetGroupId: String) {
        val source = findGroup(sourceGroupId) ?: return
        viewModelScope.launch {
            runCatching { household.modifyGroupMembers(targetGroupId, add = source.playerIds, remove = emptyList()) }
                .onFailure { failure("add ${source.name}", it) }
        }
    }

    fun soloGroup(groupId: String) {
        val group = findGroup(groupId) ?: return
        // Everything but the coordinator leaves, which is what "solo" means here.
        val others = group.playerIds.filter { it != group.coordinatorId }
        if (others.isEmpty()) return
        viewModelScope.launch {
            runCatching { household.modifyGroupMembers(groupId, add = emptyList(), remove = others) }
                .onFailure { failure("separate ${group.name}", it) }
        }
    }

    fun removePlayerFromGroup(groupId: String, playerId: String) {
        viewModelScope.launch {
            runCatching { household.modifyGroupMembers(groupId, add = emptyList(), remove = listOf(playerId)) }
                .onFailure { failure("remove ${household.playerName(playerId)}", it) }
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
     * whose panel this was opened from is the one the house follows.
     *
     * With [evenVolume], every speaker is first set to the host room's level — the level of
     * what is being heard where the viewer is — and only then do the rooms join, so nothing
     * starts loud, as a preset is applied. A fixed line-out is left alone.
     */
    fun partyMode(hostGroupId: String, evenVolume: Boolean = false) {
        val groups = (uiState.value as? UiState.Success)?.groups ?: return
        if (groups.size < 2) return
        val host = groups.firstOrNull { it.id == hostGroupId } ?: return
        val joiners = groups.filter { it.id != host.id }.flatMap { it.playerIds }
        if (joiners.isEmpty()) return
        val level = household.groupState(host.id).volume?.volume.takeIf { evenVolume }
        // Asked to even out and no level known yet (the room's volume not yet pushed): joining
        // anyway would be the loud start evening out was for.
        if (evenVolume && level == null) return post("Couldn't read ${host.name}'s volume yet, so no room joined")
        viewModelScope.launch {
            // A speaker whose level could not be set stays out: joining, it would play at once at
            // whatever level it was left on, which is what evening out was for (P-40).
            val failed = level?.let { household.setLevelsBeforeJoining(groups.flatMap { it.playerIds }.associateWith { level }) }.orEmpty()
            val joining = joiners.filter { it !in failed }
            val leftOut = joiners.filter { it in failed }.joinToString { household.playerName(it) }
            if (joining.isEmpty()) return@launch post("Couldn't set the volume in $leftOut, so no room joined")
            runCatching { household.modifyGroupMembers(host.id, add = joining, remove = emptyList()) }
                .onSuccess { if (leftOut.isNotEmpty()) post("Left out $leftOut: couldn't set its volume") }
                .onFailure { failure("start the party", it) }
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
                .onFailure { failure("switch to the TV", it) }
        }
    }

    /**
     * Put the room on a line-in, its own or another room's. A beta feature, untested on hardware:
     * see `SonosHousehold.useLineIn`. A refusal is kept for the tester's report as well as said.
     */
    fun useLineIn(groupId: String, playerId: String) {
        viewModelScope.launch {
            runCatching { household.useLineIn(groupId, playerId) }
                .onFailure {
                    failure("switch to the line-in", it)
                    betaFeatures.noteRefusal("Line-In (${household.playerName(playerId)})", it)
                }
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
     * Whether the home screen is in front, as it says: its banner is the only reader of [notice]. An
     * outcome that lands once the viewer has gone on goes to the shared line instead, which every
     * screen draws — a TV input can take 20s to fail and a party retries its regroup, and both used to
     * be posted, unseen, to a banner nobody could see while Queue or Browse was in front (P-79).
     */
    @Volatile var shown = false

    private fun post(message: String) = if (shown) _notice.post(message) else shared.post(message)

    private fun failure(what: String, e: Throwable) = if (shown) _notice.failure(what, e) else shared.failure(what, e)


    /** Turn crossfade over in [groupId], keeping its shuffle and repeat as they are. */
    fun toggleCrossfade(groupId: String) {
        val mode = household.groupState(groupId).playMode
        viewModelScope.launch {
            runCatching { household.setPlayMode(groupId, mode.copy(crossfade = !mode.crossfade)) }
                .onFailure { failure("change Crossfade", it) }
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
        val trueplay: TruePlay?,
        /** The speaker's status light, and whether its touch controls are locked; `null` unread. */
        val statusLight: Boolean? = null,
        val buttonsLocked: Boolean? = null,
        /**
         * The speaker's volume limit as a percent, 100 being none; `null` where it says nothing. Shown,
         * not set: the write is refused to an unsigned connection (see `PlayerSettings`).
         */
        val volumeLimit: Int? = null,
        /** The room's soundbar, when it has one, and its TV-remote settings: see `RemoteSettings`. */
        val soundbarId: String? = null,
        val remote: RemoteSettings? = null,
        /** A portable's pack, or `null` on mains. Untested against a portable: see `parseBattery`. */
        val battery: Battery? = null,
        /** The room's Sub and surrounds, where it has either; see `SonosHousehold.satellites`. */
        val satellites: Satellites? = null,
    )

    private val _tone = MutableStateFlow<ToneUi?>(null)
    val tone: StateFlow<ToneUi?> = _tone.asStateFlow()
    private var toneJob: Job? = null
    /** One pending write per control, so a burst of presses sends its total once. */
    private val toneWrites = mutableMapOf<String, Job>()
    /** Writes go one at a time, in order: two in flight could land in either order. */
    private val toneSends = Mutex()
    /** Moves on every press, so a read that started before one is known to be stale. */
    private var toneEdits = 0
    /** The speaker the panel last asked about: a read of any other — a write's, landing late — is not shown. */
    private var tonePlayer: String? = null

    /** Read the tone of [groupId]'s own speaker: its coordinator, the room it is named for. */
    fun loadTone(groupId: String) {
        val group = findGroup(groupId) ?: return
        val playerId = group.coordinatorId
        // The remote settings are the soundbar's own, which need not be the room's coordinator.
        val soundbar = TvSoundbar.soundbarOf(group, household.state.value)
        if (_tone.value?.playerId != playerId) _tone.value = null
        tonePlayer = playerId
        toneJob?.cancel()
        toneJob = viewModelScope.launch { readTone(playerId, soundbar) }
    }

    /**
     * Applied only if nothing was pressed while it was out. A read that started before a press
     * reports the level before it, and showing that would also make the next press step from
     * it, losing one.
     */
    private suspend fun readTone(playerId: String, soundbar: String?) {
        val edits = toneEdits
        // Two reads of the same speaker, one over its socket and one over UPnP, neither
        // needing the other: together, so the Sound rows wait for one latency, not two.
        // The light and the lock are the speaker's own switches, read with its tone for the same panel.
        val read = coroutineScope {
            val settings = async { runCatching { household.playerSettings(playerId) }.getOrNull() }
            val trueplay = async { runCatching { household.trueplay(playerId) }.getOrNull() }
            val light = async { runCatching { household.statusLight(playerId) }.getOrNull() }
            val locked = async { runCatching { household.buttonsLocked(playerId) }.getOrNull() }
            val remote = async { soundbar?.let { runCatching { household.remoteSettings(it) }.getOrNull() } }
            // Never seen on a portable, so asked only with Beta features on (T-21).
            val battery = async { if (betaFeatures.enabled.value) runCatching { household.battery(playerId) }.getOrNull() else null }
            val satellites = async { runCatching { household.satellites(playerId) }.getOrNull() }
            val read = settings.await()
            val eqNow = read?.eq ?: return@coroutineScope null
            val limit = read.volumeScalingFactor?.let { (it * 100).roundToInt().coerceIn(0, 100) }
            ToneUi(playerId, eqNow.bass, eqNow.treble, eqNow.loudness, trueplay.await(), light.await(), locked.await(), limit, soundbar, remote.await(), battery.await(), satellites.await())
        }
        if (read == null || edits != toneEdits || playerId != tonePlayer) return
        _tone.value = read
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
                runCatching { send(edited.playerId) }.onFailure { failure(what, it) }
            }
            if (toneWrites.isEmpty()) readTone(edited.playerId, edited.soundbarId)
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

    /** The soundbar's acknowledging flash for the TV remote. Written to the soundbar, not the room. */
    fun toggleRemoteFeedback() {
        val tone = _tone.value ?: return
        val soundbar = tone.soundbarId ?: return
        val remote = tone.remote ?: return
        val on = !remote.feedback
        editTone("feedback", "change the remote light", tone.copy(remote = remote.copy(feedback = on))) { household.setRemoteFeedback(soundbar, on) }
    }

    /** Repeating the TV remote's IR on to the TV. Only between On and Off; another word is left alone. */
    fun toggleIrRepeater() {
        val tone = _tone.value ?: return
        val soundbar = tone.soundbarId ?: return
        val remote = tone.remote ?: return
        val on = !(remote.irRepeaterOn ?: return)
        editTone("repeater", "change the IR repeater", tone.copy(remote = remote.copy(irRepeater = if (on) "On" else "Off"))) {
            household.setIrRepeater(soundbar, on)
        }
    }

    fun toggleStatusLight() {
        val tone = _tone.value ?: return
        val on = !(tone.statusLight ?: return)
        editTone("light", "change the status light", tone.copy(statusLight = on)) { household.setStatusLight(it, on) }
    }

    /** Touch controls on or off, as the Sonos app's Button Control says it: off is locked. */
    fun toggleTouchControls() {
        val tone = _tone.value ?: return
        val locked = !(tone.buttonsLocked ?: return)
        editTone("buttons", "change the touch controls", tone.copy(buttonsLocked = locked)) { household.setButtonsLocked(it, locked) }
    }

    /** The Sub's level, as the Sonos app's Sub audio sets it; on the room's own player, like tone. */
    fun stepSubLevel(delta: Int) = stepSatellite(delta, "sub", "change the Sub", { it.subLevel }, { s, v -> s.copy(subLevel = v) }) { id, v -> household.setSubLevel(id, v) }

    /** The surrounds' level while the room plays its TV — the Sonos app's "TV Level". */
    fun stepSurroundLevel(delta: Int) = stepSatellite(delta, "surround", "change the surrounds", { it.tvLevel }, { s, v -> s.copy(tvLevel = v) }) { id, v -> household.setSurroundLevel(id, v) }

    /** The surrounds' level for music — the Sonos app's "Music Level". */
    fun stepMusicSurroundLevel(delta: Int) = stepSatellite(delta, "musicSurround", "change the surrounds", { it.musicLevel }, { s, v -> s.copy(musicLevel = v) }) { id, v -> household.setMusicSurroundLevel(id, v) }

    fun toggleSub() {
        val tone = _tone.value ?: return
        val s = tone.satellites ?: return
        val on = !(s.subOn ?: return)
        editTone("subOn", "turn the Sub ${if (on) "on" else "off"}", tone.copy(satellites = s.copy(subOn = on))) { household.setSubOn(it, on) }
    }

    fun toggleSurrounds() {
        val tone = _tone.value ?: return
        val s = tone.satellites ?: return
        val on = !(s.surroundsOn ?: return)
        editTone("surroundsOn", "turn the surrounds ${if (on) "on" else "off"}", tone.copy(satellites = s.copy(surroundsOn = on))) { household.setSurroundsOn(it, on) }
    }

    private fun stepSatellite(
        delta: Int, key: String, what: String,
        level: (Satellites) -> Int?, with: (Satellites, Int) -> Satellites, send: suspend (String, Int) -> Unit,
    ) {
        val tone = _tone.value ?: return
        val s = tone.satellites ?: return
        val now = level(s) ?: return
        val target = (now + delta).coerceIn(Upnp.SATELLITE_RANGE)
        if (target != now) editTone(key, what, tone.copy(satellites = with(s, target))) { send(it, target) }
    }

    fun toggleTrueplay() {
        val tone = _tone.value ?: return
        val now = tone.trueplay ?: return
        val on = !now.enabled
        editTone("trueplay", "change TruePlay", tone.copy(trueplay = now.copy(enabled = on))) { household.setTrueplay(it, on) }
    }

    /**
     * Keep this room as it is now — its speakers, each one's level, and the favourite it is playing
     * if what plays is one — as a preset to bring back with one press. Named for what it holds,
     * because naming one would mean typing on a remote: "Kitchen + Dining Room · Smooth Jazz".
     *
     * The favourite is found the way Browse marks one as playing, by the container's name; music
     * that is not a favourite is not kept, and the notice says so rather than saving a preset that
     * silently plays nothing.
     */
    fun savePreset(groupId: String) {
        val group = findGroup(groupId) ?: return
        viewModelScope.launch {
            runCatching {
                val players = group.playerIds.sortedByDescending { it == group.coordinatorId }
                val levels = household.playerVolumes.value
                val volumes = players.mapNotNull { id -> levels[id]?.takeIf { !it.fixed }?.volume?.let { id to it } }.toMap()
                val container = household.groupState(groupId).container?.name
                val favorite = container?.let { name ->
                    playingFavorite(runCatching { household.favorites().items }.getOrDefault(emptyList()), name)
                }
                val rooms = players.joinToString(" + ") { household.playerName(it) }
                val preset = Preset(
                    id = UUID.randomUUID().toString(),
                    name = if (favorite != null) "$rooms · ${favorite.name}" else rooms,
                    playerIds = players,
                    volumes = volumes,
                    favoriteId = favorite?.id,
                )
                presetStore.add(preset)
                preset
            }.onSuccess { preset ->
                post(
                    if (preset.favoriteId != null) "Saved \"${preset.name}\" to Browse"
                    else "Saved \"${preset.name}\" to Browse — rooms and levels only, since what plays isn't a favourite",
                )
            }.onFailure { failure("save the preset", it) }
        }
    }

    /** What the app would choose with nothing else to go on. */
    private fun defaultSelection(groups: List<Group>): Group? = sortGroups(groups).firstOrNull()

    private fun findGroup(id: String): Group? = household.state.value.groups.find { it.id == id }

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
    // Before the first format arrives, the room's own word; the transport's "Idle" said nothing.
    info.onTvInput -> listOfNotNull(info.inputFormat.ifEmpty { null } ?: roomActivity(playbackState, hasSource = true).label, info.source)
    info.track?.name != null -> listOfNotNull(
        info.track.name,
        info.track.artist?.name ?: info.streamInfo?.takeIf { it != info.track.name },
    )
    info.streamInfo != null -> listOfNotNull(info.streamInfo, info.source)
    info.isRadio && info.source != null -> listOf(info.source)
    // In the same word the pane and the panel use for it — "Idle" here read against "Nothing
    // playing" beside it (Streamer, 2026-10-07).
    else -> listOf(roomActivity(playbackState, info.hasSource).label)
}
