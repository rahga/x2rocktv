package com.rahga.x2rock.lan

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.rahga.x2rock.model.ContainerMetadata
import com.rahga.x2rock.model.Group
import com.rahga.x2rock.model.GroupVolume
import com.rahga.x2rock.model.FavoritesResponse
import com.rahga.x2rock.model.GroupsResponse
import com.rahga.x2rock.model.PlayModeState
import com.rahga.x2rock.model.PlaybackActions
import com.rahga.x2rock.model.PlaybackMetadata
import com.rahga.x2rock.model.PlaybackStates
import com.rahga.x2rock.model.PlayerSettings
import com.rahga.x2rock.model.QueueResponse
import com.rahga.x2rock.model.Player
import com.rahga.x2rock.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetAddress
import java.net.URI

/** What is playing in one group. Every field arrives by push; nothing here is polled. */
data class GroupState(
    val playbackState: String = PlaybackStates.IDLE,
    val positionMillis: Long = 0,
    /** When [positionMillis] was last told to us, so a UI can extrapolate between events. */
    val positionUpdatedAt: Long = 0,
    val durationMillis: Long = 0,
    val track: Track? = null,
    val container: ContainerMetadata? = null,
    /** See [PlaybackMetadata.streamInfo]: the now-playing of a stream that has no track. */
    val streamInfo: String? = null,
    val volume: GroupVolume? = null,
    val playMode: PlayModeState = PlayModeState(),
    /** What the current source permits. See [PlaybackActions]. */
    val actions: PlaybackActions = PlaybackActions(),
    /**
     * Whether a `playbackMetadata:1` event has arrived for this group yet.
     *
     * Not the same as having an entry in the map at all: any of the three subscriptions
     * creates one, and [onTvInput] is derived from metadata alone. Anything waiting to judge
     * whether a room is on a TV input has to wait for *this*, or it reads "no" from a group
     * that has merely not answered yet.
     */
    val metadataSeen: Boolean = false,
) {
    /**
     * Whether this group is on a soundbar's TV input right now.
     *
     * The *presence* of the format is the signal, never its wording: with the television
     * off a player still reports the input, naming no codec and no channels.
     */
    val onTvInput: Boolean get() = container?.htInputFormat != null

    /**
     * A station rather than a queue of tracks — internet radio, or a service's own station.
     * Kept separate from [PlaybackActions] on purpose: what a source *is* decides the artwork,
     * what it *permits* decides the controls, and the two are different questions.
     */
    val isRadio: Boolean get() = container?.type == "station"

    /** e.g. "Silence 2.0", "Dolby Digital 5.1", or "No Signal" with the television off. */
    val inputFormat: String get() = container?.htInputFormat?.summary().orEmpty()
}

/** The household as a whole. */
data class HouseholdState(
    val connected: Boolean = false,
    val householdId: String? = null,
    val groups: List<Group> = emptyList(),
    val players: List<Player> = emptyList(),
    /** Set when the household is unreachable. Withdraw the UI rather than showing stale state. */
    val error: String? = null,
) {
    /**
     * Whether this group has a TV input to switch to at all.
     *
     * The HDMI socket belongs to a *player*, which need not be the one coordinating: a
     * soundbar that joined a speaker's group still has its own input, so every member is
     * asked, not just the coordinator.
     */
    fun hasTvInput(group: Group): Boolean {
        val byId = players.associateBy { it.id }
        return group.playerIds.any { HT_PLAYBACK in (byId[it]?.capabilities ?: emptyList()) }
    }
}

/** The capability a soundbar reports, and the only way to know a room can take a TV input. */
const val HT_PLAYBACK = "HT_PLAYBACK"

/**
 * A live view of one Sonos household over the LAN, and the commands that change it.
 *
 * This replaces polling entirely. [state] and [groupStates] are fed by subscriptions:
 * the speakers push, and a command's effect arrives as an event like any other change,
 * so there is nothing to re-fetch after acting and no interval to tune.
 *
 * One socket is opened per group coordinator, because group-scoped namespaces are only
 * answered by the coordinator. Player-scoped calls open that player's own socket.
 *
 * The **queue is the exception** and is asked for rather than pushed: `queue:1` and
 * `playbackQueue:1` answer `ERROR_UNSUPPORTED_NAMESPACE`, so it goes over UPnP on port
 * 1400 instead — see [Upnp], and expect it to be stale until re-read.
 *
 * A lost connection is rebuilt with capped exponential backoff, and [onNetworkChanged]
 * skips the wait when the ground has moved underneath it.
 */
class SonosHousehold(
    private val scope: CoroutineScope,
    private val addressBook: PlayerAddressBook = PlayerAddressBook(),
    /**
     * Held across SSDP. On Wi-Fi, Android drops multicast to the app unless a
     * `WifiManager.MulticastLock` is held, so discovery would silently find nothing; on
     * Ethernet it is irrelevant. `:core` cannot take that lock itself, so the platform
     * supplies it.
     */
    private val multicast: MulticastGate = MulticastGate.None,
    /**
     * Injected so the *same* client can be handed to the image loader: album art lives on
     * the players at cleartext `.local` URLs, and a loader with its own client would have
     * neither the address book nor permission to fetch them.
     */
    private val client: OkHttpClient = LanHttp.client(addressBook),
    /** Where the last reachable player is remembered, to skip discovery on a warm start. */
    private val seeds: SeedStore = SeedStore.None,
    /** Overridden only by tests, which reach a fake player on an ephemeral port. */
    private val port: Int = SonosSocket.PORT,
) {

    private val gson = Gson()
    private val upnp = Upnp(client)

    private val sockets = java.util.concurrent.ConcurrentHashMap<String, SonosSocket>()
    private val socketJobs = java.util.Collections.synchronizedList(mutableListOf<Job>())
    private val lock = Mutex()
    private var reconnectJob: Job? = null

    /** Set once someone asks for a connection, cleared only by [disconnect]. */
    @Volatile private var wantConnection = false

    /**
     * Group id to the coordinator we subscribed on its behalf.
     *
     * Anything else on the network — the Sonos app, another controller, a voice
     * assistant — can regroup this household at any moment, and a regroup mints *new*
     * group ids. Subscribing once at connect would leave every group formed afterwards
     * with no playback, metadata or volume subscription at all: present in the room list
     * and permanently frozen. The coordinator is tracked too, because a group can keep its
     * id while coordination moves to a different player, and the old socket would then be
     * the wrong one to have subscribed on.
     */
    private val subscribedGroups = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val resubscribeLock = Mutex()

    /**
     * Bumped by every teardown. A socket opened concurrently with one belongs to a session
     * that no longer exists, and closing it is the only way it does not leak past the
     * rebuild and later trigger a spurious reconnect.
     */
    @Volatile private var generation = 0

    private val _state = MutableStateFlow(HouseholdState())
    val state: StateFlow<HouseholdState> = _state.asStateFlow()

    private val _groupStates = MutableStateFlow<Map<String, GroupState>>(emptyMap())
    val groupStates: StateFlow<Map<String, GroupState>> = _groupStates.asStateFlow()

    /**
     * Per-speaker volume, which is a different thing from a group's volume and needs its
     * own subscription on each player's own socket.
     */
    private val _playerVolumes = MutableStateFlow<Map<String, GroupVolume>>(emptyMap())
    val playerVolumes: StateFlow<Map<String, GroupVolume>> = _playerVolumes.asStateFlow()

    // ---------------------------------------------------------------- lifecycle

    /**
     * Connects, learns the household, and subscribes to everything.
     *
     * [seed] is any player's address. With none, SSDP finds one — reaching a single player
     * is enough, because `getGroups` then reports where all the others are.
     */
    suspend fun connect(seed: Discovery.DiscoveredPlayer? = null) {
        if (_state.value.connected || reconnectJob?.isActive == true) return
        // Remembered so a *failed* first attempt is still recoverable: a TV box commonly
        // boots and starts this app before the LAN is up, and without this the network
        // coming back would be ignored and the user left on the error screen.
        wantConnection = true
        establish(seed)
    }

    /**
     * Called when the device changes network, or wakes.
     *
     * **Assume dead, reconnect from scratch.** A resumed TCP session can be a zombie that
     * accepts writes and never surfaces a failure, so nothing here waits for the socket to
     * admit it is gone — and a cached address is a DHCP hint, not a fact, so discovery
     * runs again rather than reusing what worked on the last network.
     */
    fun onNetworkChanged() {
        if (wantConnection) reconnect(immediate = true, fresh = true)
    }

    /**
     * A remembered player first, discovery second.
     *
     * The remembered one is tried without verifying it beyond opening a socket, because
     * that *is* the verification: if it has moved or this is a different network entirely,
     * the connect fails and discovery runs, which is both simpler and more reliable than
     * trying to decide in advance whether the memory is still good.
     */
    private suspend fun findEntryPoint(rediscover: Boolean = false): Discovery.DiscoveredPlayer {
        // After a network change the remembered address is not merely unverified, it is
        // probably wrong — and trying it first would burn the connect timeout before
        // discovery ever runs.
        if (!rediscover) {
            val remembered = withContext(Dispatchers.IO) { seeds.load() }
            val hostname = remembered?.hostname
            if (remembered != null && hostname != null) {
                addressBook.register(hostname, remembered.address)
                // Bounded: the client has no call timeout and a zero read timeout, so a
                // host that accepts the TCP connect and then says nothing would hang here
                // forever and discovery would never be reached.
                val reachable = runCatching {
                    withTimeout(PROBE_TIMEOUT_MILLIS) { socketForHostname(hostname) }
                }.isSuccess
                if (reachable) return remembered
                addressBook.forget(hostname)
            }
        }
        return multicast.around { Discovery.findPlayers(stopAfterFirst = true) }.firstOrNull()
            ?: error("no Sonos players answered on this network")
    }

    private suspend fun establish(
        seed: Discovery.DiscoveredPlayer? = null,
        rediscover: Boolean = false,
    ) {
        var fromMemory = false
        try {
            val entry = seed ?: findEntryPoint(rediscover).also {
                fromMemory = !rediscover && it.id == seeds.load()?.id
            }

            // Discovery reports the player's id and address together, so even the very
            // first connection is made to the name on the certificate. There is no point
            // in the flow where hostname verification has to be relaxed.
            val hostname = entry.hostname
                ?: error("cannot derive a certificate hostname for ${entry.id}")
            addressBook.register(hostname, entry.address)
            val seedSocket = socketForHostname(hostname)

            // SSDP already answered this; only ask a player if it somehow did not.
            val householdId = entry.householdId ?: seedSocket.householdId()

            val groups = gson.fromJson(
                seedSocket.command(Frames.onHousehold("groups:1", "getGroups", householdId)),
                GroupsResponse::class.java,
            )
            registerAddresses(groups)
            _state.update {
                it.copy(connected = true, householdId = householdId, groups = fillPlaybackState(groups.groups), players = groups.players, error = null)
            }

            // Topology changes arrive here: a group forming or breaking rewrites the list.
            // The socket is already being watched — socketForHostname does that on open,
            // before any subscribe, so no snapshot can be missed.
            seedSocket.subscribe(Frames.onHousehold("groups:1", "subscribe", householdId))

            // Worth remembering only once the whole session stood up, not merely because a
            // socket opened.
            withContext(Dispatchers.IO) { seeds.save(entry.copy(householdId = householdId)) }

            groups.groups.forEach { subscribeGroup(it) }
            // Per-speaker volume only matters once rooms are grouped, but subscribing up
            // front means the rows are already populated when a group forms.
            groups.players.forEach { player ->
                runCatching {
                    socketForPlayer(player.id)
                        .subscribe(Frames.onPlayer("playerVolume:1", "subscribe", player.id))
                }
            }
        } catch (e: Exception) {
            // A remembered player whose socket opened but whose session did not stand up is
            // still unusable — a household id that no longer exists, say, after a re-setup.
            // Without this the same dead memory is retried on every launch, forever.
            if (fromMemory) {
                withContext(Dispatchers.IO) { seeds.clear() }
            }
            _state.update { it.copy(connected = false, error = e.message ?: e.toString()) }
            throw e
        }
    }

    /**
     * Rebuilds the connection, with capped exponential backoff.
     *
     * Subscriptions do not survive a reconnect and there is no replay buffer, so this
     * tears everything down first and treats the fresh snapshot as truth rather than
     * merging it with what was there before an outage.
     */
    private fun reconnect(immediate: Boolean = false, fresh: Boolean = false) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            teardown()
            var backoff = if (immediate) 0L else MIN_BACKOFF_MILLIS
            var skipSeed = fresh
            while (isActive) {
                if (backoff > 0) delay(backoff)
                val recovered = runCatching { establish(rediscover = skipSeed) }.isSuccess
                if (recovered) return@launch
                // Only the first attempt after a network change ignores the remembered
                // address; if discovery then fails too, the memory is worth another try.
                skipSeed = false
                backoff = nextBackoff(backoff)
            }
        }
    }

    /** A socket died on its own. One loss is enough: the whole session is rebuilt. */
    private fun handleLoss(cause: Throwable) {
        if (reconnectJob?.isActive == true) return
        _state.update { it.copy(connected = false, error = cause.message ?: "connection lost") }
        reconnect()
    }

    private suspend fun teardown() = lock.withLock {
        generation++
        takeJobs().forEach { it.cancel() }
        // cancel(), not close(): this runs on the premise that the peer may be gone, and a
        // graceful close waits for a handshake a dead peer will never send.
        sockets.values.forEach { runCatching { it.cancel() } }
        sockets.clear()
        subscribedGroups.clear()
        addressBook.clear()
        _state.update { it.copy(connected = false) }
        _groupStates.value = emptyMap()
        _playerVolumes.value = emptyMap()
    }

    fun disconnect() {
        wantConnection = false
        reconnectJob?.cancel()
        reconnectJob = null
        generation++
        takeJobs().forEach { it.cancel() }
        sockets.values.forEach { it.close() }
        sockets.clear()
        addressBook.clear()
        // Cleared for the same reason [teardown] clears it: nothing should go on believing
        // it holds a subscription over a socket that no longer exists. `establish`
        // re-subscribes unconditionally, so this is latent today — but `resubscribe` running
        // against a stale map before a reconnect completes would skip every group in it.
        subscribedGroups.clear()
        _state.value = HouseholdState()
        _groupStates.value = emptyMap()
        _playerVolumes.value = emptyMap()
    }

    // ---------------------------------------------------------------- reads

    fun groupState(groupId: String): GroupState = _groupStates.value[groupId] ?: GroupState()

    fun playerName(playerId: String): String =
        _state.value.players.firstOrNull { it.id == playerId }?.name ?: playerId

    // ---------------------------------------------------------------- queue
    //
    // The one part of the UI that is asked rather than pushed: the Control API has no
    // queue at all, so this goes over UPnP on port 1400. See [Upnp].

    suspend fun queue(groupId: String): QueueResponse =
        upnp.browseQueue(coordinatorHostname(groupId))

    suspend fun removeFromQueue(groupId: String, trackNumber: Int) =
        upnp.removeFromQueue(coordinatorHostname(groupId), trackNumber)

    suspend fun skipToQueueItem(groupId: String, trackNumber: Int) =
        upnp.skipToQueueItem(coordinatorHostname(groupId), trackNumber)

    private fun coordinatorHostname(groupId: String): String {
        val group = _state.value.groups.firstOrNull { it.id == groupId }
            ?: error("no group $groupId")
        return PlayerNames.localHostname(group.coordinatorId)
            ?: error("cannot derive a hostname for ${group.coordinatorId}")
    }

    /**
     * Put this room on its soundbar's HDMI input, overriding whatever music it is playing.
     *
     * Fails only if the room has no soundbar in it. Everything else is deliberately not
     * awaited: when the soundbar is a *member* rather than the coordinator, taking the TV
     * hands coordination over, and the player we asked stops coordinating before it can
     * answer — so a lost reply there is the normal case, not a failure.
     *
     * Nothing needs to be polled to find out. The switch arrives as a `playbackMetadata:1`
     * event carrying `htInputFormat`, which is what [GroupState.onTvInput] already reads,
     * so the UI learns it the same way it learns everything else.
     */
    suspend fun useTvInput(groupId: String, preferSoundbar: String? = null) {
        val group = _state.value.groups.firstOrNull { it.id == groupId } ?: error("no group $groupId")
        // A group can hold more than one soundbar — party mode across a household with
        // three Beams is enough — and then "the first player with an HDMI socket" is an
        // arbitrary one. If the viewer has said which soundbar their television is, switch
        // that one; picking a different Beam would put the wrong room on the wrong TV.
        val soundbar = preferSoundbar
            ?.takeIf { it in group.playerIds && TvSoundbar.hasHdmi(it, _state.value) }
            ?: TvSoundbar.soundbarOf(group, _state.value)
            ?: error("${group.name} has no speaker with a TV input")
        try {
            upnp.useTvInput(coordinatorHostname(groupId), soundbar)
        } catch (e: IOException) {
            // A soundbar that already coordinates hands nothing over, so its answer is the
            // answer and an error from it is a real one. Caught narrowly: cancellation must
            // still propagate, and a room with no soundbar is a caller's mistake either way.
            if (soundbar == group.coordinatorId) throw e
        }
    }

    suspend fun favorites(): FavoritesResponse {
        val household = _state.value.householdId ?: error("not connected")
        val socket = sockets.values.firstOrNull() ?: error("not connected")
        val body = socket.command(Frames.onHousehold("favorites:1", "getFavorites", household))
        return gson.fromJson(body, FavoritesResponse::class.java) ?: FavoritesResponse()
    }

    /**
     * The group's metadata as the coordinator has it, for recording a fixture verbatim.
     *
     * Everything the app uses arrives by subscription instead; this exists so the live suite
     * can capture a shape rather than have someone write down what they assume it to be.
     */
    /** Companion to [metadataStatusBody], for capturing what a source says it permits. */
    internal suspend fun playbackStatusBody(groupId: String): JsonElement =
        coordinator(groupId).command(
            Frames.onGroup("playback:1", "getPlaybackStatus", groupId),
        )

    internal suspend fun metadataStatusBody(groupId: String): JsonElement =
        coordinator(groupId).command(
            Frames.onGroup("playbackMetadata:1", "getMetadataStatus", groupId),
        )

    /**
     * A soundbar's Night Sound and Speech Enhancement, which `playerVolume:1` does not
     * carry and which nothing pushes: `settings:1` takes a subscription and then stays
     * silent when the value changes, so this is the only way to learn one, and a write must
     * be followed by a re-read rather than awaited as an event.
     *
     * Player-scoped, so it goes to the soundbar's *own* socket rather than its coordinator's
     * — on a grouped room those are different players, and the coordinator answers
     * `ERROR_INVALID_OBJECT_ID`. Reading it for a speaker with no HDMI socket is not an
     * error, merely pointless: the block comes back inert.
     */
    suspend fun playerSettings(playerId: String): PlayerSettings =
        gson.fromJson(playerSettingsBody(playerId), PlayerSettings::class.java) ?: PlayerSettings()

    /**
     * The body as the player sent it. Split out so the live suite can record a fixture
     * verbatim rather than someone writing down what they assume the shape to be.
     */
    internal suspend fun playerSettingsBody(playerId: String): JsonElement =
        socketForPlayer(playerId).command(
            Frames.onPlayer("settings:1", "getPlayerSettings", playerId),
        )

    /** Night Sound, as the Sonos app names it. Leaves the Control API — see [Upnp.setEq]. */
    suspend fun setNightMode(playerId: String, on: Boolean) = setEq(playerId, "NightMode", on)

    /** Speech Enhancement, as the Sonos app names it. `DialogLevel` on the wire. */
    suspend fun setSpeechEnhancement(playerId: String, on: Boolean) = setEq(playerId, "DialogLevel", on)

    /**
     * Gated on the HDMI capability rather than left to the player, because the failure is
     * otherwise a bare UPnP 402 from a One SL with nothing to say which setting was refused.
     */
    private suspend fun setEq(playerId: String, eqType: String, on: Boolean) {
        require(TvSoundbar.hasHdmi(playerId, _state.value)) {
            "$eqType is a soundbar setting and $playerId has no TV input"
        }
        val hostname = PlayerNames.localHostname(playerId)
            ?: error("cannot derive a hostname for $playerId")
        upnp.setEq(hostname, eqType, on)
    }

    // ---------------------------------------------------------------- commands

    suspend fun play(groupId: String) = onGroup(groupId, "play")
    suspend fun pause(groupId: String) = onGroup(groupId, "pause")
    suspend fun togglePlayPause(groupId: String) = onGroup(groupId, "togglePlayPause")
    suspend fun skipToNextTrack(groupId: String) = onGroup(groupId, "skipToNextTrack")
    suspend fun skipToPreviousTrack(groupId: String) = onGroup(groupId, "skipToPreviousTrack")

    suspend fun seek(groupId: String, positionMillis: Long) {
        coordinator(groupId).command(
            Frames.onGroup("playback:1", "seek", groupId),
            JsonObject().apply { addProperty("positionMillis", positionMillis) },
        )
    }

    suspend fun setPlayMode(groupId: String, mode: PlayModeState) {
        coordinator(groupId).command(
            Frames.onGroup("playback:1", "setPlayModes", groupId),
            PlayModes.toBody(mode),
        )
    }

    suspend fun setGroupVolume(groupId: String, volume: Int) {
        coordinator(groupId).command(
            Frames.onGroup("groupVolume:1", "setVolume", groupId),
            JsonObject().apply { addProperty("volume", volume.coerceIn(0, 100)) },
        )
    }

    suspend fun setGroupMute(groupId: String, muted: Boolean) {
        coordinator(groupId).command(
            Frames.onGroup("groupVolume:1", "setMute", groupId),
            JsonObject().apply { addProperty("muted", muted) },
        )
    }

    /** Player-scoped: this must go to the player's own socket, not its coordinator's. */
    suspend fun setPlayerVolume(playerId: String, volume: Int) {
        socketForPlayer(playerId).command(
            Frames.onPlayer("playerVolume:1", "setVolume", playerId),
            JsonObject().apply { addProperty("volume", volume.coerceIn(0, 100)) },
        )
    }

    suspend fun setPlayerMute(playerId: String, muted: Boolean) {
        socketForPlayer(playerId).command(
            Frames.onPlayer("playerVolume:1", "setMute", playerId),
            JsonObject().apply { addProperty("muted", muted) },
        )
    }

    suspend fun loadFavorite(groupId: String, favoriteId: String, playOnCompletion: Boolean = true) {
        coordinator(groupId).command(
            Frames.onGroup("favorites:1", "loadFavorite", groupId),
            JsonObject().apply {
                addProperty("favoriteId", favoriteId)
                addProperty("playOnCompletion", playOnCompletion)
            },
        )
    }

    suspend fun modifyGroupMembers(groupId: String, add: List<String>, remove: List<String>) {
        coordinator(groupId).command(
            Frames.onGroup("groups:1", "modifyGroupMembers", groupId),
            JsonObject().apply {
                add("playerIdsToAdd", gson.toJsonTree(add))
                add("playerIdsToRemove", gson.toJsonTree(remove))
            },
        )
    }

    private suspend fun onGroup(groupId: String, command: String) {
        coordinator(groupId).command(Frames.onGroup("playback:1", command, groupId))
    }

    // ---------------------------------------------------------------- sockets

    private suspend fun SonosSocket.householdId(): String =
        // No command asks this, but every reply header answers it, so the cheapest question
        // is a deliberately invalid one. It fails by design.
        send(JsonObject()).header.householdId
            ?: error("player did not report a householdId")

    /** The coordinator's socket, opened on first use and reused after. */
    private suspend fun coordinator(groupId: String): SonosSocket {
        val group = _state.value.groups.firstOrNull { it.id == groupId }
            ?: error("no group $groupId")
        return socketForPlayer(group.coordinatorId)
    }

    private suspend fun socketForPlayer(playerId: String): SonosSocket {
        val name = PlayerNames.localHostname(playerId)
            ?: error("cannot derive a certificate hostname for $playerId")
        return socketForHostname(name)
    }

    private suspend fun socketForHostname(hostname: String): SonosSocket = lock.withLock {
        sockets[hostname]?.let { return it }
        val opened = generation
        val socket = SonosSocket.open(client, hostname, port)
        // A teardown that happened while the handshake was in flight means this socket
        // belongs to a dead session; adopting it would resurrect it into a just-cleared map.
        if (opened != generation) {
            socket.cancel()
            throw java.io.IOException("connection torn down while opening $hostname")
        }
        sockets[hostname] = socket
        watch(socket)
        return socket
    }

    /**
     * Empties the job list under its own lock and hands back a snapshot.
     *
     * `Collections.synchronizedList` synchronises each *operation*, not iteration — a bare
     * `forEach` over it throws `ConcurrentModificationException` when the reconnect loop
     * appends a watch job at the same moment, which is exactly what `disconnect()` used to
     * do. Taking a copy first means the cancelling happens outside the lock, too.
     */
    private fun takeJobs(): List<Job> = synchronized(socketJobs) {
        socketJobs.toList().also { socketJobs.clear() }
    }

    /** Routes one socket's events into the state flows, and its death into a reconnect. */
    private fun watch(socket: SonosSocket) {
        // UNDISPATCHED so the collector is attached before this returns. `events` has no
        // replay and drops when nobody is listening, so a dispatched launch could lose the
        // race against a subscribe reply and its snapshot.
        socketJobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // One malformed frame from one speaker must not take the process down: an
            // uncaught throw here reaches the thread's default handler, not the scope.
            socket.events.collect { event -> runCatching { apply(event) } }
        }
        socketJobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            socket.failures.collect { cause -> handleLoss(cause) }
        }
    }

    /**
     * Brings subscriptions in line with a topology someone else may have changed.
     *
     * Only what actually moved is touched: an unchanged group keeps its subscription, so a
     * regroup elsewhere in the house costs nothing here. Vanished groups are forgotten so
     * that an id reappearing later — Sonos reuses them — is treated as new.
     */
    private suspend fun resubscribe() = resubscribeLock.withLock {
        // Read the topology here rather than taking it as a parameter, and under the lock.
        // These runs used to be launched per `groups:1` event with no ordering between them,
        // and each one suspends for three round-trips per group — so two events arriving
        // close together (which the room panel invites: "join" leaves it open for several
        // presses) could interleave. Whichever run held the *older* snapshot would then see
        // the newer group ids as "gone", drop their subscriptions and blank their rows.
        // `_state` is written before this is scheduled, so it is never older than the event.
        val current = _state.value
        val live = current.groups.associateBy { it.id }

        subscribedGroups.keys.filter { it !in live }.forEach { gone ->
            subscribedGroups.remove(gone)
            _groupStates.update { it - gone }
        }

        // Best-effort, but not once-only: a group left unsubscribed here would sit in the
        // sidebar permanently frozen — no playback, metadata or volume — which is the very
        // failure this function exists to prevent, and nothing else would retry it until the
        // next topology change, which may never come.
        val failed = live.values.filter { group ->
            subscribedGroups[group.id] != group.coordinatorId &&
                runCatching { subscribeGroup(group) }.isFailure
        }
        if (failed.isNotEmpty()) {
            delay(RESUBSCRIBE_RETRY_MILLIS)
            failed.forEach { group ->
                if (subscribedGroups[group.id] != group.coordinatorId) {
                    runCatching { subscribeGroup(group) }
                }
            }
        }

        // A player can arrive with a regroup — a speaker taken out of standby, say.
        current.players.forEach { player ->
            runCatching {
                socketForPlayer(player.id)
                    .subscribe(Frames.onPlayer("playerVolume:1", "subscribe", player.id))
            }
        }
    }

    private suspend fun subscribeGroup(group: Group) {
        val socket = socketForPlayer(group.coordinatorId)
        // Subscribing returns the current state as the first event, so there is no
        // separate "get" needed to seed the flow.
        listOf("playback:1", "playbackMetadata:1", "groupVolume:1").forEach { namespace ->
            socket.subscribe(Frames.onGroup(namespace, "subscribe", group.id))
        }
        subscribedGroups[group.id] = group.coordinatorId
    }

    private fun registerAddresses(groups: GroupsResponse) {
        groups.players.forEach { player ->
            val name = PlayerNames.localHostname(player.id) ?: return@forEach
            val host = player.websocketUrl?.let { runCatching { URI(it).host }.getOrNull() } ?: return@forEach
            addressBook.register(name, host)
        }
    }

    // ---------------------------------------------------------------- events

    private fun apply(event: SonosEvent) {
        val groupId = event.header.groupId
        when (event.namespace) {
            "groups:1" -> {
                val groups = gson.fromJson(event.body, GroupsResponse::class.java) ?: return
                registerAddresses(groups)
                _state.update { it.copy(groups = fillPlaybackState(groups.groups), players = groups.players) }
                // Someone regrouped. Catch up rather than sit on subscriptions for groups
                // that no longer exist.
                scope.launch { runCatching { resubscribe() } }
            }

            "playback:1" -> {
                if (groupId == null) return
                val body = event.body.asJsonObject
                // Keep the groups list in step. It carries its own playbackState, which only
                // a groups:1 event would otherwise refresh — leaving a room list showing
                // "Playing" for something that stopped seconds ago.
                body.string("playbackState")?.let { pushed ->
                    _state.update { state ->
                        state.copy(
                            groups = state.groups.map {
                                if (it.id == groupId) it.copy(playbackState = pushed) else it
                            }
                        )
                    }
                }
                val position = body.long("positionMillis")
                update(groupId) {
                    it.copy(
                        playbackState = body.string("playbackState") ?: it.playbackState,
                        positionMillis = position ?: it.positionMillis,
                        // Only move the clock when a position actually arrived. The UI keys
                        // its extrapolation off this, so refreshing it regardless makes the
                        // progress bar visibly jump back to the last reported position.
                        positionUpdatedAt = if (position != null) System.currentTimeMillis() else it.positionUpdatedAt,
                        durationMillis = body.long("durationMillis") ?: it.durationMillis,
                        // Absent means unchanged, not off — every other field here falls
                        // back the same way, and defaulting would silently clear shuffle.
                        playMode = body.getAsJsonObject("playModes")
                            ?.let { modes -> PlayModes.fromJson(modes) }
                            ?: it.playMode,
                        // Absent means unchanged, for the same reason as playModes above.
                        actions = body.getAsJsonObject("availablePlaybackActions")
                            ?.let { a -> gson.fromJson(a, PlaybackActions::class.java) }
                            ?: it.actions,
                    )
                }
            }

            "playbackMetadata:1" -> {
                if (groupId == null) return
                val meta = gson.fromJson(event.body, PlaybackMetadata::class.java) ?: return
                val coordinator = _state.value.groups.firstOrNull { it.id == groupId }?.coordinatorId
                val track = meta.currentItem?.track
                    ?.let { t -> t.copy(imageUrl = reachableArt(t.imageUrl, coordinator)) }
                val container = meta.container
                    ?.let { c -> c.copy(imageUrl = reachableArt(c.imageUrl, coordinator)) }
                update(groupId) {
                    it.copy(
                        track = track,
                        container = container,
                        // Blank is absent: a station between titles sends an empty string,
                        // and an empty headline renders worse than the room's own state.
                        streamInfo = meta.streamInfo?.trim()?.takeIf { info -> info.isNotEmpty() },
                        metadataSeen = true,
                        // No carry-over here, unlike `playback:1` above: a metadata event is
                        // the whole statement of what is loaded, so no track means no
                        // duration. Keeping the last one left a soundbar switched to its TV
                        // input still showing the progress bar of the song it interrupted —
                        // seen on hardware, because a TV input has no track at all.
                        durationMillis = track?.durationMillis ?: 0,
                        positionMillis = if (track == null) 0 else it.positionMillis,
                        // Stamped with the zero, not left on the old reading. Consumers
                        // extrapolate as position + (now - positionUpdatedAt), so a fresh 0
                        // against a minutes-old stamp reads as minutes elapsed — and a
                        // `+30s` press would seek from there.
                        positionUpdatedAt =
                            if (track == null) System.currentTimeMillis() else it.positionUpdatedAt,
                    )
                }
            }

            "groupVolume:1" -> {
                if (groupId == null) return
                val volume = gson.fromJson(event.body, GroupVolume::class.java) ?: return
                update(groupId) { it.copy(volume = volume) }
            }

            "playerVolume:1" -> {
                val playerId = event.header.playerId ?: return
                val volume = gson.fromJson(event.body, GroupVolume::class.java) ?: return
                _playerVolumes.update { it + (playerId to volume) }
            }
        }
    }

    private fun fillPlaybackState(groups: List<Group>): List<Group> =
        fillPlaybackState(groups, _groupStates.value, _state.value.groups)

    /**
     * Points player-served art at the player's `.local` name.
     *
     * Album art for LAN content is served by the speaker itself, and the Control API gives
     * it as `http://<ip>:1400/getaa?…`. That is cleartext to a bare address, which Android
     * refuses: the exemption in `network_security_config.xml` covers `.local` names only,
     * deliberately. Left alone, every piece of album art fails to load with nothing on
     * screen to say why.
     *
     * Service-hosted art (`https://…`) is returned untouched — it needs none of this.
     *
     * @param coordinatorId the player that reported the art, needed to resolve a relative
     *   path. Without one a relative path cannot be reached at all, so it becomes null
     *   rather than a URL that will quietly fail.
     */
    internal fun reachableArt(url: String?, coordinatorId: String? = null): String? {
        if (url.isNullOrEmpty()) return url

        // Some art arrives as a bare path — "/getaa?s=1&u=…" — which is served by the
        // player that reported it, not by anything the app can resolve on its own.
        if (url.startsWith("/")) {
            val host = coordinatorId?.let { PlayerNames.localHostname(it) } ?: return null
            return "http://$host:${Upnp.PORT}$url"
        }

        if (!url.startsWith("http://")) return url
        val host = runCatching { URI(url).host }.getOrNull() ?: return url
        val hostname = _state.value.players
            .firstOrNull { player ->
                player.websocketUrl?.let { runCatching { URI(it).host }.getOrNull() } == host
            }
            ?.let { PlayerNames.localHostname(it.id) }
            ?: return url
        return url.replaceFirst(host, hostname)
    }

    private fun update(groupId: String, block: (GroupState) -> GroupState) {
        _groupStates.update { all ->
            all + (groupId to block(all[groupId] ?: GroupState()))
        }
    }

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.long(name: String): Long? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asLong
}

/**
 * A `groups:1` event omits `playbackState`, though the `getGroups` reply carries it — so
 * a group's state has to be recovered rather than read.
 *
 * Preference order: whatever the event did say, then the group's own `playback:1`
 * subscription (more current than the groups list anyway), then the last value known for
 * that group, and only then idle. Pure so the precedence can be tested without a socket.
 */
internal fun fillPlaybackState(
    groups: List<Group>,
    pushed: Map<String, GroupState>,
    previous: List<Group>,
): List<Group> {
    val lastKnown = previous.associate { it.id to it.playbackState }
    return groups.map { group ->
        group.copy(
            playbackState = group.playbackState
                ?: pushed[group.id]?.playbackState
                ?: lastKnown[group.id]
                ?: PlaybackStates.IDLE
        )
    }
}

/** Matches the Rust daemon's curve: start at a second, double, stop at a minute. */
internal const val MIN_BACKOFF_MILLIS = 1_000L
internal const val MAX_BACKOFF_MILLIS = 60_000L

/** One retry for a group whose subscribe failed, before leaving it to the next topology event. */
internal const val RESUBSCRIBE_RETRY_MILLIS = 1_000L

/** A remembered address gets this long to prove itself before discovery takes over. */
internal const val PROBE_TIMEOUT_MILLIS = 3_000L

internal fun nextBackoff(current: Long): Long =
    (if (current <= 0) MIN_BACKOFF_MILLIS else current * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
