package com.rahga.x2rock.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder

/**
 * The TV's end of a sign-in: the one moment this app listens on the network. The phone that signed in
 * is forwarded here by the redirect page (see [signInState]) with `GET /callback?code=…&state=…`; the
 * request is checked against the state this sign-in sent, answered with a short page for the phone,
 * and the code handed back. Anything else that connects is answered 404 and the wait goes on.
 *
 * Plain HTTP on the LAN, which is all a browser can be sent to here: what crosses it is a one-time
 * code that is worth nothing without the integration's secret, and the secret never leaves the TV.
 */
object SignInCallback {

    /** How long a connected client gets to send its request, and how much of one is read. */
    private const val READ_TIMEOUT_MILLIS = 5_000
    private const val MAX_REQUEST = 16 * 1024

    /**
     * Wait on [server] for the redirect carrying [state]; the code it brings, or [SignInException] if
     * Sonos sent an error instead. Cancelling the wait closes [server].
     */
    suspend fun awaitCode(server: ServerSocket, state: String): String = coroutineScope {
        // `accept` hears nothing of a cancellation; closing the socket is what ends it. The closer
        // runs when this scope is cancelled, and when the wait ends: the listener goes with it.
        val closer = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { runCatching { server.close() } } }
        try {
            withContext(Dispatchers.IO) {
                var outcome: Result<String>? = null
                while (outcome == null) {
                    val socket = try { server.accept() } catch (e: SocketException) {
                        // Closed under the wait: that is the closer, which runs only when this ends or is
                        // cancelled — and the cancellation can land on this thread after the socket
                        // error does, which then escaped as a failure (the test caught it under load).
                        if (server.isClosed) throw CancellationException("the sign-in's listener was closed").apply { initCause(e) }
                        throw e
                    }
                    outcome = socket.use { answer(it, state) }
                }
                outcome.getOrThrow()
            }
        } finally {
            closer.cancel()
        }
    }

    /** One connection: the outcome if it was this sign-in's redirect, `null` to keep waiting. */
    private fun answer(socket: Socket, state: String): Result<String>? {
        socket.soTimeout = READ_TIMEOUT_MILLIS
        val line = runCatching { readRequestLine(socket) }.getOrNull()
        // The rest of the request too, before answering: a socket closed with any of it unread is
        // reset rather than ended, and the reset throws away a reply still waiting to be sent. On
        // the Streamer's Wi-Fi the page was still queued, and the phone's Chrome sat on a blank tab
        // over a sign-in that had worked (2026-10-17, logged on the TV: 267 bytes written, none seen).
        if (line != null) runCatching { readHeaders(socket) }
        val target = line?.split(' ')?.getOrNull(1)
        if (line == null || !line.startsWith("GET ") || target == null || !target.startsWith("/callback?")) {
            respond(socket, 404, "Not here", "This is x2rock on a TV, waiting for a Sonos sign-in.")
            return null
        }
        // A bad escape (`%zz`) is a stray request, not the end of the sign-in.
        val query = try { parseQuery(target.substringAfter('?')) } catch (_: IllegalArgumentException) {
            respond(socket, 400, "Not this sign-in", "Start the sign-in on the TV again, and scan the code it shows now.")
            return null
        }
        if (query["state"] != state) {
            // Another sign-in's redirect, or a stale tab: not this one's to redeem.
            respond(socket, 400, "Not this sign-in", "Start the sign-in on the TV again, and scan the code it shows now.")
            return null
        }
        query["error"]?.let { error ->
            val said = listOfNotNull(error, query["error_description"]).joinToString(": ")
            respond(socket, 200, "Sonos did not sign you in", said)
            return Result.failure(SignInException("Sonos did not sign you in: $said"))
        }
        val code = query["code"]?.takeIf { it.isNotEmpty() }
        if (code == null) {
            respond(socket, 400, "No code", "Sonos sent no code. Start again on the TV.")
            return Result.failure(SignInException("the sign-in came back with no code"))
        }
        respond(socket, 200, "Signed in", "Back to the TV: it finishes from here. You can close this page.")
        return Result.success(code)
    }

    /** Read and drop the request's headers, up to the blank line that ends them. */
    private fun readHeaders(socket: Socket) {
        var read = 0
        while (read < MAX_REQUEST && readRequestLine(socket).also { read += it.length + 2 }.isNotEmpty()) Unit
    }

    private fun readRequestLine(socket: Socket): String {
        val input = socket.getInputStream()
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() < MAX_REQUEST) {
            val b = input.read()
            if (b == -1 || b == '\n'.code) break
            if (b != '\r'.code) bytes.write(b)
        }
        return bytes.toString(Charsets.ISO_8859_1.name())
    }

    internal fun parseQuery(query: String): Map<String, String> =
        query.split('&').filter { it.isNotEmpty() }.associate { pair ->
            val parts = pair.split('=', limit = 2)
            val k = parts[0]
            val v = parts.getOrElse(1) { "" }
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }

    private fun respond(socket: Socket, status: Int, title: String, text: String) {
        val escape = { s: String -> s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") }
        val body = "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width\">" +
            "<title>x2rock</title><body style=\"font:18px system-ui;margin:48px 16px\">" +
            "<h1 style=\"font-size:22px\">${escape(title)}</h1><p>${escape(text)}</p>"
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; else -> "Not Found" }
        runCatching {
            socket.getOutputStream().apply {
                write(
                    ("HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
                        .toByteArray(Charsets.ISO_8859_1)
                )
                write(bytes)
                flush()
            }
            // End our side and wait for the browser to end its own, so the page is delivered before the
            // socket goes: a close straight after the write could still reset it. Bounded by the read
            // timeout; a browser that keeps the connection open is let go then.
            socket.shutdownOutput()
            runCatching { while (socket.getInputStream().read() != -1) Unit }
        }
    }
}

/**
 * This device's own address on the LAN [peer] is on — the one a phone on the same network reaches it
 * by. A connected UDP socket sends nothing; it only asks the system which interface would carry it.
 */
fun lanAddressToward(peer: InetAddress): Inet4Address? = runCatching {
    DatagramSocket().use { socket ->
        socket.connect(peer, 1400)
        socket.localAddress as? Inet4Address
    }
}.getOrNull()?.takeIf { it.isSiteLocalAddress }
