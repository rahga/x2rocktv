package com.rahga.x2rock.lan

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.rahga.x2rock.apple.AppleMusic
import com.rahga.x2rock.apple.AppleMusicItem
import com.rahga.x2rock.model.ContainerMetadata
import com.rahga.x2rock.model.Group
import com.rahga.x2rock.model.GroupVolume
import com.rahga.x2rock.model.FavoritesResponse
import com.rahga.x2rock.model.GroupsResponse
import com.rahga.x2rock.model.PlayModeState
import com.rahga.x2rock.model.PlaybackActions
import com.rahga.x2rock.model.PlaybackMetadata
import com.rahga.x2rock.model.PlaybackStates
import com.rahga.x2rock.model.PlaybackError
import com.rahga.x2rock.model.PlayerSettings
import com.rahga.x2rock.model.PlaylistsResponse
import com.rahga.x2rock.model.HistoryItem
import com.rahga.x2rock.model.HistoryResponse
import com.rahga.x2rock.model.QueueResponse
import com.rahga.x2rock.model.Player
import com.rahga.x2rock.model.isPlaying
import com.rahga.x2rock.model.Track
import com.rahga.x2rock.model.TruePlay
import com.rahga.x2rock.model.MusicObjectId
import com.rahga.x2rock.smapi.AccountCapture
import com.rahga.x2rock.smapi.Auth
import com.rahga.x2rock.smapi.Category
import com.rahga.x2rock.smapi.Item
import com.rahga.x2rock.smapi.ItemPage
import com.rahga.x2rock.smapi.LinkedService
import com.rahga.x2rock.smapi.RatingsCatalogue
import com.rahga.x2rock.smapi.RatingsMatch
import com.rahga.x2rock.smapi.RatingsStore
import com.rahga.x2rock.smapi.Service
import com.rahga.x2rock.smapi.SmapiClient
import com.rahga.x2rock.smapi.StoredAccounts
import com.rahga.x2rock.smapi.Thumb
import com.rahga.x2rock.smapi.linkedServices
import com.rahga.x2rock.smapi.parseServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.IOException
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
    /**
     * The last thing this room failed to play, until it plays again. A failed stream drops
     * the room to IDLE and says why only in a `playbackError`, so without this the pane
     * shows an idle room and nothing about the music having stopped.
     */
    val lastError: PlaybackError? = null,
    /**
     * `playbackStatus`'s `queueVersion` — sent by this firmware ("8" at home, "QV:00019" on
     * the office One SL), though x2rock found none on its own. It moves on every queue edit
     * and arrives as a playback event, idle room or not (verified at home, 2026-10-01), which
     * is what the queue screen keeps itself current by.
     */
    val queueVersion: String? = null,
    /**
     * The id of the alarm ringing in this group, while it rings. Not pushed: asked for when the
     * room starts playing, which is how an alarm starts, and dropped when it stops — a snoozed
     * alarm is still "running" to the player but is not ringing, and is offered nothing here.
     */
    val ringingAlarm: Int? = null,
    /**
     * How many times this group has started playing. A waiter compares it rather than watching
     * for the state to leave PLAYING and come back: updates close together reach a collector
     * merged, and BUFFERING then PLAYING can arrive as PLAYING alone.
     */
    val playStarts: Int = 0,
    /**
     * When the sleep timer runs out, in milliseconds of [monotonicMillis], or null for none.
     *
     * Pushed: `sleepTimer:1` is in no published reference, and subscribing to it was found to
     * send a `sleepTimerStatus` at once and again on every set and cancel, whoever made it
     * (office One SL, 2026-10-05). A report says what was left *when it was sent* and is not
     * re-sent as the time runs down, so the end is fixed here as it arrives: a room looked at
     * ten minutes after its report still shows the right time, and the same length set again
     * later is a later end. Read it through [sleepTimerLeftMillis].
     */
    val sleepTimerEndsAt: Long? = null,
) {
    /** What is left on the sleep timer now, or null for none or one that has run out. */
    fun sleepTimerLeftMillis(now: Long = monotonicMillis()): Long? =
        sleepTimerEndsAt?.let { it - now }?.takeIf { it > 0 }

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

    /**
     * The room's picture: the track's art, else the container's. A station sends a logo on the
     * container and nothing on the track — iHeartRadio does, captured — so taking the track's
     * alone left a radio room with none. The room list and the home-screen tiles both use this,
     * so they cannot disagree.
     */
    val artUrl: String? get() = track?.imageUrl ?: container?.imageUrl

    /** e.g. "Silence 2.0", "Dolby Digital 5.1", or "No Signal" with the television off. */
    val inputFormat: String get() = container?.htInputFormat?.summary().orEmpty()

    /**
     * Whether the current track carries an id a service could rate — the half of "can this
     * be rated" knowable from metadata alone, and the check [SonosHousehold.ratingState] makes
     * before it leaves the LAN. A Live broadcast's track has none (only its station container
     * does, and stations are not offered for rating — verified against a real household,
     * 2026-09-12). It is not the answer by itself: Plex tracks carry real ids and publish no
     * ratings. Draw buttons from [SonosHousehold.ratingState], never from this.
     */
    val hasTrackId: Boolean get() = track?.id?.isReal == true
}

/** The household as a whole. */
data class HouseholdState(
    val connected: Boolean = false,
    val householdId: String? = null,
    val groups: List<Group> = emptyList(),
    val players: List<Player> = emptyList(),
    /** Set when the household is unreachable. Withdraw the UI rather than showing stale state. */
    val error: String? = null,
    /**
     * The speaker this session ran through went away, and another is being tried. Not an
     * error, and not shown as one: unplugging that speaker — the first found, so on a TV box
     * often its own room's — used to put "websocket … failed" on screen for the seconds a
     * reconnect takes (Shield, Living Room pulled from a group, 2026-10-05). [error] is set
     * only if that reconnect fails.
     */
    val reconnecting: Boolean = false,
    /**
     * The speakers answered and refused: the household has Authentication switched on in the
     * Sonos app's Connection Security, and this app cannot sign in. [error] then says so,
     * naming the switch. Cleared by the next session that stands up — turning the switch off
     * is the fix, and a retry is all it takes.
     */
    /**
     * The household has UPnP switched off in the Sonos app's Connection Security, so every
     * SOAP call on port 1400 answers 403: the queue, the TV input and the Night Sound and
     * Speech Enhancement writes. True only once a player has said so; unknown reads as on,
     * because a switch that could not be read must not withdraw anything.
     */
    val upnpOff: Boolean = false,
    /**
     * Bonded speakers that have dropped off, by the room they belong to: the room's own player
     * id — the one `getGroups` lists — to how many of its satellites, surrounds or Sub report
     * `disconnected`. Such a speaker is not a player in the Control API at all, so nothing else
     * says it has gone: the room plays on, quieter, without saying why. From `zones:1`.
     */
    val offlineSpeakers: Map<String, Int> = emptyMap(),
    /**
     * Each soundbar's `hdmi:1` `connection`, by player id: `CONNECTED`, or `NO_CONNECTION`
     * for an HDMI port with nothing in it (Guest TV's Beam, captured 2026-10-02). A soundbar
     * not yet heard from is absent, and counts as connected.
     */
    val hdmiConnection: Map<String, String> = emptyMap(),
    /**
     * More than one Sonos system answered on this network and none is remembered, so the app
     * must ask which — x2rock's rule is never to guess between households. Empty otherwise.
     * Picking one is [SonosHousehold.chooseHousehold].
     */
    val householdChoices: List<HouseholdChoice> = emptyList(),
) {
    /** Every Control API command is refused: the Sonos app's Authentication switch is on. */
    val authenticationRequired: Boolean get() = error == AUTHENTICATION_REQUIRED

    /**
     * Whether this group has a TV input to switch to at all.
     *
     * The HDMI socket belongs to a *player*, which need not be the one coordinating: a
     * soundbar that joined a speaker's group still has its own input, so every member is
     * asked, not just the coordinator.
     */
    fun hasTvInput(group: Group): Boolean {
        val byId = players.associateBy { it.id }
        return group.playerIds.any {
            HT_PLAYBACK in (byId[it]?.capabilities ?: emptyList()) && hdmiConnection[it] != NO_HDMI_CONNECTION
        }
    }
}

/**
 * One of several households found on this network. Households have no names of their own, so
 * [label] is a room in it, as that player's `/status/zp` names it.
 */
data class HouseholdChoice(val householdId: String, val label: String, val seed: Discovery.DiscoveredPlayer)

/** Connecting stopped to ask which household: see [HouseholdState.householdChoices]. */
class HouseholdChoiceNeeded(count: Int) :
    IllegalStateException("$count Sonos systems answer on this network. Choose one.")

/** The capability a soundbar reports, and the only way to know a room can take a TV input. */
const val HT_PLAYBACK = "HT_PLAYBACK"

/**
 * `hdmi:1`'s word for a soundbar's HDMI port with nothing in it. Such a room has an input in
 * principle and no television in fact, so it gets no TV badge, no Source row and is no
 * candidate for "the television's room" — but keeps Night Sound and Speech Enhancement, which
 * are the soundbar's and do not need a TV. Hence [TvSoundbar.hasHdmi] does not consult this.
 */
const val NO_HDMI_CONNECTION = "NO_CONNECTION"

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
    /** Where to look when SSDP finds nothing. See [MdnsDiscovery]. */
    private val mdns: MdnsDiscovery = MdnsDiscovery.None,
    /**
     * Injected so the *same* client can be handed to the image loader: album art lives on
     * the players at cleartext `.local` URLs, and a loader with its own client would have
     * neither the address book nor permission to fetch them.
     */
    private val client: OkHttpClient = LanHttp.client(addressBook),
    /** Where the last reachable player is remembered, to skip discovery on a warm start. */
    private val seeds: SeedStore = SeedStore.None,
    /**
     * A plain, internet-facing client for [rate] — never [client]. That one trusts a
     * leaf-only certificate chain built for the players' own LAN handshake, which has no
     * business being extended to Sonos's CDN or a music service's own endpoint.
     */
    internetClient: OkHttpClient = OkHttpClient(),
    /** Where a service's rating rules are remembered between presses. */
    ratingsStore: RatingsStore = RatingsStore.None,
    /** Overridden only by tests, which reach a fake player on an ephemeral port. */
    private val port: Int = SonosSocket.PORT,
    /** Likewise for cleartext UPnP, which a test answers from a separate fake. */
    upnpPort: Int = Upnp.PORT,
    /**
     * How long an unanswered change is given to show up anyway. A Beam stalled for 14–20s
     * and then applied a regroup; TV audio arrives in 4–5s and its format by ~9s.
     * Overridden only by tests, which cannot wait that long for a failure.
     */
    private val settleMillis: Long = UNANSWERED_SETTLE_MILLIS,
    /** The SSDP sweep. Overridden only by tests, so a cold connect sends no real M-SEARCH. */
    private val ssdp: suspend () -> List<Discovery.DiscoveredPlayer> = { Discovery.findPlayers() },
) {

    private val gson = Gson()
    private val upnp = Upnp(client, upnpPort)
    private val smapi = SmapiClient(internetClient)
    private val ratingsCatalogue = RatingsCatalogue(smapi, ratingsStore)

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
    /** Players whose own subscriptions stand: see [subscribePlayer]. */
    private val subscribedPlayers: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val resubscribeLock = Mutex()

    /**
     * Bumped by every teardown. A socket opened concurrently with one belongs to a session
     * that no longer exists, and closing it is the only way it does not leak past the
     * rebuild and later trigger a spurious reconnect.
     */
    @Volatile private var generation = 0

    /** The socket `groups:1` is subscribed on. Losing it loses the topology itself. */
    @Volatile private var seedHostname: String? = null
    /** The player the UPnP switch is read from and followed on: the seed. */
    @Volatile private var seedPlayerId: String? = null
    /** The `security` settings group's timestamp at the last read; an event naming another means re-read. */
    @Volatile private var securityVersion: String? = null

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
     * Connect to the household the viewer picked from [HouseholdState.householdChoices]. Its
     * player is remembered on success, as any connect's is, so the question is not asked again.
     */
    suspend fun chooseHousehold(choice: HouseholdChoice) {
        _state.update { it.copy(householdChoices = emptyList(), error = null) }
        connect(choice.seed)
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
    private suspend fun findEntryPoint(
        rediscover: Boolean = false,
        /** A speaker's hostname known to have just gone; neither remembered nor discovered is it tried. */
        avoid: String? = null,
    ): Discovery.DiscoveredPlayer {
        // After a network change the remembered address is not merely unverified, it is
        // probably wrong — and trying it first would burn the connect timeout before
        // discovery ever runs.
        // A store keyed by network answers for the new one, so its memory is worth trying.
        if (!rediscover || seeds.keyedByNetwork) {
            val remembered = withContext(Dispatchers.IO) { seeds.load() }
            val hostname = remembered?.hostname
            // The speaker that just went is usually the one remembered — the session ran
            // through it — and an unplugged one does not refuse, it says nothing: probing it
            // cost the whole probe timeout before discovery began.
            if (remembered != null && hostname != null && !hostname.equals(avoid, ignoreCase = true)) {
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
        // SSDP first: one reply carries everything, in well under a second where it works.
        // Where a network drops it, mDNS carries the same three facts. Either way the whole
        // window is waited out rather than the first reply taken, because a network can hold
        // two households — an office running two systems — and the first to answer is not a
        // choice. Only a cold start pays for that: a remembered player skips this.
        // The speaker that went is passed over only when another answers. Where it is the only
        // one — the office's single One SL — and it is back, it is the answer; skipping it then
        // left a one-speaker household unable to reconnect at all.
        val found = multicast.around { ssdp() }
            .ifEmpty { runCatching { mdns.find(MDNS_TIMEOUT_MILLIS) }.getOrDefault(emptyList()) }
            .let { all -> all.filterNot { it.hostname.equals(avoid, ignoreCase = true) }.ifEmpty { all } }
        val households = found.filter { it.householdId != null }.groupBy { it.householdId!! }
        if (households.size > 1) {
            val choices = households.map { (household, players) ->
                val seed = players.first()
                seed.hostname?.let { addressBook.register(it, seed.address) }
                val label = seed.hostname?.let { runCatching { upnp.zoneName(it) }.getOrNull() } ?: seed.address.hostAddress
                HouseholdChoice(household, label, seed)
            }.sortedBy { it.label }
            _state.update { it.copy(householdChoices = choices) }
            throw HouseholdChoiceNeeded(choices.size)
        }
        return found.firstOrNull() ?: error("no Sonos players answered on this network")
    }

    private suspend fun establish(
        seed: Discovery.DiscoveredPlayer? = null,
        rediscover: Boolean = false,
        avoid: String? = null,
    ) {
        var fromMemory = false
        try {
            val entry = seed ?: findEntryPoint(rediscover, avoid).also {
                fromMemory = (!rediscover || seeds.keyedByNetwork) && it.id == seeds.load()?.id
            }

            // Discovery reports the player's id and address together, so even the very
            // first connection is made to the name on the certificate. There is no point
            // in the flow where hostname verification has to be relaxed.
            val hostname = hostnameOf(entry.id)
            addressBook.register(hostname, entry.address)
            val seedSocket = socketForHostname(hostname)
            seedHostname = hostname

            // SSDP already answered this. A seed found any other way asks the player's
            // /status/zp — the socket's own way of asking was ignored by a One SL.
            val householdId = entry.householdId
                ?: upnp.householdId(hostname)
                ?: error("$hostname did not say which household it belongs to")

            val groups = gson.fromJson(
                seedSocket.command(Frames.onHousehold("groups:1", "getGroups", householdId)),
                GroupsResponse::class.java,
            )
            registerAddresses(groups)
            _state.update {
                it.copy(
                    connected = true, householdId = householdId, groups = fillPlaybackState(groups.groups),
                    players = groups.players, error = null, householdChoices = emptyList(), reconnecting = false,
                )
            }

            // Topology changes arrive here: a group forming or breaking rewrites the list.
            // The socket is already being watched — socketForHostname does that on open,
            // before any subscribe, so no snapshot can be missed.
            seedSocket.subscribe(Frames.onHousehold("groups:1", "subscribe", householdId))

            // Worth remembering only once the whole session stood up, not merely because a
            // socket opened.
            withContext(Dispatchers.IO) { seeds.save(entry.copy(householdId = householdId)) }

            // Each on its own. Sonos goes on listing a speaker for minutes after it is unplugged,
            // and one group whose coordinator cannot be reached used to throw out of here and
            // sink the whole session — on the Shield, Kitchen pulled after a party broke up left
            // every room behind "websocket to … failed", and every retry failed the same way
            // until Sonos dropped it. A group left unsubscribed here is caught up by the next
            // groups:1 event, which is how groups formed later are subscribed anyway.
            //
            // Under the catch-up's lock, and skipping what is already done: the groups:1
            // subscription above starts a catch-up of its own with its first event, and the two
            // used to subscribe every group twice — each namespace, every group, on every connect.
            resubscribeLock.withLock {
                groups.groups.forEach { group ->
                    if (subscribedGroups[group.id] != group.coordinatorId) runCatching { subscribeGroup(group) }
                }
            }
            if (unsubscribedGroupsRemain()) catchUpLater()

            // The UPnP switch: read once, then followed. A `settingsChanged` event names the
            // version each settings group is at, so a flip in the Sonos app is heard without
            // asking again (x2rock verified both directions on the office One SL, 2026-09-28).
            // Best-effort: a firmware without the namespace leaves the switch unknown, read as on.
            // Bonded speakers that have fallen off the network, which nothing else reports.
            // Household-scoped; the subscription's first event is the current state.
            runCatching { seedSocket.subscribe(Frames.onHousehold("zones:1", "subscribe", householdId)) }

            seedPlayerId = entry.id
            runCatching { readUpnpSwitch() }
            runCatching { seedSocket.subscribe(Frames.onPlayer("effectiveSettings:1", "subscribe", entry.id)) }
            // Per-speaker volume only matters once rooms are grouped, but subscribing up
            // front means the rows are already populated when a group forms.
            groups.players.forEach { player ->
                subscribePlayer(player)
            }
        } catch (e: Exception) {
            // With Authentication on, every Control API command is refused with
            // ERROR_NO_PERMISSION, getGroups first, while UPnP keeps answering (x2rock, verified
            // 2026-09-26). The player is right and reachable; only the switch is in the way. So
            // the memory of it is kept, and the error names the switch rather than reading as a
            // speaker that did not answer.
            val refused = e is SonosCommandException && e.isPermissionRefusal
            // A remembered player whose socket opened but whose session did not stand up is
            // still unusable — a household id that no longer exists, say, after a re-setup.
            // Without this the same dead memory is retried on every launch, forever.
            if (fromMemory && !refused) {
                withContext(Dispatchers.IO) { seeds.clear() }
            }
            _state.update {
                it.copy(
                    connected = false,
                    error = if (refused) AUTHENTICATION_REQUIRED else e.message ?: e.toString(),
                    reconnecting = false,
                )
            }
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
    private fun reconnect(
        immediate: Boolean = false,
        fresh: Boolean = false,
        /** See [findEntryPoint]: the speaker whose socket just died, not tried in this run. */
        avoid: String? = null,
    ) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            teardown()
            var backoff = if (immediate) 0L else MIN_BACKOFF_MILLIS
            var skipSeed = fresh
            var passOver = avoid
            while (isActive) {
                if (backoff > 0) delay(backoff)
                val outcome = runCatching { establish(rediscover = skipSeed, avoid = passOver) }
                if (outcome.isSuccess) return@launch
                // A question for the viewer, not a fault: asking SSDP again will not answer it.
                if (outcome.exceptionOrNull() is HouseholdChoiceNeeded) return@launch
                // Only the first attempt after a network change ignores the remembered
                // address; if discovery then fails too, the memory is worth another try.
                skipSeed = false
                // Likewise the speaker that went: once is enough to have tried the others first.
                passOver = null
                backoff = nextBackoff(backoff)
            }
        }
    }

    /**
     * A socket died on its own. Whether that costs the session depends on what it carried.
     *
     * The seed holds `groups:1`, and a coordinator's socket holds its group's playback,
     * metadata and volume subscriptions; there is no replay, so losing either means
     * rebuilding from a fresh snapshot. A socket that is neither — a member's, open only for
     * that speaker's own volume — carried nothing anyone else needs. Rebuilding the household
     * for it, as this once did, let one flaky portable blank every room in the house, again on
     * each reconnect it failed. x2rock learned the same and treats member sockets as
     * best-effort.
     *
     * So a member's socket is evicted, its speaker's level withdrawn rather than left showing
     * a value nothing will update, and nothing else happens. It comes back with the next
     * `groups:1` event, whose catch-up resubscribes every player — and a speaker dropping off
     * or rejoining is itself a topology change. A loss from a socket no longer in the pool, a
     * previous session's, is ignored.
     */
    private fun handleLoss(socket: SonosSocket, cause: Throwable) {
        if (!sockets.remove(socket.hostname, socket)) return
        if (carriesSession(socket.hostname)) {
            if (reconnectJob?.isActive == true) return
            // Said as reconnecting, not as [cause]: a speaker unplugged is ordinary, and the
            // error is for a reconnect that fails. Tried at once, past the speaker that went —
            // other speakers are very likely there, and the backoff is for when they are not.
            _state.update { it.copy(connected = false, reconnecting = true) }
            reconnect(immediate = true, avoid = socket.hostname)
            return
        }
        val players = playersOn(socket.hostname)
        _playerVolumes.update { it - players.toSet() }
        subscribedPlayers.removeAll(players.toSet())
    }

    /** Whether [hostname] is the seed or any group's coordinator, by the topology as it is now. */
    private fun carriesSession(hostname: String): Boolean {
        if (hostname.equals(seedHostname, ignoreCase = true)) return true
        val coordinators = _state.value.groups.map { it.coordinatorId } + subscribedGroups.values
        return playersOn(hostname).any { it in coordinators }
    }

    private suspend fun teardown() = lock.withLock {
        // cancel(), not close(): this runs on the premise that the peer may be gone, and a
        // graceful close waits for a handshake a dead peer will never send.
        clearSession { runCatching { it.cancel() } }
        // A different household has its own services and tokens; make the next search re-read.
        searchable = null
        _state.update { it.copy(connected = false) }
    }

    /**
     * Everything a session holds, let go, with [dispose] applied to each socket. Sockets leave
     * the pool before they are disposed of, so the failures that raises are recognised as a
     * previous session's and ignored; the subscription maps go for the same reason — nothing
     * should go on believing it holds a subscription over a socket that no longer exists.
     */
    private fun clearSession(dispose: (SonosSocket) -> Unit) {
        generation++
        catchUpJob?.cancel()
        takeJobs().forEach { it.cancel() }
        val gone = sockets.values.toList()
        sockets.clear()
        seedHostname = null
        seedPlayerId = null
        securityVersion = null
        gone.forEach(dispose)
        subscribedGroups.clear()
        subscribedPlayers.clear()
        addressBook.clear()
        upnp.switchedOff = false
        _groupStates.value = emptyMap()
        _playerVolumes.value = emptyMap()
    }

    fun disconnect() {
        wantConnection = false
        reconnectJob?.cancel()
        reconnectJob = null
        clearSession { it.close() }
        _state.value = HouseholdState()
    }

    // ---------------------------------------------------------------- reads

    fun groupState(groupId: String): GroupState = _groupStates.value[groupId] ?: GroupState()

    fun playerName(playerId: String): String =
        _state.value.players.firstOrNull { it.id == playerId }?.name ?: playerId

    // ---------------------------------------------------------------- queue
    //
    // The one part of the UI that is asked rather than pushed: the Control API has no
    // queue at all, so this goes over UPnP on port 1400. See [Upnp].

    /**
     * The whole queue, a page of [Upnp.MAX_ITEMS] at a time — a browse answers at most that
     * many, and a long queue is not cut short at it.
     */
    suspend fun queue(groupId: String): QueueResponse {
        val hostname = coordinatorHostname(groupId)
        val first = upnp.browseQueue(hostname)
        val items = first.items.toMutableList()
        while (items.size < first.totalItems) {
            val page = upnp.browseQueue(hostname, start = items.size)
            if (page.items.isEmpty()) break
            items += page.items
        }
        return QueueResponse(items = items, totalItems = first.totalItems, updateId = first.updateId)
    }


    /** [updateId] is the version the caller's list was read at, from [queue]: see [Upnp.moveInQueue]. */
    suspend fun moveInQueue(groupId: String, from: Int, to: Int, updateId: String) =
        upnp.moveInQueue(coordinatorHostname(groupId), from, to, updateId)

    suspend fun clearQueue(groupId: String) = upnp.clearQueue(coordinatorHostname(groupId))

    /** Save [groupId]'s queue as a new Sonos playlist. Answers its UPnP id. */
    suspend fun saveQueue(groupId: String, title: String): String =
        upnp.saveQueue(coordinatorHostname(groupId), title)

    suspend fun removeFromQueue(groupId: String, trackNumber: Int, updateId: String) =
        upnp.removeFromQueue(coordinatorHostname(groupId), trackNumber, updateId)

    /**
     * Play the queue from [trackNumber], whatever the room is playing now.
     *
     * The queue outlives a station, a stream or the TV input, but stops being the source,
     * and a bare `Seek` then answers 701. So the queue is made the source first, only if it
     * is not already — re-setting it while it plays would restart the transport for nothing —
     * then the track is chosen and played. Played because choosing a track is the request:
     * after a switch, or on a paused room, the transport would otherwise sit stopped on it.
     */
    suspend fun skipToQueueItem(groupId: String, trackNumber: Int) {
        val group = group(groupId)
        val hostname = coordinatorHostname(groupId)
        if (!upnp.mediaInfo(hostname).playingFromQueue) upnp.useQueue(hostname, group.coordinatorId)
        upnp.skipToQueueItem(hostname, trackNumber)
        play(groupId)
    }

    /** [groupId]'s own sleep timer, or `null` with none set. See [Upnp.sleepTimer]. */
    suspend fun sleepTimer(groupId: String): Long? = upnp.sleepTimer(coordinatorHostname(groupId))

    /** Arm [groupId]'s sleep timer for [minutes], or cancel it with `null`. */
    suspend fun setSleepTimer(groupId: String, minutes: Int?) =
        upnp.setSleepTimer(coordinatorHostname(groupId), minutes?.let { it * 60_000L })

    /**
     * Snooze [groupId]'s ringing alarm for [minutes] — the clock radio's nine, as x2rock's
     * default. To stop it outright, [pause]: that ends it, where deleting the alarm would not.
     */
    suspend fun snoozeAlarm(groupId: String, minutes: Int = SNOOZE_MINUTES) =
        upnp.snoozeAlarm(coordinatorHostname(groupId), minutes * 60_000L)

    /**
     * Ask whether an alarm is why [groupId] just started playing. Off the event path, and kept
     * only if the room is still playing when the answer comes. A refusal — UPnP off, say — is
     * no alarm: the room simply offers none.
     */
    private fun askForAlarm(groupId: String) {
        scope.launch {
            val id = runCatching { upnp.runningAlarm(coordinatorHostname(groupId)) }.getOrNull() ?: return@launch
            update(groupId) { if (it.playbackState.isPlaying()) it.copy(ringingAlarm = id) else it }
        }
    }

    /** Whether [groupId] is playing from its queue, rather than a station, stream or TV. */
    suspend fun playingFromQueue(groupId: String): Boolean =
        upnp.mediaInfo(coordinatorHostname(groupId)).playingFromQueue

    /** For the live suite, which must put a room's source back as it found it. */
    internal suspend fun mediaInfo(groupId: String): MediaInfo = upnp.mediaInfo(coordinatorHostname(groupId))

    internal suspend fun destroyPlaylist(groupId: String, upnpId: String) =
        upnp.destroyObject(coordinatorHostname(groupId), upnpId)

    internal suspend fun restoreSource(groupId: String, info: MediaInfo) =
        upnp.setTransportUri(coordinatorHostname(groupId), info.currentUri, info.currentUriMetaData)

    private fun coordinatorHostname(groupId: String): String {
        val group = group(groupId)
        return hostnameOf(group.coordinatorId)
    }

    /**
     * Put this room on its soundbar's HDMI input, overriding whatever music it is playing.
     *
     * When the soundbar is a *member* rather than the coordinator, taking the TV hands
     * coordination over, and the player we asked stops coordinating before it can answer —
     * so a lost reply there is the normal case, not a failure. An error the player actually
     * answered with is real, from either; only silence is not.
     *
     * Nothing polls to find out. The switch arrives as a `playbackMetadata:1` event
     * carrying `htInputFormat`, which is what [GroupState.onTvInput] reads. With no reply,
     * this waits for that event, and fails if it never comes, rather than say nothing.
     */
    suspend fun useTvInput(groupId: String, preferSoundbar: String? = null) {
        val group = group(groupId)
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
            // An answer that is an error is final, from whichever player gave it. What is left
            // is silence: a reply lost in the handoff, or a soundbar too busy to answer.
            // Caught narrowly: cancellation must still propagate.
            if (e is UpnpRefusedException) throw e
            val switched = withTimeoutOrNull(settleMillis) {
                combine(_state, _groupStates) { state, groups ->
                    state.groups.any { soundbar in it.playerIds && groups[it.id]?.onTvInput == true }
                }.first { it }
            }
            if (switched == null) {
                throw IOException("${group.name} did not answer, and has not switched to the TV", e)
            }
        }
    }

    /** The household's Sonos playlists — its saved queues. `playlists:1`, household-scoped. */
    suspend fun playlists(): PlaylistsResponse {
        val body = householdCommand("playlists:1", "getPlaylists")
        return gson.fromJson(body, PlaylistsResponse::class.java) ?: PlaylistsResponse()
    }

    /**
     * Play [playlistId] in [groupId], in place of the queue.
     *
     * `REPLACE` is said out loud because it is not the default: `loadPlaylist` *appends*
     * unless told otherwise, with playback jumping to the end, so playing one playlist twice
     * on a four-track queue left twelve (x2rock, verified 2026-09-04). `loadFavorite` replaces
     * without being asked; the two sibling commands do not agree.
     */
    suspend fun loadPlaylist(groupId: String, playlistId: String) = loadPlaylist(groupId, playlistId, "REPLACE", play = true)

    /**
     * Add [playlistId] to the end of [groupId]'s queue, leaving what plays alone — the
     * default the player has for `loadPlaylist`, said explicitly, and without playing it.
     */
    suspend fun appendPlaylist(groupId: String, playlistId: String) = loadPlaylist(groupId, playlistId, "APPEND", play = false)

    private suspend fun loadPlaylist(groupId: String, playlistId: String, action: String, play: Boolean) {
        coordinator(groupId).command(
            Frames.onGroup("playlists:1", "loadPlaylist", groupId),
            JsonObject().apply {
                addProperty("playlistId", playlistId)
                addProperty("playOnCompletion", play)
                addProperty("action", action)
            },
        )
    }

    /**
     * What the household played lately, newest first, with any player-relative art made
     * reachable. Household-scoped. With the Sonos app's Personalization off it answers
     * `ERROR_DISALLOWED_BY_POLICY` ("History is disabled"), and nothing is recorded back.
     */
    suspend fun history(): List<HistoryItem> {
        val body = householdCommand("history:1", "getHistory")
        // Its art is served by a player, by path; any coordinator will answer for it.
        val anyCoordinator = _state.value.groups.firstOrNull()?.coordinatorId
        return (gson.fromJson(body, HistoryResponse::class.java) ?: HistoryResponse()).resources.map { item ->
            item.copy(images = item.images.map { it.copy(url = reachableArt(it.url, anyCoordinator)) })
        }
    }

    /**
     * The household's Apple Music account, from what it has played: see [AppleMusic.accountIn].
     * Recently Played may be switched off (Personalization), so its refusal is not an answer.
     */
    suspend fun appleMusicAccount(): String? =
        AppleMusic.accountIn(runCatching { history() }.getOrDefault(emptyList()), _groupStates.value.values.map { it.track })

    /**
     * Play an Apple Music search result in [groupId], in place of the queue, through the
     * household's own account — the way Recently Played is played again, which it shares.
     */
    suspend fun playAppleMusic(groupId: String, item: AppleMusicItem, accountId: String) =
        replay(groupId, item.asLoadable(accountId))

    /** Add an Apple Music search result to the end of [groupId]'s queue, leaving what plays alone. */
    suspend fun queueAppleMusic(groupId: String, item: AppleMusicItem, accountId: String) =
        upnp.addToQueue(coordinatorHostname(groupId), AppleMusic.queueUri(item, accountId), AppleMusic.queueMetadata(item))

    // ---- Searching and browsing the household's own music services ----
    //
    // Not Apple Music, which has no usable SMAPI credential and is searched through iTunes
    // instead (see [AppleMusic]). Everything else the household has added — Qobuz, TIDAL,
    // Deezer, Amazon, Saavn — is searched and browsed over SMAPI with the household's **own**
    // stored token, read off a player once per connection. Playing rides `loadContent` the
    // same way Recently Played and Apple Music do; nothing here needs a browser login, which a
    // TV remote could not complete anyway.

    private val serviceLock = Mutex()
    @Volatile private var searchable: List<LinkedService>? = null

    /**
     * The services this household can search or browse right now, each carrying the credential
     * it uses — an anonymous service, or one the household stores a token for. Captured once per
     * connection and cached; cleared on [teardown] so a different household re-reads.
     *
     * The token read is **best-effort**: it asks a player to open a connection back to this
     * device (the one time Sonos does), and if that never arrives only the anonymous services
     * come back — the capability degrades, it does not fail. Apple Music is deliberately absent;
     * its catalogue is reached through [searchAppleMusic]-shaped iTunes search, not SMAPI.
     */
    suspend fun searchableServices(): List<LinkedService> = serviceLock.withLock {
        searchable?.let { return@withLock it }
        val household = _state.value.householdId ?: error("not connected")
        val coordinator = _state.value.groups.firstOrNull()?.coordinatorId ?: error("no players yet")
        val hostname = hostnameOf(coordinator)
        val answer = upnp.listAvailableServices(hostname)
        val services = parseServices(answer.descriptors, answer.types)
        val accounts = runCatching {
            val ip = addressBook.lookup(hostname).firstOrNull()?.hostAddress ?: return@runCatching emptyList()
            AccountCapture.captureEnvelope(ip)
                // The decrypt key is the short household id (`Sonos_xxx`), not the long form.
                ?.let { StoredAccounts.decryptAccounts(it, household.substringBefore('.')) }
                ?: emptyList()
        }.getOrDefault(emptyList())
        linkedServices(services, accounts, household).also { searchable = it }
    }

    /** The categories [linked] will accept in [searchService]; empty when it can only be browsed. */
    suspend fun serviceCategories(linked: LinkedService): List<Category> = smapi.categories(linked.service)

    /** Search [linked] for [term] under a [Category.mappedId]. */
    suspend fun searchService(linked: LinkedService, category: String, term: String, index: Int = 0, count: Int = 30): ItemPage =
        smapi.search(linked.service, linked.token, category, term, index, count)

    /** Browse into a container of [linked] — `root` for its top level. */
    suspend fun browseService(linked: LinkedService, id: String = "root", index: Int = 0, count: Int = 100): ItemPage =
        smapi.metadata(linked.service, linked.token, id, index, count)

    /**
     * Play a service [item] in [groupId], in place of the queue, through the household's own
     * account — the same `loadContent` path as Recently Played, which carries the item's own
     * `itemType` as the load type (`track`, `album`, `playlist`). Only for a playable item, not
     * a container to descend into.
     */
    suspend fun playServiceItem(groupId: String, linked: LinkedService, item: Item) =
        replay(groupId, HistoryItem(item.title, item.itemType, MusicObjectId(item.id, linked.service.id, linked.accountId)))

    /**
     * Play [item] in [groupId] again, in place of what it is playing.
     *
     * `playback:1 loadContent` **loads and does not start**: `playOnCompletion` is accepted
     * and ignored, and a `play` sent at once is refused with `ERROR_PLAYBACK_NO_CONTENT`
     * while the load is under way, or accepted mid-load and comes to nothing (x2rock,
     * verified 2026-09-26). So, as x2rock does: pause first, so that *playing* can only mean
     * the new content; load; then press play until the room is pushed as playing. The
     * pushed state is the answer — only the press repeats, and only until it lands.
     *
     * Fails as the player does, with its words: an anonymous service's item answers
     * `ERROR_ACCOUNT_INVALID_ID`, and so does a deleted playlist. Or, after
     * [LOAD_SETTLE_MILLIS], that it loaded and did not start.
     */
    suspend fun replay(groupId: String, item: HistoryItem) {
        runCatching { pause(groupId) }
        // Wait out the pause, or a room that was playing still reads as playing below.
        withTimeoutOrNull(2_000) {
            _groupStates.first { it[groupId]?.playbackState != PlaybackStates.PLAYING }
        }
        // No reply is not no, here as for a regroup: a coordinator busy with a load can answer
        // late or not at all and do what it was asked. Only a refusal is final; a timeout on
        // the load or on a press leaves the wait below to judge, by whether the room plays —
        // and, for a load nobody answered, by whether what plays is something *else*: the
        // presses below would otherwise resume what was paused and pass it off as the item.
        val before = _groupStates.value[groupId]?.let { it.track?.id to it.container?.name }
        var loadLost = false
        try {
            coordinator(groupId).command(
                Frames.onGroup("playback:1", "loadContent", groupId),
                JsonObject().apply {
                    add("id", JsonObject().apply {
                        addProperty("_objectType", "universalMusicObjectId")
                        item.id.serviceId?.let { addProperty("serviceId", it) }
                        item.id.accountId?.let { addProperty("accountId", it) }
                        addProperty("objectId", item.id.objectId)
                    })
                    addProperty("type", item.type)
                },
            )
        } catch (e: ReplyTimeoutException) {
            loadLost = true
        }
        val started = withTimeoutOrNull(minOf(settleMillis, LOAD_SETTLE_MILLIS)) {
            // Not straight away. A play ~30ms after the load hung the home Dining Room — a
            // Symfonisk pair, 86.10 — outright, the HTTP server on 1400 included, until it
            // restarted itself two minutes later: three times from the app, and once from this
            // code alone against the real speaker (2026-10-06). The same commands a second apart
            // played every time, and x2rock found a play two seconds after the load starts
            // everything it tried. A fixed wait rather than one for the new content's event:
            // measured, that event came ~1.1s after the load was answered, so waiting for it
            // would save a few tenths and need a floor and a fallback besides.
            delay(minOf(PLAY_AFTER_LOAD_MILLIS, settleMillis / 4))
            while (true) {
                try {
                    play(groupId)
                } catch (e: SonosCommandException) {
                    if (!e.detail.startsWith("ERROR_PLAYBACK_NO_CONTENT")) throw e
                } catch (e: ReplyTimeoutException) {
                    // Judged below.
                }
                val playing = withTimeoutOrNull(1_000) {
                    _groupStates.first { all ->
                        all[groupId]?.let { g ->
                            g.playbackState == PlaybackStates.PLAYING &&
                                (!loadLost || (g.track?.id to g.container?.name) != before)
                        } == true
                    }
                }
                if (playing != null) break
            }
            true
        }
        if (started == null) throw IOException("${item.name} loaded but did not start playing")
    }

    suspend fun favorites(): FavoritesResponse {
        val body = householdCommand("favorites:1", "getFavorites")
        return gson.fromJson(body, FavoritesResponse::class.java) ?: FavoritesResponse()
    }

    // ---------------------------------------------------------------- ratings
    //
    // A genuinely different code path from everything else here: it starts from the group's
    // current track id and coordinator, then leaves the LAN — and Sonos — entirely, for the
    // service's presentation map on Sonos's CDN and `getExtendedMetadata`/`rateItem` on the
    // service's own endpoint. Kept out of the socket machinery so a slow internet call can
    // never block, or be mistaken for, the household. The mechanism is x2rock's `rate`; its
    // `docs/openphonos-ratings-findings.md` holds the research it was built from.

    /** What a [rate] call did. */
    data class RateOutcome(
        val up: Boolean,
        val serviceName: String,
        /** Whether the service's "skip after rating" was acted on — a failed skip must not read
         * as the rating itself having failed, since it already landed. */
        val skipped: Boolean,
    )

    /** Where the current track stands with its service, when it can be rated at all. */
    data class RatingState(val serviceName: String, val current: Thumb)

    /** Everything a rate press needs, resolved: who to ask, what to name, which ids it offers. */
    private class Rateable(val service: Service, val objectId: String, val matches: List<RatingsMatch>, val current: RatingsMatch)

    /**
     * Throws with a message meant to be shown as-is — "nothing rateable is playing", "Plex
     * needs an account linked" — because each is a fact about the content or the service,
     * not a bug. [ratingState] and [rate] share it, so a button is drawn exactly when a press
     * would get as far as `rateItem`.
     */
    private suspend fun rateable(groupId: String): Rateable {
        val trackId = groupState(groupId).track?.id?.takeIf { it.isReal }
            ?: error("nothing rateable is playing in this room")
        val serviceId = trackId.serviceId ?: error("the current track names no service")
        val hostname = coordinatorHostname(groupId)

        val service = parseServices(upnp.listAvailableServices(hostname).descriptors)
            .firstOrNull { it.id == serviceId }
            ?: error("service $serviceId is not in this player's service list")
        // Before anything leaves the LAN: there is no account linking on a TV, so a service
        // that needs one cannot be rated from here whatever its presentation map says.
        if (service.auth != Auth.ANONYMOUS) error("${service.name} needs an account linked, which this app does not do")

        val matches = ratingsCatalogue.ratingsFor(service)
        if (matches.isEmpty()) error("${service.name} publishes no ratings, so nothing here can be rated")

        val properties = smapi.extendedMetadata(service, token = null, id = trackId.objectId)
        val current = RatingsMatch.current(matches, properties)
            ?: error("${service.name} did not report a rating state for the current track")
        return Rateable(service, trackId.objectId, matches, current)
    }

    /**
     * Whether, and how, the track playing in [groupId] is rated — `null` whenever [rate]
     * would refuse. Not pushed: nothing tells a controller that a rating changed, so this is
     * asked once per track and again after a press, never on a timer.
     *
     * Costs a LAN call, and for a track with an id on an anonymous service that publishes
     * ratings, one call to that service. Everything else stops before the internet.
     */
    suspend fun ratingState(groupId: String): RatingState? {
        if (!groupState(groupId).hasTrackId) return null
        val rateable = try {
            rateable(groupId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        return RatingState(rateable.service.name, rateable.current.selected)
    }

    /** Rate the track currently playing in [groupId]. Fails as [rateable] does. */
    suspend fun rate(groupId: String, up: Boolean): RateOutcome {
        val rateable = rateable(groupId)
        val chosen = RatingsMatch.find(rateable.matches, rateable.current.propname, rateable.current.value, up)
            ?: error("${rateable.service.name} offers no ${if (up) "up" else "down"} rating here")

        val result = smapi.rateItem(rateable.service, token = null, id = rateable.objectId, rating = chosen.id)
        // The rating already landed; a skip failure here is a separate fact, so it is caught
        // rather than allowed to read as the rating itself having failed.
        val skipped = result.shouldSkip == true &&
            runCatching { skipToNextTrack(groupId) }.isSuccess

        return RateOutcome(
            up = up,
            serviceName = rateable.service.name,
            skipped = skipped,
        )
    }

    /** Companion to [metadataStatusBody], for capturing what a source says it permits. */
    internal suspend fun playbackStatusBody(groupId: String): JsonElement =
        coordinator(groupId).command(
            Frames.onGroup("playback:1", "getPlaybackStatus", groupId),
        )

    /**
     * The group's metadata as the coordinator has it, for recording a fixture verbatim.
     *
     * Everything the app uses arrives by subscription instead; this exists so the live suite
     * can capture a shape rather than have someone write down what they assume it to be.
     */
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
        upnp.setEq(hostnameOf(playerId), eqType, on)
    }

    // ---------------------------------------------------------------- tone
    //
    // Per speaker, on that speaker: read from settings:1 (see [playerSettings]), written over
    // RenderingControl because settings:1 refuses writes. Nothing pushes a change, so the room
    // panel re-reads after each write, as it does for Night Sound.

    suspend fun setBass(playerId: String, level: Int) = upnp.setTone(hostnameOf(playerId), Upnp.Tone.BASS, level)
    suspend fun setTreble(playerId: String, level: Int) = upnp.setTone(hostnameOf(playerId), Upnp.Tone.TREBLE, level)
    suspend fun setLoudness(playerId: String, on: Boolean) = upnp.setLoudness(hostnameOf(playerId), on)
    suspend fun trueplay(playerId: String): TruePlay = upnp.trueplay(hostnameOf(playerId))
    suspend fun setTrueplay(playerId: String, on: Boolean) = upnp.setTrueplay(hostnameOf(playerId), on)

    private fun hostnameOf(playerId: String): String =
        PlayerNames.localHostname(playerId) ?: error("cannot derive a hostname for $playerId")

    private fun group(groupId: String): Group =
        _state.value.groups.firstOrNull { it.id == groupId } ?: error("no group $groupId")

    /** The players whose certificate name is [hostname]: one socket per speaker, so usually one. */
    private fun playersOn(hostname: String): List<String> =
        _state.value.players.map { it.id }.filter { PlayerNames.localHostname(it).equals(hostname, ignoreCase = true) }

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

    /**
     * Move the group's level by [delta]. For buttons and keys, which say "louder" rather than
     * a level — Sonos's own rule: `setVolume` for stateful controls like a slider,
     * `setRelativeVolume` for stateless ones. It needs no baseline, so a press before the
     * first snapshot moves the speaker by the step rather than to it, and it unmutes as a side
     * effect, as both setters do. Clamping to 0..100 is the player's.
     */
    suspend fun adjustGroupVolume(groupId: String, delta: Int) {
        coordinator(groupId).command(
            Frames.onGroup("groupVolume:1", "setRelativeVolume", groupId),
            JsonObject().apply { addProperty("volumeDelta", delta.coerceIn(-100, 100)) },
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

    /** One speaker's level by [delta], on its own socket. See [adjustGroupVolume]. */
    suspend fun adjustPlayerVolume(playerId: String, delta: Int) {
        socketForPlayer(playerId).command(
            Frames.onPlayer("playerVolume:1", "setRelativeVolume", playerId),
            JsonObject().apply { addProperty("volumeDelta", delta.coerceIn(-100, 100)) },
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

    /**
     * Play a stream that no service stands behind — a radio directory's station — and say
     * whether it played.
     *
     * `playbackSession:1`: `createSession` on the coordinator, then `loadStreamUrl` in it. It
     * plays alongside the queue and leaves the queue alone. **The load succeeding is not the
     * stream playing**: the player accepts a URL it cannot play and sits IDLE without a word
     * (x2rock), so this waits for the room to play. A stream can buffer four seconds or more.
     *
     * Not by the station's name, though Kitchen did echo it as the container (2026-10-02): a
     * stream loaded without one is named by its *host* — `ice1.somafm.com` in an older capture —
     * and two stations can share a name. So by the transport: a new *start* of play, counted by
     * [GroupState.playStarts], because a room already playing would otherwise pass the old
     * stream off as the new one. A room that never seems to stop and start again is reported as
     * starting, never playing. Verified on Kitchen: a directory station PLAYING, and a URL that
     * cannot resolve accepted and then SILENT.
     */
    suspend fun playStream(
        groupId: String,
        url: String,
        name: String,
        startMillis: Long = STREAM_START_MILLIS,
    ): StreamStart {
        val socket = coordinator(groupId)
        val startsBefore = _groupStates.value[groupId]?.playStarts ?: 0
        val opened = socket.command(
            Frames.onGroup("playbackSession:1", "createSession", groupId),
            JsonObject().apply {
                addProperty("appId", APP_ID)
                addProperty("appContext", "radio")
            },
        )
        val sessionId = opened.asJsonObject.string("sessionId") ?: error("the player opened a session without naming it")
        socket.command(
            Frames.onSession("playbackSession:1", "loadStreamUrl", sessionId),
            JsonObject().apply {
                addProperty("streamUrl", url)
                addProperty("playOnCompletion", true)
                // Where the name the room shows comes from; without it the stream plays nameless.
                add("stationMetadata", JsonObject().apply {
                    addProperty("name", name)
                    addProperty("type", "station")
                })
            },
        )
        val playing = withTimeoutOrNull(startMillis) {
            _groupStates.first { all ->
                all[groupId]?.let { it.playStarts > startsBefore && it.playbackState.isPlaying() } == true
            }
        }
        if (playing != null) return StreamStart.PLAYING
        // Decided on the last state rather than the first: a room is briefly IDLE between
        // taking the URL and buffering it, so an early look would condemn every stream.
        // The Control API has no STOPPED, which is UPnP's word: a stream that will not play is IDLE.
        return if (_groupStates.value[groupId]?.playbackState == PlaybackStates.IDLE) StreamStart.SILENT
            else StreamStart.STARTING
    }

    /**
     * Move players into or out of [groupId]'s group.
     *
     * A refusal is final. **No answer is not**: grouping onto a soundbar on its TV input
     * stalled it past the reply timeout, and the change landed 14–20s later (x2rock, "The
     * Beam stall"). So on a timeout this waits for the topology to show the change — it
     * arrives by push like any regroup — and failing that asks once more, from the seed.
     * Only if neither shows it does the call fail.
     *
     * The group is found **by coordinator**, not by id: a regroup mints a new group id, so
     * the id this was called with names nothing once the change has happened.
     */
    suspend fun modifyGroupMembers(groupId: String, add: List<String>, remove: List<String>) {
        val group = group(groupId)
        try {
            coordinator(groupId).command(
                Frames.onGroup("groups:1", "modifyGroupMembers", groupId),
                JsonObject().apply {
                    add("playerIdsToAdd", gson.toJsonTree(add))
                    add("playerIdsToRemove", gson.toJsonTree(remove))
                },
            )
        } catch (e: ReplyTimeoutException) {
            fun landed(groups: List<Group>): Boolean {
                val now = groups.firstOrNull { it.coordinatorId == group.coordinatorId } ?: return false
                return add.all { it in now.playerIds } && remove.none { it in now.playerIds }
            }
            if (withTimeoutOrNull(settleMillis) { _state.first { landed(it.groups) } } != null) return
            val fresh = runCatching { freshGroups() }.getOrNull()
            if (fresh != null && landed(fresh)) return
            throw IOException("${group.name} did not answer, and the change has not appeared", e)
        }
    }

    /**
     * The household's UPnP switch, as the seed applies it: `effectiveSettings:1
     * getSettingsGroup {"groupName": "security"}`, player-scoped — the household-scoped form is
     * refused. A failure keeps what was known.
     */
    private suspend fun readUpnpSwitch() {
        val playerId = seedPlayerId ?: return
        val body = socketForPlayer(playerId).command(
            Frames.onPlayer("effectiveSettings:1", "getSettingsGroup", playerId),
            JsonObject().apply { addProperty("groupName", "security") },
        ).asJsonObject
        val allowed = body.getAsJsonObject("attributes")?.get("allowInsecureUPnP")
            ?.takeIf { it.isJsonPrimitive }?.asBoolean ?: return
        securityVersion = body.string("timestamp")
        _state.update { it.copy(upnpOff = !allowed) }
        upnp.switchedOff = !allowed
    }

    /** The topology as the seed reports it now, for a check the pushed copy could not settle. */
    private suspend fun freshGroups(): List<Group> {
        val household = _state.value.householdId ?: error("not connected")
        val seed = seedHostname ?: error("not connected")
        val body = socketForHostname(seed).command(Frames.onHousehold("groups:1", "getGroups", household))
        return gson.fromJson(body, GroupsResponse::class.java)?.groups.orEmpty()
    }

    /** A household-scoped command. Any player's socket answers for the household, so the first will do. */
    private suspend fun householdCommand(namespace: String, command: String): JsonElement {
        val household = _state.value.householdId ?: error("not connected")
        val socket = sockets.values.firstOrNull() ?: error("not connected")
        return socket.command(Frames.onHousehold(namespace, command, household))
    }

    private suspend fun onGroup(groupId: String, command: String) {
        coordinator(groupId).command(Frames.onGroup("playback:1", command, groupId))
    }

    // ---------------------------------------------------------------- sockets

    /** The coordinator's socket, opened on first use and reused after. */
    private suspend fun coordinator(groupId: String): SonosSocket {
        val group = group(groupId)
        return socketForPlayer(group.coordinatorId)
    }

    private suspend fun socketForPlayer(playerId: String): SonosSocket {
        return socketForHostname(hostnameOf(playerId))
    }

    /**
     * The pooled socket for [hostname], opened if there is none.
     *
     * The handshake runs **outside** the lock. Held across it, one unreachable speaker
     * stalled every other socket for its whole connect timeout — at connect, each member's
     * volume subscription queued behind the last, and a coordinator's command could not even
     * reach its own, already open, socket. Two callers racing to open the same name both
     * finish; the second to arrive keeps the first's socket and closes its own, which is
     * x2rock's pool rule too.
     */
    private suspend fun socketForHostname(hostname: String): SonosSocket {
        val opened = lock.withLock {
            sockets[hostname]?.let { return it }
            generation
        }
        val socket = SonosSocket.open(client, hostname, port)
        return lock.withLock {
            // A teardown while the handshake was in flight means this socket belongs to a
            // dead session; adopting it would resurrect it into a just-cleared map.
            if (opened != generation) {
                socket.cancel()
                throw java.io.IOException("connection torn down while opening $hostname")
            }
            sockets[hostname]?.let { winner ->
                socket.close()
                return@withLock winner
            }
            sockets[hostname] = socket
            watch(socket)
            socket
        }
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
            socket.events.collect { event -> runCatching { apply(event, from = socket.hostname) } }
        }
        socketJobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            socket.failures.collect { cause -> handleLoss(socket, cause) }
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
        current.players.forEach { subscribePlayer(it) }
        if (unsubscribedGroupsRemain()) catchUpLater()
    }

    @Volatile private var catchUpJob: Job? = null

    private fun unsubscribedGroupsRemain(): Boolean =
        _state.value.groups.any { subscribedGroups[it.id] != it.coordinatorId }

    /**
     * Keep trying the groups that could not be subscribed, with the reconnect's backoff.
     *
     * Needed because a topology event is not guaranteed. Kitchen, unplugged and plugged back
     * in on the Shield, was listed as its own group the whole time, so its return changed no
     * topology, no groups:1 event came, and it sat in the room list frozen — the very failure
     * the catch-up exists to prevent, reached a way it did not cover. This is reconnecting to
     * a speaker, not asking for state: it stops the moment every listed group is subscribed,
     * or the session it belongs to is torn down.
     */
    private fun catchUpLater() {
        if (catchUpJob?.isActive == true) return
        val session = generation
        catchUpJob = scope.launch {
            var wait = MIN_BACKOFF_MILLIS
            while (isActive && session == generation && unsubscribedGroupsRemain()) {
                delay(wait)
                if (session != generation) return@launch
                runCatching { resubscribe() }
                wait = nextBackoff(wait)
            }
        }
    }

    /** A soundbar's HDMI port, on its own socket: player-scoped. Best-effort, like its volume. */
    /**
     * A player's own subscriptions, once: its level and, for a soundbar, its HDMI port. Done
     * once rather than on every topology change, as this used to: a regroup already costs
     * three round trips per changed group and was costing one per player in the house on top,
     * each serialised under the catch-up lock. A player whose socket was lost is forgotten in
     * [handleLoss], so it is subscribed afresh at the next catch-up.
     */
    private suspend fun subscribePlayer(player: Player) {
        if (!subscribedPlayers.add(player.id)) return
        val subscribed = runCatching {
            socketForPlayer(player.id).subscribe(Frames.onPlayer("playerVolume:1", "subscribe", player.id))
        }.isSuccess && subscribeHdmi(player)
        // Either failing leaves the player to the next catch-up: a soundbar whose port was never
        // heard from could not tell an empty socket from a television, for the whole session.
        if (!subscribed) subscribedPlayers.remove(player.id)
    }

    /** True once a soundbar's port is subscribed — or at once for a player that has none. */
    private suspend fun subscribeHdmi(player: Player): Boolean {
        if (HT_PLAYBACK !in player.capabilities.orEmpty()) return true
        return runCatching { socketForPlayer(player.id).subscribe(Frames.onPlayer("hdmi:1", "subscribe", player.id)) }.isSuccess
    }

    private suspend fun subscribeGroup(group: Group) {
        val socket = socketForPlayer(group.coordinatorId)
        // Subscribing returns the current state as the first event, so there is no
        // separate "get" needed to seed the flow. All four at once, replies matched by cmdId:
        // one round-trip per group rather than four, and a namespace a firmware ignores costs
        // one reply timeout rather than one per room.
        coroutineScope {
            // Allowed to fail on its own. It is not in Sonos's reference, so a firmware without it
            // is plausible, and a room with no sleep timer shown is far better than a room frozen
            // because its fourth subscription was refused.
            val sleepTimer = async { runCatching { socket.subscribe(Frames.onGroup("sleepTimer:1", "subscribe", group.id)) } }
            listOf("playback:1", "playbackMetadata:1", "groupVolume:1")
                .map { namespace -> async { socket.subscribe(Frames.onGroup(namespace, "subscribe", group.id)) } }
                .awaitAll()
            sleepTimer.await()
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

    /** [from] is the hostname of the socket [event] came in on: see `hdmi:1`. */
    private fun apply(event: SonosEvent, from: String? = null) {
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
                // The same namespace sends two shapes. An error has none of a status's fields,
                // so reading it as one would apply nothing and drop the only notice that the
                // music stopped. The body's `_objectType` says which, as it does in every capture
                // (the header's type agrees), and it is what x2rock reads too.
                if (body.string("_objectType") == "playbackError") {
                    val error = gson.fromJson(body, PlaybackError::class.java) ?: return
                    update(groupId) { it.copy(lastError = error) }
                    return
                }
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
                val wasPlaying = _groupStates.value[groupId]?.playbackState?.isPlaying() == true
                update(groupId) {
                    val playbackState = body.string("playbackState") ?: it.playbackState
                    it.copy(
                        playbackState = playbackState,
                        ringingAlarm = it.ringingAlarm.takeIf { playbackState.isPlaying() },
                        playStarts = if (!wasPlaying && playbackState.isPlaying()) it.playStarts + 1 else it.playStarts,
                        queueVersion = body.string("queueVersion") ?: it.queueVersion,
                        // Playing again answers the error; anything short of that leaves it
                        // standing, because a failed stream is followed by IDLE statuses.
                        lastError = it.lastError.takeUnless { playbackState.isPlaying() },
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
                if (!wasPlaying && _groupStates.value[groupId]?.playbackState?.isPlaying() == true) {
                    askForAlarm(groupId)
                }
            }

            // Whether a soundbar's HDMI port has anything in it. Only `connection` is kept.
            // A Beam's hdmi:1 event names no player — `playerId` is null in the header, unlike
            // playerVolume:1's (both captured 2026-10-02). It is about the player whose socket
            // it came in on, one socket per speaker, so that is who. Read from the header alone,
            // every real one was dropped and an empty port was never noticed; the fake had put
            // an id there that no player sends.
            "hdmi:1" -> {
                val playerId = event.header.playerId
                    ?: from?.let { playersOn(it).firstOrNull() }
                    ?: return
                val connection = event.body.asJsonObject.string("connection") ?: return
                _state.update { it.copy(hdmiConnection = it.hdmiConnection + (playerId to connection)) }
            }

            // Each zone's members and whether each is connected, captured with a Bedroom surround
            // unplugged. A zone is matched to its room by the member the topology lists as a
            // player; the rest are its bonded speakers.
            "zones:1" -> {
                val body = event.body.asJsonObject
                if (body.string("_objectType") != "activeZonesChange") return
                val players = _state.value.players.map { it.id }.toSet()
                val offline = body.getAsJsonArray("zones")?.mapNotNull { zone ->
                    val members = zone.asJsonObject.getAsJsonArray("members")?.map { it.asJsonObject }.orEmpty()
                    val room = members.firstOrNull { it.string("id") in players }?.string("id") ?: return@mapNotNull null
                    val gone = members.count { member ->
                        member.getAsJsonObject("state")?.bool("disconnected") == true
                    }
                    if (gone > 0) room to gone else null
                }?.toMap().orEmpty()
                _state.update { it.copy(offlineSpeakers = offline) }
            }

            // Which settings groups exist, each with its version, and no values. Only the seed's
            // is followed, and only a moved `security` version is worth a read.
            "effectiveSettings:1" -> {
                if (event.header.playerId == null || event.header.playerId != seedPlayerId) return
                val version = event.body.asJsonObject.getAsJsonArray("settingsGroupMetadata")
                    ?.map { it.asJsonObject }
                    ?.firstOrNull { it.string("name") == "security" }
                    ?.string("timestamp")
                    ?: return
                if (version != securityVersion) scope.launch { runCatching { readUpnpSwitch() } }
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
                    // Something else loaded answers a failure as surely as playing does: a room
                    // put back on its own queue after a dead stream, idle, otherwise kept saying
                    // "Couldn't play this" over a track it could play (seen on the Shield).
                    val sourceChanged = track?.name != it.track?.name || container?.name != it.container?.name ||
                        track?.id != it.track?.id
                    it.copy(
                        lastError = it.lastError.takeUnless { sourceChanged },
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

            "sleepTimer:1" -> {
                if (groupId == null) return
                val body = event.body.asJsonObject
                val left = if (body.get("active")?.asBoolean == true) {
                    body.string("remainingTimeDuration")?.let(::isoDurationMillis)
                } else null
                update(groupId) { it.copy(sleepTimerEndsAt = left?.let { ms -> monotonicMillis() + ms }) }
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

/**
 * What became of a stream after the player accepted it. Three answers, because there are three
 * (x2rock): it played; it was still buffering when the wait ran out, which is not a failure;
 * or the room sat idle, which is the silent failure of a URL it cannot play.
 */
enum class StreamStart { PLAYING, STARTING, SILENT }

/** How long a stream is given to start. Four seconds is ordinary for SomaFM over https (x2rock). */
internal const val STREAM_START_MILLIS = 10_000L

/** Names this app to the player when it opens a playback session. */
internal const val APP_ID = "com.rahga.x2rock"

/** What snooze means with no duration given: the clock radio's nine minutes, as x2rock has it. */
const val SNOOZE_MINUTES = 9

/** How long mDNS is given after SSDP draws nothing. The office speaker answered within a second. */
internal const val MDNS_TIMEOUT_MILLIS = 4_000L

/** How long a loaded item is pressed to play before it is called a failure. x2rock's figure. */
internal const val LOAD_SETTLE_MILLIS = 12_000L

/** How long after a `loadContent` the first `play` waits: see [SonosHousehold.replay]. */
internal const val PLAY_AFTER_LOAD_MILLIS = 1_500L

/** What a household with Authentication on is told, in place of the bare refusal. */
const val AUTHENTICATION_REQUIRED =
    "The speakers refused this app: Authentication is turned on in the Sonos app's Connection " +
        "Security, and this app cannot sign in from a TV. Turn it off in the Sonos app " +
        "(Account > Privacy and Security > Connection Security > Authentication), then retry."

/** See [SonosHousehold]'s `settleMillis`: the Beam stall's 14–20s, with the top of it. */
internal const val UNANSWERED_SETTLE_MILLIS = 20_000L

/** A remembered address gets this long to prove itself before discovery takes over. */
internal const val PROBE_TIMEOUT_MILLIS = 3_000L

internal fun nextBackoff(current: Long): Long =
    (if (current <= 0) MIN_BACKOFF_MILLIS else current * 2).coerceAtMost(MAX_BACKOFF_MILLIS)

/** The JVM's monotonic clock in milliseconds: what [GroupState.sleepTimerEndsAt] is measured on. */
fun monotonicMillis(): Long = System.nanoTime() / 1_000_000

/**
 * An ISO 8601 duration as milliseconds — `sleepTimer:1` sends `PT0H15M0S` — or null for
 * anything else. By hand rather than `java.time.Duration`, which this app's minSdk predates.
 */
internal fun isoDurationMillis(text: String): Long? {
    val match = ISO_DURATION.matchEntire(text.trim()) ?: return null
    val (days, hours, minutes, seconds) = match.destructured
    if (days.isEmpty() && hours.isEmpty() && minutes.isEmpty() && seconds.isEmpty()) return null
    return (days.toLongOrNull() ?: 0) * 86_400_000 +
        (hours.toLongOrNull() ?: 0) * 3_600_000 +
        (minutes.toLongOrNull() ?: 0) * 60_000 +
        ((seconds.toDoubleOrNull() ?: 0.0) * 1000).toLong()
}

private val ISO_DURATION = Regex("""P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?""")
