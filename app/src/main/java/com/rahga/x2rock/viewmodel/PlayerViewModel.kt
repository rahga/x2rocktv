package com.rahga.x2rock.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.lan.TvSoundbar
import com.rahga.x2rock.model.PlaybackActions
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlayerVolumeEntry(
    val playerId: String,
    val playerName: String,
    val volume: Int,
    val muted: Boolean,
    /** A fixed line-out, as on a Port or an Amp set that way: its level is the amplifier's. */
    val fixed: Boolean = false,
)

data class PlayerUiState(
    val groupName: String = "",
    val playbackState: String = PlaybackStates.IDLE,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val albumArtUrl: String? = null,
    /** On a soundbar's HDMI input, which carries no track at all. */
    val onTvInput: Boolean = false,
    /** e.g. "Dolby Digital 5.1"; empty unless on a TV input with a signal. */
    val inputFormat: String = "",
    /**
     * What the station says is on, for a stream that carries no track. See
     * `PlaybackMetadata.streamInfo` — it is one opaque string, never split into parts.
     */
    val streamInfo: String? = null,
    /** The container's own name: a station, an album. What a stream has instead of an artist. */
    val sourceName: String? = null,
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
    /** What the current source permits — which controls exist at all. */
    val actions: PlaybackActions = PlaybackActions(),
    /** A station rather than a queue: decides the artwork, not the controls. */
    val isRadio: Boolean = false,
    val playerVolumes: List<PlayerVolumeEntry> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    /**
     * Why the room stopped, when it failed to play something — "Couldn't play this: found
     * nothing it could play". Stands until the room plays again. See `GroupState.lastError`.
     */
    val playbackError: String? = null,
    /** The alarm ringing in this room, while it rings: see `GroupState.ringingAlarm`. */
    val ringingAlarm: Int? = null,
    /** UPnP is switched off for the household: see `HouseholdState.upnpOff`. */
    val upnpOff: Boolean = false,
    /**
     * The group's volume is fixed — a Port or an Amp with a fixed line-out — so it is set on
     * the amplifier it feeds, not here. `groupVolume:1`'s `fixed`; x2rock shows "fixed".
     */
    val volumeFixed: Boolean = false,
    /**
     * When the room's sleep timer will stop it, on [MonotonicClock], or null for none. What is
     * left is counted down where it is drawn; this view model keeps no clock of its own.
     */
    val sleepTimerEndsAt: Long? = null,
    /**
     * The soundbar in this room, when it has one. Kept because the home-theatre writes are
     * player-scoped and must name the soundbar itself — on a grouped room the coordinator
     * may be an ordinary speaker, which would refuse them.
     */
    val soundbarId: String? = null,
    /** Night Sound. Meaningful only alongside [soundbarId]; false when there is none. */
    val nightMode: Boolean = false,
    /** Speech Enhancement. The player carries a level too; the Sonos app shows on/off. */
    val speechEnhancement: Boolean = false,
    /**
     * Where the current track stands with its service, or `null` when it cannot be rated
     * from here — no track id, a service needing an account, or one publishing no ratings.
     * Its presence is what draws the rating buttons.
     */
    val rating: SonosHousehold.RatingState? = null,
    /**
     * One transient line: a rating's result ("Rated up on iHeartRadio"), or why a command
     * did not work. Cleared a few seconds after it lands. See [TransientNotice].
     */
    val notice: String? = null,
)

/**
 * The line under a track's title: its artist and album, and on a station the station too — "The
 * Weeknd • Hit List", as the Sonos app puts it. The station used to vanish the moment a track was
 * known, so nothing on the pane said which station was playing (seen against the Sonos app,
 * 2026-10-08). Not repeated where the station's name is the album's or the track's own.
 */
fun PlayerUiState.trackLine(): String {
    val station = sourceName?.takeIf { isRadio && it.isNotBlank() && it != albumName && it != trackName }
    return listOfNotNull(artistName, albumName, station).joinToString(" • ")
}

/** What one read of `settings:1` yielded, with the speaker it came from. */
data class HomeTheaterUi(
    val soundbarId: String,
    val nightMode: Boolean,
    val speechEnhancement: Boolean,
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val household: SonosHousehold,
    private val nowPlaying: NowPlayingPublisher,
    private val clock: MonotonicClock,
) : ViewModel() {

    private val _groupId = MutableStateFlow<String?>(null)
    private val _groupName = MutableStateFlow("")
    private val _sleepEndsAt = MutableStateFlow<Long?>(null)
    private val _homeTheater = MutableStateFlow<HomeTheaterUi?>(null)
    private val notice = TransientNotice(viewModelScope)
    /** The last [SonosHousehold.ratingState] answer, with the room it answered for. */
    private val _rating = MutableStateFlow<Pair<String, SonosHousehold.RatingState>?>(null)

    private var volumeDebounceJob: Job? = null
    private val playerVolumeDebounceJobs = mutableMapOf<String, Job>()
    private var seekDebounceJob: Job? = null
    private var homeTheaterJob: Job? = null

    // What the last press asked for, before the speaker has said anything back.
    //
    // Necessary because uiState is now purely pushed: without it, five volume presses
    // inside the debounce window all read the same unchanged pushed value and the speaker
    // moves one step instead of five. Cleared once the command has gone.
    private var pendingVolumeDelta = 0
    private var pendingSeekMillis: Long? = null

    /**
     * When [pendingSeekMillis] was sent, or `null` while it waits out the debounce. The target
     * holds until the speaker pushes a position after this — the command's reply comes first and
     * the push that moves the position later, and a press in between used to aim from the old one.
     */
    private var pendingSentAt: Long? = null
    private val pendingPlayerDeltas = mutableMapOf<String, Int>()

    private var lastMetadataKey = ""
    private var lastPbStateCode = -1
    private var lastPbPositionMillis = -1L
    private var lastPbActions: PlaybackActions? = null

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
                    // Household-wide, so known before any one room has said anything.
                    upnpOff = householdState.upnpOff,
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
                    onTvInput = state.onTvInput,
                    inputFormat = state.inputFormat,
                    streamInfo = state.streamInfo,
                    sourceName = state.container?.name,
                    positionMillis = state.positionMillis,
                    durationMillis = state.durationMillis,
                    positionUpdatedAt = state.positionUpdatedAt,
                    volume = state.volume?.volume,
                    isMuted = state.volume?.muted ?: false,
                    volumeFixed = state.volume?.fixed ?: false,
                    shuffle = state.playMode.shuffle,
                    repeat = state.playMode.repeat,
                    crossfade = state.playMode.crossfade,
                    actions = state.actions,
                    isRadio = state.isRadio,
                    playbackError = state.lastError?.describe(),
                    ringingAlarm = state.ringingAlarm,
                    upnpOff = householdState.upnpOff,
                    // Per-speaker rows only mean anything once a group has more than one.
                    playerVolumes = group?.playerIds
                        ?.takeIf { it.size > 1 }
                        ?.mapNotNull { id ->
                            playerVolumes[id]?.let {
                                PlayerVolumeEntry(id, household.playerName(id), it.volume, it.muted, it.fixed)
                            }
                        }
                        .orEmpty(),
                    isLoading = false,
                    error = householdState.error,
                )
            }
        }.combine(_sleepEndsAt) { state, endsAt ->
            state.copy(sleepTimerEndsAt = endsAt)
        }.combine(_homeTheater) { state, ht ->
            // Merged rather than derived: alone among everything here, these two are not
            // pushed, so they cannot come out of `household.state` with the rest.
            state.copy(
                soundbarId = ht?.soundbarId,
                nightMode = ht?.nightMode ?: false,
                speechEnhancement = ht?.speechEnhancement ?: false,
            )
        }.combine(_rating) { state, rating ->
            // Keyed to the room it was read for, so a switch never shows the last room's.
            state.copy(rating = rating?.takeIf { it.first == _groupId.value }?.second)
        }.combine(notice.text) { state, text ->
            state.copy(notice = text)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, PlayerUiState())

    private val controls = object : NowPlayingPublisher.Controls {
        override fun togglePlayPause() = this@PlayerViewModel.togglePlayPause()
        override fun play() = this@PlayerViewModel.play()
        override fun pause() = this@PlayerViewModel.pause()
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

    /**
     * A rating is not pushed, so it is asked for once per room and track — `collectLatest`
     * drops a read still in flight when either moves on. Only a track with an id gets as far
     * as the network; everything else answers `null` without leaving the LAN.
     */
    init {
        viewModelScope.launch {
            combine(_groupId, household.groupStates) { id, states ->
                id to states[id]?.track?.id?.takeIf { it.isReal }
            }.distinctUntilChanged().collectLatest { (id, trackId) ->
                _rating.value = null
                if (id != null && trackId != null) loadRating(id)
            }
        }
    }

    private suspend fun loadRating(groupId: String) {
        _rating.value = household.ratingState(groupId)?.let { groupId to it }
    }

    fun selectGroup(id: String, name: String) {
        _groupName.value = name
        // Re-selecting the same room must do nothing. The caller re-runs whenever the group
        // *list* changes, and a `playback:1` event rewrites that list for any room in the
        // house — so without this, a track boundary in another room would clear the home
        // theatre reading and blank both toggles for a round-trip.
        if (_groupId.value == id) return
        _groupId.value?.let { settlePending(it) }
        _groupId.value = id
        // Cleared rather than left standing: the previous room's answer would otherwise sit
        // on screen against the new room's name until this read came back.
        _homeTheater.value = null
        loadHomeTheater()
    }

    /**
     * `settings:1` accepts a subscription and then never mentions these again — verified on
     * a Beam — so this is the one thing in the app that is read rather than pushed, once per
     * room and again after each write.
     */
    private fun loadHomeTheater() {
        val groupId = _groupId.value ?: return
        homeTheaterJob?.cancel()
        homeTheaterJob = viewModelScope.launch {
            val householdState = household.state.value
            val group = householdState.groups.firstOrNull { it.id == groupId }
            val soundbar = group?.let { TvSoundbar.soundbarOf(it, householdState) }
            _homeTheater.value = soundbar?.let { id ->
                runCatching { household.playerSettings(id).homeTheater }.getOrNull()?.let {
                    HomeTheaterUi(id, it.nightMode, it.enhanceDialog)
                }
            }
        }
    }

    /**
     * Applied locally before it is sent, then reconciled by the re-read.
     *
     * Necessary for the same reason the volume steps accumulate: nothing pushes these, so a second
     * press inside the write-and-re-read window would otherwise read the same stale value and
     * send the same command again — two presses landing on *on* rather than back where they
     * started.
     */
    fun toggleNightMode() = editHomeTheater("change Night Sound", { it.copy(nightMode = !it.nightMode) }) { id, ht ->
        household.setNightMode(id, ht.nightMode)
    }

    /** See [toggleNightMode]: applied locally first, then reconciled. */
    fun toggleSpeechEnhancement() = editHomeTheater("change Speech Enhancement", { it.copy(speechEnhancement = !it.speechEnhancement) }) { id, ht ->
        household.setSpeechEnhancement(id, ht.speechEnhancement)
    }

    /** Show [edit] now, send it, and read back what the soundbar then reports. */
    private fun editHomeTheater(what: String, edit: (HomeTheaterUi) -> HomeTheaterUi, send: suspend (String, HomeTheaterUi) -> Unit) {
        val edited = edit(_homeTheater.value ?: return)
        _homeTheater.value = edited
        viewModelScope.launch {
            runCatching { send(edited.soundbarId, edited) }.onFailure { notice.failure(what, it) }
            loadHomeTheater()
        }
    }

    /** Metadata and state are pushed separately, and only when they actually change. */
    private fun publish(state: PlayerUiState) {
        if (state.isLoading) return

        // A soundbar on its HDMI input is not something to advertise as playing media: the
        // television is the source. Everything else is, idle rooms included — a play key
        // needs somewhere to land.
        nowPlaying.setPresenting(!state.onTvInput)

        // Never blank. An empty title renders as "Unknown" on the Google TV home screen —
        // its media card showed "Unknown · x2rock · Unknown" for any room without track
        // metadata, which is every idle room and every soundbar on TV audio. The room is
        // what this session is *about*, so it is the honest fallback for both lines.
        val title = state.trackName ?: state.groupName.takeIf { it.isNotBlank() }
        val subtitle = state.artistName ?: when {
            state.onTvInput -> state.inputFormat.takeIf { it.isNotBlank() } ?: "TV Audio"
            // The room's own word, as the list and pane say it: the transport's "Idle" told the
            // launcher's card nothing.
            else -> roomActivity(state.playbackState, state.hasSource).label
        }

        val metadataKey = "$title|$subtitle|${state.albumName}|${state.durationMillis}|${state.albumArtUrl}"
        if (metadataKey != lastMetadataKey) {
            lastMetadataKey = metadataKey
            nowPlaying.publish(title, subtitle, state.albumName, state.durationMillis, state.albumArtUrl)
        }

        val playing = state.playbackState.isPlaying()
        // Idle is a playback state, not the absence of a title. A soundbar on TV audio has
        // no track metadata and is still playing.
        val idle = !state.playbackState.hasLoadedContent()
        val code = if (idle) 0 else if (playing) 1 else 2
        // Seek the way the pane offers it: only with a duration to seek within.
        val actions = state.actions.copy(canSeek = state.actions.canSeek && state.durationMillis > 0)
        if (code == lastPbStateCode && state.positionMillis == lastPbPositionMillis && actions == lastPbActions) return
        lastPbStateCode = code
        lastPbPositionMillis = state.positionMillis
        lastPbActions = actions
        nowPlaying.publishState(playing, idle, state.positionMillis, actions)
    }

    // ------------------------------------------------------------ transport
    //
    // Fire and forget. The player answers with an event, and the flows above pick it up.

    fun togglePlayPause() = command("play or pause") { household.togglePlayPause(it) }

    /**
     * An explicit Play — the remote's own Play key, a voice "resume" — which a playing room ignores
     * rather than toggling into a pause.
     */
    fun play() {
        if (uiState.value.playbackState.isPlaying()) return
        command("play") { household.play(it) }
    }

    /**
     * An explicit Pause, which a room not playing ignores. A live stream cannot pause, only stop,
     * and the toggle is what stops it, as the pane's own button does.
     */
    fun pause() {
        val state = uiState.value
        if (!state.playbackState.isPlaying()) return
        command("pause") { if (state.actions.canPause) household.pause(it) else household.togglePlayPause(it) }
    }

    /** Silence the ringing alarm for nine minutes; it rings again after. */
    fun snoozeAlarm() = command("snooze the alarm") { household.snoozeAlarm(it) }

    /** End the ringing alarm. Pausing is what does that; deleting the alarm would not. */
    fun stopAlarm() = command("stop the alarm") { household.pause(it) }
    fun skipToNextTrack() = command("skip") { household.skipToNextTrack(it) }
    /**
     * Back a track, or back to the start of this one where the player has no previous track
     * to go to — see [PlaybackActions.canGoBack]. The remote's media key and the system's own
     * controls come through here too, so all three agree.
     */
    fun skipToPreviousTrack() {
        val actions = uiState.value.actions
        if (!actions.canSkipToPrevious && actions.canSeek) seekTo(0)
        else command("go back") { household.skipToPreviousTrack(it) }
    }

    // ------------------------------------------------------------ ratings
    //
    // Unlike the transport commands above, this isn't fire-and-forget: there's no pushed
    // event for a rating, and a failure here (an unsupported service, a Live broadcast with
    // nothing to rate) is worth saying rather than swallowing.

    fun rateUp() = rate(up = true)
    fun rateDown() = rate(up = false)

    private fun rate(up: Boolean) {
        val groupId = _groupId.value ?: return
        viewModelScope.launch {
            val outcome = runCatching { household.rate(groupId, up) }
            // Re-read rather than assumed: whether a second "up" clears the first is the
            // service's rule, and only its answer says which state the track is now in.
            if (outcome.isSuccess) loadRating(groupId)
            val message = outcome.fold(
                onSuccess = { outcome -> rateMessage(outcome) },
                onFailure = { it.message ?: "Could not rate this track" },
            )
            notice.post(message)
        }
    }

    /** What a rating did, in the words of the control pressed: a thumb, or Deezer's heart and ban. */
    private fun rateMessage(outcome: SonosHousehold.RateOutcome): String {
        val service = outcome.serviceName
        val said = when (outcome.stringId.uppercase()) {
            "SAVE_TRACK" -> "Added to your $service favourites"
            "DELETE_TRACK" -> "Removed from your $service favourites"
            "SKIP_TRACK" -> "$service won't play this again"
            else -> "Rated ${if (outcome.up) "up" else "down"} on $service"
        }
        return said + if (outcome.skipped) " — skipping" else ""
    }

    /** Debounced and accumulating, so holding skip moves once by the total, not once by one step. */
    fun seekBy(deltaMillis: Long) {
        val state = uiState.value
        val now = System.currentTimeMillis()
        val playing = state.playbackState.isPlaying()
        val pending = pendingSeekMillis
        val sentAt = pendingSentAt
        val base = when {
            pending != null && sentAt == null -> pending
            // Sent, and the speaker has not said where it is since: still the best answer, moved
            // on by however long it has been playing from there.
            pending != null && sentAt != null && state.positionUpdatedAt < sentAt ->
                pending + if (playing) now - sentAt else 0L
            else -> state.positionMillis + if (playing) now - state.positionUpdatedAt else 0L
        }
        seekTo(base + deltaMillis)
    }

    /**
     * Seek to [positionMillis] in the track: a tap on the progress bar. Debounced with [seekBy],
     * so a tap and then a press move from where the tap aimed, not from a position that has
     * not caught up yet.
     */
    fun seekTo(positionMillis: Long) {
        val groupId = _groupId.value ?: return
        val state = uiState.value
        val target = positionMillis
            .coerceIn(0, state.durationMillis.takeIf { it > 0 } ?: Long.MAX_VALUE)
        pendingSeekMillis = target
        pendingSentAt = null
        seekDebounceJob?.cancel()
        seekDebounceJob = viewModelScope.launch {
            delay(VOLUME_DEBOUNCE_MILLIS)
            val sent = runCatching { household.seek(groupId, target) }.onFailure { notice.failure("seek", it) }
            // Only if it is still ours. `runCatching` catches the CancellationException a newer
            // press throws in here, and this is not a suspension point, so touching it
            // unconditionally would overwrite the target that press just wrote. Sent, it waits
            // for the speaker's next position; refused, there is nothing to wait for.
            if (pendingSeekMillis == target) {
                if (sent.isSuccess) pendingSentAt = System.currentTimeMillis() else pendingSeekMillis = null
            }
        }
    }

    fun toggleMute() {
        if (uiState.value.volume == null) return
        val muted = !uiState.value.isMuted
        command(if (muted) "mute" else "unmute") { household.setGroupMute(it, muted) }
    }

    /** Debounced: a held D-pad key would otherwise send one command per repeat. */
    /**
     * A step, accumulated across presses and sent as one relative change after the debounce.
     *
     * Relative because a button says "louder", not a level (Sonos's rule for stateless
     * controls), and because it needs no baseline: the old absolute target was computed from
     * the last pushed level, so the buttons had to be disabled until one arrived and while
     * muted — and a disabled tv-material3 button still takes focus and draws nothing. A step
     * on a muted room unmutes it, as the player does for either setter.
     *
     * The total is taken and zeroed just before it is sent, so a newer press — which cancels
     * this job, possibly with the command already in flight — starts a new total rather than
     * resending this one or losing its own.
     */
    /**
     * What the room being left still owes: its volume steps and a seek waiting out the debounce,
     * sent to it now, and the pending state cleared. They used to stay with the view model, and
     * the next press in the newly selected room sent them there — +5 in one room then +1 in the
     * next arrived as +6, and a pending seek became the next room's baseline (outside review,
     * 2026-10-08). A seek already sent keeps nothing pending past here: its room has it.
     */
    private fun settlePending(leaving: String) {
        volumeDebounceJob?.cancel()
        val step = pendingVolumeDelta
        pendingVolumeDelta = 0
        if (step != 0) viewModelScope.launch {
            runCatching { household.adjustGroupVolume(leaving, step) }.onFailure { notice.failure("change the volume", it) }
        }
        val unsent = pendingSeekMillis?.takeIf { pendingSentAt == null && seekDebounceJob?.isActive == true }
        seekDebounceJob?.cancel()
        pendingSeekMillis = null
        pendingSentAt = null
        if (unsent != null) viewModelScope.launch {
            runCatching { household.seek(leaving, unsent) }.onFailure { notice.failure("seek", it) }
        }
    }

    fun adjustVolume(delta: Int) {
        val groupId = _groupId.value ?: return
        // The player refuses a level it does not control; say so instead of asking it.
        if (uiState.value.volumeFixed) return notice.post(FIXED_VOLUME)
        pendingVolumeDelta += delta
        volumeDebounceJob?.cancel()
        volumeDebounceJob = viewModelScope.launch {
            delay(VOLUME_DEBOUNCE_MILLIS)
            val step = pendingVolumeDelta
            pendingVolumeDelta = 0
            if (step == 0) return@launch
            runCatching { household.adjustGroupVolume(groupId, step) }.onFailure { notice.failure("change the volume", it) }
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

    private fun updatePlayMode(transform: (PlayModeState) -> PlayModeState) {
        val groupId = _groupId.value ?: return
        val current = PlayModeState(
            repeat = uiState.value.repeat,
            shuffle = uiState.value.shuffle,
            crossfade = uiState.value.crossfade,
        )
        viewModelScope.launch {
            runCatching { household.setPlayMode(groupId, transform(current)) }
                .onFailure { notice.failure("change the play mode", it) }
        }
    }

    /** One speaker's step, the same way as [adjustVolume]. */
    fun adjustPlayerVolume(playerId: String, delta: Int) {
        if (uiState.value.playerVolumes.any { it.playerId == playerId && it.fixed }) return notice.post(FIXED_VOLUME)
        pendingPlayerDeltas[playerId] = (pendingPlayerDeltas[playerId] ?: 0) + delta
        playerVolumeDebounceJobs[playerId]?.cancel()
        playerVolumeDebounceJobs[playerId] = viewModelScope.launch {
            delay(VOLUME_DEBOUNCE_MILLIS)
            val step = pendingPlayerDeltas.remove(playerId) ?: return@launch
            if (step == 0) return@launch
            runCatching { household.adjustPlayerVolume(playerId, step) }
                .onFailure { notice.failure("change that speaker's volume", it) }
        }
    }

    fun togglePlayerMute(playerId: String) {
        val current = uiState.value.playerVolumes.firstOrNull { it.playerId == playerId } ?: return
        viewModelScope.launch {
            runCatching { household.setPlayerMute(playerId, !current.muted) }
                .onFailure { notice.failure(if (current.muted) "unmute that speaker" else "mute that speaker", it) }
        }
    }

    // ------------------------------------------------------------ sleep timer
    //
    // Sonos's own timer, so the Sonos app and every other controller see it, and it outlives
    // this one: the room pauses itself when it fires. Set and cancelled over UPnP, the only
    // way there is; what it is comes from `sleepTimer:1`, which pushes every set and cancel
    // whoever made it — `x2rock sleep 15` was the case that showed it was needed. Counted down
    // where it is drawn, for the display alone: nothing is asked of the speaker on a timer.

    fun setSleepTimer(minutes: Int) = changeSleepTimer(minutes, "set the sleep timer")

    fun cancelSleepTimer() = changeSleepTimer(null, "cancel the sleep timer")

    private fun changeSleepTimer(minutes: Int?, what: String) {
        val groupId = _groupId.value ?: return
        viewModelScope.launch {
            runCatching { household.setSleepTimer(groupId, minutes) }.onFailure { notice.failure(what, it) }
        }
    }

    init {
        // The household fixes the end as each report arrives, on its own clock; it is moved
        // onto [clock] here, when it is seen — for a room selected long after its report as
        // much as for one that has just reported.
        viewModelScope.launch {
            combine(_groupId, household.groupStates) { id, all -> id to id?.let { all[it] } }
                .distinctUntilChanged { (a, x), (b, y) -> a == b && x?.sleepTimerEndsAt == y?.sleepTimerEndsAt }
                .collect { (_, state) -> _sleepEndsAt.value = state?.sleepTimerLeftMillis()?.let { clock.now() + it } }
        }
    }

    override fun onCleared() {
        // Detach, not release: the publisher is application-scoped and outlives this.
        nowPlaying.detach(controls)
        super.onCleared()
    }

    private fun command(what: String, block: suspend (String) -> Unit) {
        val groupId = _groupId.value ?: return
        viewModelScope.launch { runCatching { block(groupId) }.onFailure { notice.failure(what, it) } }
    }


    private companion object {
        const val VOLUME_DEBOUNCE_MILLIS = 300L
    }
}
