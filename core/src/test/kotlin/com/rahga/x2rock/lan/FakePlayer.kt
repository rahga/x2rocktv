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
 *   `src/test/resources/fixtures/`, recorded verbatim off a real household and redacted for
 *   identifiers only. The invented versions these replaced had no `_objectType` anywhere,
 *   no `queueVersion`, no `availablePlaybackActions`, and no stereo pair. A fake built from
 *   what one assumes the protocol looks like tests those assumptions against themselves and
 *   passes for the wrong reasons. **Add fixtures by capturing, never by writing them out.**
 * - It serves a certificate carrying the players' real `sonos-<MAC>.local` names, so the
 *   address book and OkHttp's default hostname verifier are exercised rather than switched
 *   off. A test that passed by disabling verification would prove nothing about the design
 *   it exists to protect.
 * - It refuses handshakes the way a player does: 403 with an `Origin` header, 400 without
 *   the API key. Both cost real effort to establish against hardware.
 */
class FakePlayer(
    /** The fixture's first coordinator, so the topology it serves is self-consistent. */
    val id: String = fixtureCoordinatorId(),
    val householdId: String = "Sonos_ExampleHousehold.ExampleToken",
) {

    val hostname: String = PlayerNames.localHostname(id)!!

    /** Every command frame the client sent, so tests can assert on scope and ordering. */
    val received = LinkedBlockingQueue<JsonObject>()

    /** Handshakes the player refused, with the code — empty is the expected state. */
    val rejected = LinkedBlockingQueue<Int>()

    /**
     * One certificate covering every coordinator in the captured topology.
     *
     * That topology has five groups on five different players, so the household opens a
     * socket per coordinator. This answers for all of them rather than flattening the
     * fixture into something more convenient.
     */
    private val certificate = HeldCertificate.Builder()
        .apply { coordinatorHostnames().forEach { addSubjectAlternativeName(it) } }
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
            val header = JsonParser.parseString(text).asJsonArray[0].asJsonObject
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

        /** Reads a verbatim capture from `src/test/resources/fixtures/`. */
        fun fixture(name: String): JsonObject {
            val stream = FakePlayer::class.java.getResourceAsStream("/fixtures/$name")
                ?: error("missing fixture $name — capture it from a real player, do not write one")
            return JsonParser.parseReader(stream.reader()).asJsonObject
        }

        fun fixtureCoordinatorId(): String =
            fixture("getGroups.reply.json").getAsJsonArray("groups")[0]
                .asJsonObject.get("coordinatorId").asString

        /**
         * Every coordinator the captured topology names, derived rather than listed so a
         * re-captured fixture does not also require updating a hardcoded set.
         */
        fun coordinatorHostnames(): List<String> =
            fixture("getGroups.reply.json").getAsJsonArray("groups")
                .map { it.asJsonObject.get("coordinatorId").asString }
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
