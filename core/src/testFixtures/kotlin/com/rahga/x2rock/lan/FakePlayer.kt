package com.rahga.x2rock.lan

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.CountDownLatch
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A Sonos household, near enough to test against.
 *
 * This exists for one reason that carries on its own: **CI has no Sonos on its LAN.**
 * Without it there is no automated regression check at all, and verification happens only
 * when someone remembers — which is how coverage rots. Convenience or speed would not have
 * justified it; that gap does.
 *
 * What it is *not* is a substitute for real hardware. It exercises this code — parsing, the
 * state machine, error handling — against a *modelled* protocol, and so catches regressions
 * we introduce. It cannot discover protocol truth: everything this project learned the hard
 * way came from speakers, and a fake only knows what it was told. Run the live suite
 * (`-Dx2rock.live=<ip>`) for "does the protocol really behave this way"; run this for "did
 * I break my own parser".
 *
 * Three things make it faithful where faithfulness carries weight:
 *
 * - **Its payloads are captured, never invented.** Everything served comes from
 *   `src/testFixtures/resources/fixtures/`, recorded verbatim off a real household and redacted for
 *   identifiers only. The invented versions these replaced had no `_objectType` anywhere,
 *   no `queueVersion`, no `availablePlaybackActions`, and no stereo pair. A fake built from
 *   what one assumes the protocol looks like tests those assumptions against themselves and
 *   passes for the wrong reasons. **Add fixtures by capturing, never by writing them out.**
 * - It serves a certificate carrying the players' real `sonos-<MAC>.local` names, so the
 *   address book and OkHttp's default hostname verifier are exercised rather than switched
 *   off. A test that passed by disabling verification would prove nothing about the design
 *   it exists to protect.
 * - It refuses the way a player does: 403 on a handshake carrying an `Origin` header, 400
 *   without the API key, and `ERROR_INVALID_OBJECT_ID` for a player-scoped command naming
 *   an id this household does not have. All three cost real effort to establish against
 *   hardware, and a fake that said yes to everything made any test of a refusal vacuous.
 */
class FakePlayer(
    /** The fixture's first coordinator, so the topology it serves is self-consistent. */
    val id: String = fixtureCoordinatorId(),
    val householdId: String = "Sonos_ExampleHousehold.ExampleToken",
) {

    val hostname: String = PlayerNames.localHostname(id)!!

    /** Every command header the client sent, so tests can assert on scope and ordering. */
    val received = LinkedBlockingQueue<JsonObject>()

    /**
     * Command bodies, kept alongside the headers.
     *
     * The header says what was asked and of whom; the body carries the value — the volume
     * actually set, the play modes actually sent — which is usually the thing under test.
     */
    private val bodies = mutableListOf<Pair<String, JsonObject>>()

    /**
     * Every command name in order, never consumed.
     *
     * [received] is a queue and [awaitCommand] *polls* it, so anything waited for is gone
     * afterwards — counting it after a wait reports zero. This is the log to count.
     */
    private val commandLog = mutableListOf<Pair<String?, String>>()

    @Synchronized
    fun commandsNamed(command: String, namespace: String? = null): Int =
        commandLog.count { (ns, c) -> c == command && (namespace == null || ns == namespace) }

    @Synchronized
    fun clearHistory() {
        received.clear()
        bodies.clear()
        commandLog.clear()
    }

    /** The body of the most recent [command], or null if it was never sent. */
    @Synchronized
    fun lastCommandBody(command: String): JsonObject? =
        bodies.lastOrNull { it.first == command }?.second

    /** Handshakes the player refused, with the code — empty is the expected state. */
    val rejected = LinkedBlockingQueue<Int>()

    /**
     * One certificate covering every *player* in the captured topology.
     *
     * The household opens a socket per coordinator for group state and per player for
     * `playerVolume:1`, so covering only coordinators would work by luck of this fixture
     * and break silently on one captured with rooms grouped.
     */
    private val certificate = HeldCertificate.Builder()
        .apply { playerHostnames().forEach { addSubjectAlternativeName(it) } }
        .commonName("fake-player")
        .build()

    private val serverCertificates = HandshakeCertificates.Builder()
        .heldCertificate(certificate)
        .build()

    private val server = MockWebServer().apply {
        useHttps(serverCertificates.sslSocketFactory(), false)
    }

    val port: Int get() = server.port

    /** The upgrade request, so a test can assert on what the client did and did not send. */
    @Volatile var lastUpgrade: RecordedRequest? = null
        private set

    /**
     * Every open connection, by the hostname the client asked for. One server answers for every
     * player in the topology, so the `Host` of the upgrade is the only thing that says which
     * player a connection is to — and so which one [dropConnection] can end on its own.
     */
    private val sockets = java.util.concurrent.ConcurrentHashMap<String, WebSocket>()
    /** The most recently opened, which is what a bare [dropConnection] has always ended. */
    @Volatile private var socket: WebSocket? = null
    @Volatile private var groups: JsonObject = reachableTopology()

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.headers["Origin"] != null) {
                    rejected += 403
                    return MockResponse().setResponseCode(403)
                }
                if (request.headers["X-Sonos-Api-Key"] == null) {
                    rejected += 400
                    return MockResponse().setResponseCode(400)
                }
                lastUpgrade = request
                val host = request.headers["Host"].orEmpty().substringBefore(':').lowercase()
                // MockWebServer serves each connection on its own thread, so this delays only
                // the handshake to this one player.
                stalledHandshakes[host]?.let { Thread.sleep(it) }
                if (host in unreachable) return MockResponse().setResponseCode(503)
                return upgrade(host)
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    /**
     * MockWebServer's shutdown waits for its queue to drain and throws while a WebSocket is
     * still open, which would otherwise be reported against every test in the class.
     */
    fun shutdown() {
        sockets.values.forEach { runCatching { it.close(1000, null) } }
        sockets.clear()
        socket = null
        runCatching { server.shutdown() }
    }

    /**
     * End the connection from the player's side.
     *
     * A close, not a yank: MockWebServer's server-side socket has no Call behind it, so
     * `cancel()` throws inside OkHttp. It still exercises what matters — the client did not
     * ask for this, so it must surface as a failure and drive a reconnect. What it cannot
     * reproduce is the zombie case, a socket that stays open and stops answering; that is
     * what the keepalive and [SonosHousehold.onNetworkChanged] are for, and it stays
     * untested here.
     */
    fun dropConnection() {
        val ws = socket ?: return
        ws.close(1000, "player going away")
        sockets.values.remove(ws)
        socket = null
    }

    /**
     * End one player's connection and leave the rest, as when one speaker in a group loses
     * power. Throws if the client never opened one to that player, so a test cannot pass by
     * dropping nothing.
     */
    fun dropConnection(playerId: String) {
        val name = PlayerNames.localHostname(playerId)!!.lowercase()
        val ws = sockets.remove(name) ?: error("no connection open to $playerId")
        ws.close(1000, "player going away")
        if (socket === ws) socket = null
    }

    private val stalledHandshakes = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val unreachable = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Refuse every future handshake to [playerId], as a speaker that has been unplugged while
     * the topology still lists it does — Sonos goes on naming one for minutes.
     */
    fun makeUnreachable(playerId: String) {
        unreachable += PlayerNames.localHostname(playerId)!!.lowercase()
    }

    /** Plug it back in. */
    fun makeReachable(playerId: String) {
        unreachable -= PlayerNames.localHostname(playerId)!!.lowercase()
    }

    /** Make every future handshake to [playerId] take [millis], as an unreachable speaker's does. */
    fun stallHandshakesTo(playerId: String, millis: Long) {
        stalledHandshakes[PlayerNames.localHostname(playerId)!!.lowercase()] = millis
    }

    /** Whether the client holds a connection to [playerId] right now. */
    fun isConnected(playerId: String): Boolean =
        sockets.containsKey(PlayerNames.localHostname(playerId)!!.lowercase())

    /**
     * Serve a topology from `getGroups` without announcing it — what a client that missed the
     * event would find if it asked.
     */
    fun serveTopology(topology: JsonObject) {
        groups = topology
    }

    /** Answer nothing to the malformed "what household am I?" frame, as a real One SL did. */
    @Volatile var ignoreHouseholdProbe = false

    /**
     * The `eq` the settings reply carries, over the captured one's. A tone write goes over UPnP,
     * which this fake does not serve, so a test that writes sets this to what the player would
     * now report.
     */
    @Volatile var eq: Triple<Int, Int, Boolean>? = null

    @Volatile private var upnpAllowed = true
    @Volatile private var securityVersion = 9

    private fun security(): JsonObject = fixture("getSettingsGroup.security.reply.json").apply {
        getAsJsonObject("attributes").addProperty("allowInsecureUPnP", upnpAllowed)
        addProperty("timestamp", securityVersion.toString())
    }

    /**
     * Flip the household's UPnP switch, the way the Sonos app does: the read answers the new
     * value, and a `settingsChanged` on [playerId] announces `security` at a new version.
     * Pass [announce] false to change it silently, as a missed event would leave it.
     */
    fun setUpnpAllowed(allowed: Boolean, playerId: String = id, announce: Boolean = true) {
        upnpAllowed = allowed
        securityVersion++
        if (!announce) return
        val event = fixture("event.settingsChanged.json")
        event.getAsJsonArray("settingsGroupMetadata")
            .map { it.asJsonObject }
            .first { it.get("name").asString == "security" }
            .addProperty("timestamp", securityVersion.toString())
        emit("effectiveSettings:1", "settingsChanged", event, groupId = null, playerId = playerId)
    }

    private val refused = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Refuse every [command] from now on, the way a real player says no: `globalError`. */
    fun refuse(command: String, errorCode: String = "ERROR_COMMAND_FAILED") {
        refused[command] = errorCode
    }

    /** Stop refusing [command]. */
    fun allow(command: String) {
        refused.remove(command)
    }

    /** Serve and announce a topology, the way a real regrouping arrives. */
    fun pushTopology(topology: JsonObject) {
        groups = topology
        emit("groups:1", "groups", topology, groupId = null)
    }

    /**
     * Push an event the way a player sends one about *itself* when its header names nobody: on
     * that player's own socket, with `playerId` null. A Beam's `hdmi:1` event is shaped so
     * (captured 2026-10-02); pushing one with the id in the header, as this fake used to, let a
     * handler that needed it pass here and drop every real one.
     */
    fun pushFromPlayer(playerId: String, namespace: String, type: String, body: JsonElement) {
        val header = JsonObject().apply {
            addProperty("namespace", namespace)
            addProperty("type", type)
            addProperty("householdId", householdId)
            add("playerId", com.google.gson.JsonNull.INSTANCE)
        }
        val ws = sockets[PlayerNames.localHostname(playerId)!!.lowercase()] ?: error("no connection open to $playerId")
        ws.send(JsonArray(2).apply { add(header); add(body) }.toString())
    }

    /** Push a captured event body verbatim. */
    fun pushFixture(type: String, groupId: String? = null) {
        emit(namespaceFor(type), type, fixture("event.$type.json"), groupId)
    }

    /** Push an unsolicited event — no `success`, which is what makes it an event. */
    fun push(namespace: String, type: String, body: String, groupId: String? = null, playerId: String? = null) =
        emit(namespace, type, JsonParser.parseString(body), groupId, playerId)

    /**
     * A speaker's own level, which a player addresses by `playerId` rather than by group —
     * the only event here that is not group-scoped, and the reason [emit] takes both.
     */
    fun pushPlayerVolume(playerId: String, volume: Int? = null, muted: Boolean? = null, fixed: Boolean? = null) {
        emit("playerVolume:1", "playerVolume", volumeBody("event.playerVolume.json", volume, muted, fixed), groupId = null, playerId = playerId)
    }

    /** A group's level, from the captured event with any of its three fields overridden. */
    fun pushGroupVolume(groupId: String, volume: Int? = null, muted: Boolean? = null, fixed: Boolean? = null) {
        emit("groupVolume:1", "groupVolume", volumeBody("event.groupVolume.json", volume, muted, fixed), groupId)
    }

    private fun volumeBody(capture: String, volume: Int?, muted: Boolean?, fixed: Boolean?) =
        fixture(capture).apply {
            volume?.let { addProperty("volume", it) }
            muted?.let { addProperty("muted", it) }
            fixed?.let { addProperty("fixed", it) }
        }

    /** A playback status from the capture, with the state or queue version a test is about. */
    fun pushPlaybackStatus(groupId: String, state: String? = null, queueVersion: String? = null) {
        val body = fixture("event.playbackStatus.json").apply {
            state?.let { addProperty("playbackState", it) }
            queueVersion?.let { addProperty("queueVersion", it) }
        }
        emit("playback:1", "playbackStatus", body, groupId)
    }

    /** This player as a connect seed: itself, on loopback, in its household. */
    val seed: Discovery.DiscoveredPlayer
        get() = Discovery.DiscoveredPlayer(id, InetAddress.getByName("127.0.0.1"), householdId)

    /** The group this player coordinates in [household]'s topology now. */
    fun groupId(household: SonosHousehold): String =
        household.state.value.groups.first { it.coordinatorId == id }.id

    private fun emit(
        namespace: String,
        type: String,
        body: JsonElement,
        groupId: String?,
        playerId: String? = null,
    ) {
        val header = JsonObject().apply {
            addProperty("namespace", namespace)
            addProperty("type", type)
            addProperty("householdId", householdId)
            groupId?.let { addProperty("groupId", it) }
            playerId?.let { addProperty("playerId", it) }
        }
        // Any live connection will do: the household routes events by their header, not by
        // which socket carried them. The fake's own player is preferred, as the seed.
        val ws = sockets[hostname.lowercase()] ?: socket ?: sockets.values.firstOrNull()
            ?: error("nothing connected to the fake player")
        ws.send(JsonArray(2).apply { add(header); add(body) }.toString())
    }

    /**
     * Hold the reply to [command] until [releaseReplies], so a caller stays suspended in it.
     *
     * A real player answers across a network; this one answers in microseconds, which makes
     * the window where a command is *in flight* impossible to aim at. Some behaviour lives
     * only in that window — cancelling a debounced volume job while its command is
     * outstanding, for one — so it has to be widened deliberately rather than slept at.
     */
    fun holdRepliesTo(command: String) {
        releaseLatch.set(CountDownLatch(1))
        heldCommand.set(command)
    }

    fun releaseReplies() {
        heldCommand.set(null)
        releaseLatch.get().countDown()
    }

    private fun awaitRelease(command: String?) {
        if (command == null || command != heldCommand.get()) return
        // Bounded, so a test that forgets to release fails rather than hanging the suite.
        releaseLatch.get().await(10, TimeUnit.SECONDS)
    }

    /** Blocks until the client sends [command], or fails. */
    fun awaitCommand(command: String, timeoutMillis: Long = 2_000): JsonObject =
        awaitCommand(timeoutMillis) { it.get("command")?.asString == command }

    /** Blocks until the client sends a command matching [predicate], or fails. */
    fun awaitCommand(timeoutMillis: Long = 2_000, predicate: (JsonObject) -> Boolean): JsonObject {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val next = received.poll(timeoutMillis, TimeUnit.MILLISECONDS) ?: break
            if (predicate(next)) return next
        }
        error("no matching command within ${timeoutMillis}ms")
    }

    private val heldCommand = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private val releaseLatch = java.util.concurrent.atomic.AtomicReference(CountDownLatch(0))

    private fun upgrade(host: String) = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            sockets[host] = webSocket
            socket = webSocket
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            sockets.remove(host, webSocket)
            webSocket.close(1000, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            sockets.remove(host, webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = JsonParser.parseString(text).asJsonArray
            val header = frame[0].asJsonObject
            // Record the body *first*. A test blocked in awaitCommand wakes the instant the
            // header is enqueued, and would otherwise be able to read lastCommandBody
            // before this thread had stored it.
            header.get("command")?.asString?.let { command ->
                val body = frame[1].takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
                synchronized(this@FakePlayer) {
                    bodies += command to body
                    commandLog += header.get("namespace")?.asString to command
                }
            }
            received += header
            awaitRelease(header.get("command")?.asString)
            reply(webSocket, header)
        }
    })

    private fun reply(webSocket: WebSocket, request: JsonObject) {
        val namespace = request.get("namespace")?.asString
        val command = request.get("command")?.asString
        val cmdId = request.get("cmdId")?.asString

        // No namespace is the "what household am I?" probe: it fails by design, and the
        // answer rides in the header rather than the body.
        if (namespace == null) {
            // A One SL on p20.96.1 sent nothing back at all; [ignoreHouseholdProbe] does the same.
            if (ignoreHouseholdProbe) return
            respond(webSocket, cmdId, null, "none", success = false, body = JsonObject())
            return
        }
        // Refusals are keyed by command, or by "namespace/command" for one namespace's — "subscribe"
        // alone would refuse every subscription in the house.
        (refused["$namespace/$command"] ?: refused[command ?: ""])?.let { errorCode ->
            respond(
                webSocket, cmdId, namespace, "globalError", success = false,
                body = JsonObject().apply { addProperty("errorCode", errorCode) },
            )
            return
        }
        // Captured off the office One SL, eight items, one a playlist since deleted.
        // Three of the home household's favourites, one stripped to the shell a removed
        // service leaves behind.
        if (namespace == "favorites:1" && command == "getFavorites") {
            respond(webSocket, cmdId, namespace, "favoritesList", success = true, body = fixture("getFavorites.reply.json"))
            return
        }
        if (namespace == "history:1" && command == "getHistory") {
            respond(webSocket, cmdId, namespace, "contentPagedResources", success = true, body = fixture("getHistory.reply.json"))
            return
        }
        // Captured off the office One SL, with one queue saved as a playlist for the purpose.
        if (namespace == "playlists:1" && command == "getPlaylists") {
            respond(webSocket, cmdId, namespace, "playlistsList", success = true, body = fixture("getPlaylists.reply.json"))
            return
        }
        // Captured off Kitchen: the session the commands after it are addressed by.
        if (namespace == "playbackSession:1" && command == "createSession") {
            respond(webSocket, cmdId, namespace, "sessionStatus", success = true, body = fixture("createSession.reply.json"))
            return
        }
        if (namespace == "groups:1" && command == "getGroups") {
            respond(webSocket, cmdId, namespace, "groups", success = true, body = groups)
            return
        }
        // A player-scoped command naming an id this household does not have is refused,
        // the way a real player refuses one: ERROR_INVALID_OBJECT_ID. Without this the
        // fake said yes to everything and any test of a refusal passed vacuously.
        val playerId = request.get("playerId")?.asString
        if (playerId != null && playerId !in knownPlayerIds()) {
            respond(
                webSocket, cmdId, namespace, "globalError", success = false,
                body = JsonObject().apply {
                    addProperty("errorCode", "ERROR_INVALID_OBJECT_ID")
                    addProperty("reason", "Incorrect playerId")
                },
            )
            return
        }
        // Captured off a real Beam, so it carries the whole object — spatial audio, the
        // voice block, an `eq` the Control API will read but not write — and not merely the
        // two fields this app looks at. A fixture trimmed to what the parser wants would
        // stop being evidence of what the player sends.
        // Captured off the office One SL with UPnP on; [setUpnpAllowed] flips the one
        // attribute, as the switch in the Sonos app does.
        if (namespace == "effectiveSettings:1" && command == "getSettingsGroup") {
            respond(webSocket, cmdId, namespace, "settings", success = true, body = security())
            return
        }
        if (namespace == "settings:1" && command == "getPlayerSettings") {
            respond(
                webSocket, cmdId, namespace, "playerSettings", success = true,
                body = fixture("getPlayerSettings.reply.json").apply {
                    eq?.let { (bass, treble, loudness) ->
                        getAsJsonObject("eq").apply {
                            addProperty("bass", bass); addProperty("treble", treble); addProperty("loudness", loudness)
                        }
                    }
                },
            )
            return
        }
        respond(webSocket, cmdId, namespace, "none", success = true, body = JsonObject())
    }

    private fun respond(
        webSocket: WebSocket,
        cmdId: String?,
        namespace: String?,
        type: String,
        success: Boolean,
        body: JsonElement,
    ) {
        val header = JsonObject().apply {
            namespace?.let { addProperty("namespace", it) }
            addProperty("householdId", householdId)
            addProperty("success", success)
            addProperty("type", type)
            cmdId?.let { addProperty("cmdId", it) }
        }
        webSocket.send(JsonArray(2).apply { add(header); add(body) }.toString())
    }

    companion object {

        /** Reads a verbatim capture from `src/testFixtures/resources/fixtures/`, as JSON. */
        fun fixture(name: String): JsonObject = JsonParser.parseString(fixtureText(name)).asJsonObject

        /** The same, as text: the UPnP replies and `/status/zp` are XML. */
        fun fixtureText(name: String): String =
            FakePlayer::class.java.getResourceAsStream("/fixtures/$name")?.readBytes()?.decodeToString()
                ?: error("missing fixture $name — capture it from a real player, do not write one")

        fun knownPlayerIds(): Set<String> =
            fixture("getGroups.reply.json").getAsJsonArray("players")
                .map { it.asJsonObject.get("id").asString }
                .toSet()

        fun fixtureCoordinatorId(): String =
            fixture("getGroups.reply.json").getAsJsonArray("groups")[0]
                .asJsonObject.get("coordinatorId").asString

        /**
         * Every *player* the captured topology names, derived rather than listed.
         *
         * Players, not just coordinators: the household opens a socket per player for
         * `playerVolume:1`, inside a `runCatching` that swallows failures. A fixture
         * captured with rooms grouped would have fewer coordinators than players, and the
         * non-coordinators' names would be missing from the certificate — so those
         * subscriptions would fail hostname verification and the suite would quietly stop
         * covering them.
         */
        fun playerHostnames(): List<String> =
            fixture("getGroups.reply.json").getAsJsonArray("players")
                .map { it.asJsonObject.get("id").asString }
                .mapNotNull { PlayerNames.localHostname(it) }
                .distinct()

        /**
         * The captured topology with its `websocketUrl` hosts pointed at loopback.
         *
         * The fixture on disk keeps addresses as captured (rewritten to TEST-NET-1, so
         * nothing real leaks), which is right for a record of what a player sends — but
         * they are unroutable. The copy served here points at this server instead. It is
         * the only change made to any captured payload, and it is made here rather than on
         * disk so the fixture stays a faithful record.
         */
        fun reachableTopology(): JsonObject {
            val rewritten = fixture("getGroups.reply.json").toString()
                .replace(Regex("wss://[0-9.]+:[0-9]+/"), "wss://127.0.0.1:1443/")
            return JsonParser.parseString(rewritten).asJsonObject
        }

        /**
         * The captured topology with one room's player merged into another's group.
         *
         * Every group in the capture has exactly one player, which is true of this
         * household and useless for testing anything about *members*: a check that only
         * ever looked at the coordinator would pass. The host group also takes a new id,
         * because that is what a regroup really does. Rather than hand-writing a grouped
         * payload — which would be inventing a shape — this rearranges the captured one,
         * so every field stays as a real player sent it and only the membership moves.
         */
        fun groupedTopology(coordinatorRoom: String, memberRoom: String): JsonObject {
            val topology = reachableTopology()
            val groups = topology.getAsJsonArray("groups")
            val host = groups.map { it.asJsonObject }.first { it.get("name").asString == coordinatorRoom }
            val joiner = groups.map { it.asJsonObject }.first { it.get("name").asString == memberRoom }

            joiner.getAsJsonArray("playerIds").forEach { host.getAsJsonArray("playerIds").add(it) }

            // A real regroup mints a *new* group id — the suffix after the coordinator's
            // RINCON changes. Keeping the old id would quietly make this a much weaker
            // fixture than it looks: a household that only ever re-subscribes on a changed
            // id would pass, which is exactly the bug worth catching.
            val id = host.get("id").asString
            host.addProperty("id", id.substringBeforeLast(":") + ":" + (System.nanoTime() % 1_000_000_000))
            val remaining = JsonArray().apply {
                groups.filter { it.asJsonObject.get("name").asString != memberRoom }.forEach { add(it) }
            }
            topology.add("groups", remaining)
            return topology
        }

        private fun namespaceFor(type: String) = when (type) {
            // playbackError shares the namespace and differs only in its type: captured off
            // a One SL playing a URL that does not resolve.
            "playbackStatus", "radioPlaybackStatus", "playbackError" -> "playback:1"
            // The two TV captures are metadata too; they differ only in what they carry —
            // one a stereo input, one Dolby 5.1 — and both arrive on this namespace.
            // radioMetadataStatus is a stream loaded by URL: no currentItem and no track
            // object at all, which is the whole reason `streamInfo` has to be read.
            "metadataStatus", "tvMetadataStatus", "tvSurroundMetadataStatus",
            "radioMetadataStatus", "stationMetadataStatus" -> "playbackMetadata:1"
            "groupVolume" -> "groupVolume:1"
            "playerVolume" -> "playerVolume:1"
            "groups" -> "groups:1"
            "settingsChanged" -> "effectiveSettings:1"
            // Captured with Bedroom's left surround unplugged.
            "activeZonesChange", "zoneDefinitionsChange" -> "zones:1"
            // A Beam's HDMI port, with a TV (Bedroom) and with nothing in it (Guest TV).
            "hdmiStatus" -> "hdmi:1"
            else -> error("no namespace known for $type")
        }
    }
}
