package com.rahga.x2rock.lan

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.rahga.x2rock.model.ContainerMetadata
import com.rahga.x2rock.model.Group
import com.rahga.x2rock.model.GroupVolume
import com.rahga.x2rock.model.FavoritesResponse
import com.rahga.x2rock.model.GroupsResponse
import com.rahga.x2rock.model.PlayModeState
import com.rahga.x2rock.model.PlaybackMetadata
import com.rahga.x2rock.model.PlaybackStates
import com.rahga.x2rock.model.QueueResponse
import com.rahga.x2rock.model.Player
import com.rahga.x2rock.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
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
    val volume: GroupVolume? = null,
    val playMode: PlayModeState = PlayModeState(),
)

/** The household as a whole. */
data class HouseholdState(
    val connected: Boolean = false,
    val householdId: String? = null,
    val groups: List<Group> = emptyList(),
    val players: List<Player> = emptyList(),
    /** Set when the household is unreachable. Withdraw the UI rather than showing stale state. */
    val error: String? = null,
)

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
        if (wantConnection) reconnect(immediate = true)
    }

    private suspend fun establish(seed: Discovery.DiscoveredPlayer? = null) {
        try {
            val entry = seed ?: multicast.around { Discovery.findPlayers() }.firstOrNull()
                ?: error("no Sonos players answered on this network")

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
    private fun reconnect(immediate: Boolean = false) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            teardown()
            var backoff = if (immediate) 0L else MIN_BACKOFF_MILLIS
            while (isActive) {
                if (backoff > 0) delay(backoff)
                val recovered = runCatching { establish() }.isSuccess
                if (recovered) return@launch
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
        socketJobs.forEach { it.cancel() }
        socketJobs.clear()
        // cancel(), not close(): this runs on the premise that the peer may be gone, and a
        // graceful close waits for a handshake a dead peer will never send.
        sockets.values.forEach { runCatching { it.cancel() } }
        sockets.clear()
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
        socketJobs.forEach { it.cancel() }
        socketJobs.clear()
        sockets.values.forEach { it.close() }
        sockets.clear()
        addressBook.clear()
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

    suspend fun favorites(): FavoritesResponse {
        val household = _state.value.householdId ?: error("not connected")
        val socket = sockets.values.firstOrNull() ?: error("not connected")
        val body = socket.command(Frames.onHousehold("favorites:1", "getFavorites", household))
        return gson.fromJson(body, FavoritesResponse::class.java) ?: FavoritesResponse()
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
        val socket = SonosSocket.open(client, hostname)
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

    private suspend fun subscribeGroup(group: Group) {
        val socket = socketForPlayer(group.coordinatorId)
        // Subscribing returns the current state as the first event, so there is no
        // separate "get" needed to seed the flow.
        listOf("playback:1", "playbackMetadata:1", "groupVolume:1").forEach { namespace ->
            socket.subscribe(Frames.onGroup(namespace, "subscribe", group.id))
        }
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
                    )
                }
            }

            "playbackMetadata:1" -> {
                if (groupId == null) return
                val meta = gson.fromJson(event.body, PlaybackMetadata::class.java) ?: return
                update(groupId) {
                    it.copy(
                        track = meta.currentItem?.track,
                        container = meta.container,
                        durationMillis = meta.currentItem?.track?.durationMillis ?: it.durationMillis,
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

internal fun nextBackoff(current: Long): Long =
    (if (current <= 0) MIN_BACKOFF_MILLIS else current * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
