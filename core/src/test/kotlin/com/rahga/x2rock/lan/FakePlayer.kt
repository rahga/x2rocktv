package com.rahga.x2rock.lan

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HeldCertificate
import okhttp3.tls.HandshakeCertificates
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * A Sonos player, near enough to test against.
 *
 * The point of this class is that the transport has been verifiable only by pointing it at
 * real speakers — which meant every check was a throwaway, ran nowhere but this network,
 * and caught no regressions. This serves the same protocol over a real TLS WebSocket, so
 * connection lifecycle, subscriptions, event routing and reconnection can all be asserted
 * on without hardware.
 *
 * It is deliberately faithful where faithfulness is load-bearing:
 *
 * - It serves a certificate whose SAN is the player's own `sonos-<MAC>.local` name, so the
 *   [PlayerAddressBook] and the default hostname verifier are exercised rather than
 *   sidestepped. A test that passed by turning verification off would prove nothing about
 *   the design it is meant to protect.
 * - It **rejects the handshake** the way a real player does: 403 if an `Origin` header is
 *   present, 400 if the API key is missing. Those two facts cost a while to establish
 *   against hardware and are otherwise recorded only in a comment.
 * - Replies carry `success`; pushed events never do, which is the only thing distinguishing
 *   them on the wire.
 */
class FakePlayer(
    val id: String = "RINCON_48A6B8306687" + "01400",
    val householdId: String = "Sonos_Fake.Household",
) {

    val hostname: String = PlayerNames.localHostname(id)!!

    /** Every command frame the client sent, so tests can assert on scope and ordering. */
    val received = LinkedBlockingQueue<JsonObject>()

    private val certificate = HeldCertificate.Builder()
        .addSubjectAlternativeName(hostname)
        .commonName(id.removePrefix("RINCON_").dropLast(5))
        .build()

    private val serverCertificates = HandshakeCertificates.Builder()
        .heldCertificate(certificate)
        .build()

    private val server = MockWebServer().apply {
        useHttps(serverCertificates.sslSocketFactory(), false)
    }

    /** Trusts this fake's certificate only — nothing here trusts everything. */
    val clientCertificates: HandshakeCertificates = HandshakeCertificates.Builder()
        .addTrustedCertificate(certificate.certificate)
        .build()

    val sslSocketFactory: SSLSocketFactory get() = clientCertificates.sslSocketFactory()
    val trustManager: X509TrustManager get() = clientCertificates.trustManager

    val port: Int get() = server.port

    @Volatile private var socket: WebSocket? = null
    @Volatile private var groups: JsonObject = defaultGroups(id, "Kitchen")

    /** Handshakes the client made that the player refused, with the code it refused them with. */
    val rejected = LinkedBlockingQueue<Int>()

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                // A real player's two refusals, both established the hard way against
                // hardware. Encoding them here means the client keeps being held to them.
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

    /** The upgrade request, so a test can assert on what the client did and did not send. */
    @Volatile var lastUpgrade: RecordedRequest? = null
        private set

    /**
     * MockWebServer's shutdown waits for its queue to drain and gives up — throwing — while
     * a WebSocket is still open. Close ours first so teardown is quiet; a leftover "gave up
     * waiting" would otherwise be reported against every test in the class.
     */
    fun shutdown() {
        runCatching { socket?.close(1000, null) }
        socket = null
        runCatching { server.shutdown() }
    }

    /**
     * End the connection from the player's side.
     *
     * This is a close, not a yank: MockWebServer's server-side socket has no Call behind it,
     * so `cancel()` throws inside OkHttp. It still exercises what matters — the client did
     * not ask for this, so it surfaces as a failure and drives a reconnect. What it cannot
     * reproduce is the zombie case, where a socket stays open and simply stops answering;
     * that is what the keepalive and [SonosHousehold.onNetworkChanged] exist for, and it
     * remains untested here.
     */
    fun dropConnection() {
        socket?.close(1000, "player going away")
        socket = null
    }

    /** Push an unsolicited event — no `success`, which is what makes it an event. */
    fun push(namespace: String, type: String, body: String, groupId: String? = null) {
        val header = JsonObject().apply {
            addProperty("namespace", namespace)
            addProperty("type", type)
            addProperty("householdId", householdId)
            groupId?.let { addProperty("groupId", it) }
        }
        send(header, JsonParser.parseString(body))
    }

    fun setGroups(groups: JsonObject) { this.groups = groups }

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
            received += header
            reply(webSocket, header)
        }
    })

    private fun reply(webSocket: WebSocket, request: JsonObject) {
        val namespace = request.get("namespace")?.asString
        val command = request.get("command")?.asString
        val cmdId = request.get("cmdId")?.asString

        // No namespace at all is the "what household am I?" probe: it fails by design, and
        // the answer is in the header rather than the body.
        if (namespace == null) {
            respond(webSocket, cmdId, namespace = null, type = "none", success = false, body = JsonObject())
            return
        }

        when {
            namespace == "groups:1" && command == "getGroups" ->
                respond(webSocket, cmdId, namespace, "groups", true, groups)

            command == "subscribe" ->
                respond(webSocket, cmdId, namespace, "none", true, JsonObject())

            else ->
                respond(webSocket, cmdId, namespace, "none", true, JsonObject())
        }
    }

    private fun respond(
        webSocket: WebSocket,
        cmdId: String?,
        namespace: String?,
        type: String,
        success: Boolean,
        body: com.google.gson.JsonElement,
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

    private fun send(header: JsonObject, body: com.google.gson.JsonElement) {
        val ws = socket ?: error("nothing connected to the fake player")
        ws.send(JsonArray(2).apply { add(header); add(body) }.toString())
    }

    companion object {
        fun defaultGroups(playerId: String, roomName: String): JsonObject {
            val group = JsonObject().apply {
                addProperty("id", "$playerId:1")
                addProperty("name", roomName)
                addProperty("coordinatorId", playerId)
                addProperty("playbackState", "PLAYBACK_STATE_PLAYING")
                add("playerIds", JsonArray().apply { add(playerId) })
            }
            val player = JsonObject().apply {
                addProperty("id", playerId)
                addProperty("name", roomName)
                addProperty("websocketUrl", "wss://127.0.0.1:1443/websocket/api")
            }
            return JsonObject().apply {
                add("groups", JsonArray().apply { add(group) })
                add("players", JsonArray().apply { add(player) })
            }
        }
    }
}
