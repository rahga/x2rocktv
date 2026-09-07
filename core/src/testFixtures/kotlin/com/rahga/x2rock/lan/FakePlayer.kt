package com.rahga.x2rock.lan

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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
    private val commandLog = mutableListOf<String>()

    @Synchronized
    fun commandsNamed(command: String): Int = commandLog.count { it == command }

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
                return upgrade()
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    /**
     * MockWebServer's shutdown waits for its queue to drain and throws while a WebSocket is
     * still open, which would otherwise be reported against every test in the class.
     */
    fun shutdown() {
        runCatching { socket?.close(1000, null) }
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
        socket?.close(1000, "player going away")
        socket = null
    }

    /** Serve and announce a topology, the way a real regrouping arrives. */
    fun pushTopology(topology: JsonObject) {
        groups = topology
        emit("groups:1", "groups", topology, groupId = null)
    }

    /** Push a captured event body verbatim. */
    fun pushFixture(type: String, groupId: String? = null) {
        emit(namespaceFor(type), type, fixture("event.$type.json"), groupId)
    }

    /** Push an unsolicited event — no `success`, which is what makes it an event. */
    fun push(namespace: String, type: String, body: String, groupId: String? = null) =
        emit(namespace, type, JsonParser.parseString(body), groupId)

    private fun emit(namespace: String, type: String, body: JsonElement, groupId: String?) {
        val header = JsonObject().apply {
            addProperty("namespace", namespace)
            addProperty("type", type)
            addProperty("householdId", householdId)
            groupId?.let { addProperty("groupId", it) }
        }
        val ws = socket ?: error("nothing connected to the fake player")
        ws.send(JsonArray(2).apply { add(header); add(body) }.toString())
    }

    /** Blocks until the client sends a command matching [predicate], or fails. */
    fun awaitCommand(timeoutMillis: Long = 2_000, predicate: (JsonObject) -> Boolean): JsonObject {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val next = received.poll(timeoutMillis, TimeUnit.MILLISECONDS) ?: break
            if (predicate(next)) return next
        }
        error("no matching command within ${timeoutMillis}ms")
    }

    private fun upgrade() = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
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
                    commandLog += command
                }
            }
            received += header
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
            respond(webSocket, cmdId, null, "none", success = false, body = JsonObject())
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

        /** Reads a verbatim capture from `src/testFixtures/resources/fixtures/`. */
        fun fixture(name: String): JsonObject {
            val stream = FakePlayer::class.java.getResourceAsStream("/fixtures/$name")
                ?: error("missing fixture $name — capture it from a real player, do not write one")
            return JsonParser.parseReader(stream.reader()).asJsonObject
        }

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
            "playbackStatus" -> "playback:1"
            "metadataStatus" -> "playbackMetadata:1"
            "groupVolume" -> "groupVolume:1"
            "playerVolume" -> "playerVolume:1"
            "groups" -> "groups:1"
            else -> error("no namespace known for $type")
        }
    }
}
