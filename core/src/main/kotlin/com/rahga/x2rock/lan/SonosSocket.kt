package com.rahga.x2rock.lan

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One WebSocket to one player, speaking the LAN Control API.
 *
 * Open it against a player's `sonos-<MAC>.local` name (see [PlayerNames]) with a client
 * from [LanHttp]. There is no login: two headers on the upgrade and the socket is live.
 *
 * **Scope matters.** Group-scoped namespaces (`playback:1`, `playbackMetadata:1`,
 * `groupVolume:1`) must go to that group's *coordinator's* socket, and player-scoped ones
 * (`playerVolume:1`) to that *player's own* socket. Anything else answers
 * `ERROR_INVALID_OBJECT_ID`. A caller that talks to several groups needs several sockets.
 */
class SonosSocket private constructor(
    private val socket: WebSocket,
    private val pending: ConcurrentHashMap<String, CompletableDeferred<SonosReply>>,
    private val _events: MutableSharedFlow<SonosEvent>,
    private val _failures: MutableSharedFlow<Throwable>,
    private val closedByUs: java.util.concurrent.atomic.AtomicBoolean,
    val hostname: String,
) {

    /**
     * Emits once when this socket dies, for any reason.
     *
     * Replayed, so a collector that arrives after the failure still hears about it — the
     * alternative is a socket that is quietly dead and a caller that never finds out.
     */
    val failures: SharedFlow<Throwable> get() = _failures.asSharedFlow()

    /**
     * Everything the player says without being asked, including each subscription's
     * opening snapshot.
     *
     * **Collect this before subscribing.** A `subscribe` reply is followed immediately by
     * the snapshot, and a collector started afterwards misses it.
     */
    val events: SharedFlow<SonosEvent> get() = _events.asSharedFlow()

    private val nextId = AtomicLong(1)

    /**
     * Sends a command and waits for its reply, correlated by `cmdId`.
     *
     * Throws [SonosCommandException] if the player refuses, or [IOException] if the reply
     * does not arrive — the socket may be a zombie, and the caller should reconnect
     * rather than retry on it.
     */
    suspend fun command(header: JsonObject, body: JsonObject = JsonObject()): JsonElement {
        val reply = send(header, body)
        if (!reply.isSuccess) {
            throw SonosCommandException(
                header.get("command")?.asString ?: "command",
                reply.errorOrNull() ?: "refused",
            )
        }
        return reply.body
    }

    /**
     * Like [command] but hands back a refusal instead of throwing.
     *
     * Needed because a refusal is sometimes the point: there is no command for "what
     * household am I?", and the cheapest way to ask is to send something invalid and read
     * `householdId` off the reply header.
     */
    suspend fun send(header: JsonObject, body: JsonObject = JsonObject()): SonosReply {
        val id = nextId.getAndIncrement().toString()
        val waiter = CompletableDeferred<SonosReply>()
        pending[id] = waiter
        try {
            if (!socket.send(Frames.encode(header, body, id))) {
                throw IOException("socket to $hostname is closed")
            }
            return try {
                withTimeout(REPLY_TIMEOUT_MILLIS) { waiter.await() }
            } catch (e: TimeoutCancellationException) {
                throw IOException("no reply from $hostname within ${REPLY_TIMEOUT_MILLIS}ms", e)
            }
        } finally {
            pending.remove(id)
        }
    }

    /** `subscribe` answers with the current state, then pushes changes as [events]. */
    suspend fun subscribe(header: JsonObject): JsonElement = command(header)

    /**
     * Drops the connection without waiting for a close handshake.
     *
     * The right call when the peer may already be gone — after a network change or a wake,
     * where [close] would block on a reply that is never coming.
     */
    fun cancel() {
        closedByUs.set(true)
        socket.cancel()
        failPending(IOException("socket to $hostname cancelled"))
    }

    /** Deliberate shutdown: no failure is emitted, because nothing broke. */
    fun close() {
        closedByUs.set(true)
        socket.close(1000, null)
        failPending(IOException("socket to $hostname closed"))
    }

    private fun failPending(cause: Throwable) {
        pending.values.forEach { it.completeExceptionally(cause) }
        pending.clear()
    }

    companion object {
        const val PORT = 1443

        /** A well-known sample key, not one issued to this project. */
        const val API_KEY = "123e4567-e89b-12d3-a456-426655440000"
        const val SUBPROTOCOL = "v1.api.smartspeaker.audio"

        private const val REPLY_TIMEOUT_MILLIS = 5_000L

        /**
         * Opens a socket, suspending until the upgrade completes or fails.
         *
         * Note what is *not* set: no `Origin` header. A player answers 403 if one is
         * present and 400 if the API key is missing — both verified. OkHttp adds no
         * Origin of its own, which is precisely why a WebView could not do this.
         */
        suspend fun open(
            client: OkHttpClient,
            hostname: String,
            port: Int = PORT,
        ): SonosSocket {
            val request = Request.Builder()
                .url("wss://$hostname:$port/websocket/api")
                .header("X-Sonos-Api-Key", API_KEY)
                .header("Sec-WebSocket-Protocol", SUBPROTOCOL)
                .build()

            val pending = ConcurrentHashMap<String, CompletableDeferred<SonosReply>>()
            val failures = MutableSharedFlow<Throwable>(replay = 1, extraBufferCapacity = 1)
            // Shared with the listener so a close we asked for can be told apart from one
            // that happened to us; only the latter is worth reconnecting over.
            val closedByUs = java.util.concurrent.atomic.AtomicBoolean(false)
            // Replay 0, but buffer generously: events arrive on OkHttp's reader thread and
            // must never block it, so emission is non-suspending and a slow collector
            // drops rather than stalls the socket.
            val events = MutableSharedFlow<SonosEvent>(extraBufferCapacity = 64)

            return suspendCancellableCoroutine { cont ->
                val listener = object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        cont.resume(
                            SonosSocket(webSocket, pending, events, failures, closedByUs, hostname)
                        )
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        when (val frame = Frames.decode(text)) {
                            is SonosReply -> frame.header.cmdId
                                ?.let { pending.remove(it) }
                                ?.complete(frame)
                            is SonosEvent -> events.tryEmit(frame)
                            null -> Unit
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        val cause = IOException(
                            "websocket to $hostname failed" +
                                (response?.let { " (http ${it.code})" } ?: ""),
                            t,
                        )
                        pending.values.forEach { it.completeExceptionally(cause) }
                        pending.clear()
                        if (cont.isActive) cont.resumeWithException(cause) else failures.tryEmit(cause)
                    }

                    /**
                     * The peer is closing. Acknowledge it, or OkHttp never completes the
                     * handshake and `onClosed` never arrives — which meant a player that
                     * shut the connection down went entirely unnoticed and no reconnect
                     * was ever attempted. Found by a test, not by hardware.
                     */
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                        val cause = IOException("websocket to $hostname closing ($code $reason)")
                        pending.values.forEach { it.completeExceptionally(cause) }
                        pending.clear()
                        if (!closedByUs.get()) failures.tryEmit(cause)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        val cause = IOException("websocket to $hostname closed ($code $reason)")
                        pending.values.forEach { it.completeExceptionally(cause) }
                        pending.clear()
                        // A close we asked for is not a failure worth reconnecting over.
                        if (!closedByUs.get()) failures.tryEmit(cause)
                    }
                }
                val ws = client.newWebSocket(request, listener)
                cont.invokeOnCancellation { ws.cancel() }
            }
        }
    }
}

/** The player understood the command and said no. Distinct from a transport failure. */
class SonosCommandException(
    val command: String,
    val detail: String,
) : IOException("$command failed: $detail")
