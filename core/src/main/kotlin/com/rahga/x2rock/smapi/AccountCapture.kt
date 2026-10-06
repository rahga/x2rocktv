package com.rahga.x2rock.smapi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Capturing the encrypted `ThirdPartyMediaServersX` envelope off a player.
 *
 * The player only includes it in the **first** GENA event it sends a new subscriber, and
 * refuses a direct read, so this does what the speakers do to each other: it SUBSCRIBEs to the
 * player's `ZoneGroupTopology` events over cleartext 1400, catches the initial NOTIFY the
 * player POSTs back to a listener opened for the moment, pulls the one variable out, and
 * unsubscribes. That inbound NOTIFY is **the one time a Sonos player reaches out to this
 * device** rather than the other way around — the app otherwise never hosts a listener, which
 * is why this is deliberately a one-shot capture with a short-lived socket and not standing
 * UPnP eventing (the queue is re-read on a pushed version instead; see `Upnp`).
 *
 * A direct port of x2rock's `sonos/stored.rs` capture half. The pure parsing ([extractVariable],
 * [xmlUnescape], [header]) carries the tests; only [captureEnvelope] touches the network.
 */
object AccountCapture {

    private const val EVENT_PATH = "/ZoneGroupTopology/Event"

    /**
     * The `ThirdPartyMediaServersX` envelope from the player at [playerIp], or `null` if none
     * arrives within [timeoutMillis]. Feed it to [StoredAccounts.decryptAccounts] with the
     * household's **short** id.
     *
     * On Android the listener binds an ephemeral port and the player connects back to it; no
     * firewall sits in the app's way the way one does on a laptop, so no fixed port is needed.
     */
    suspend fun captureEnvelope(playerIp: String, timeoutMillis: Long = 10_000): String? =
        withContext(Dispatchers.IO) {
            ServerSocket().use { server ->
                server.bind(InetSocketAddress(0))
                val localIp = localIpToward(playerIp)
                val callback = "<http://$localIp:${server.localPort}/notify>"
                val sid = subscribe(playerIp, callback) ?: return@withContext null
                try {
                    withTimeoutOrNull(timeoutMillis) {
                        // The first NOTIFY carries the full initial state, so one accept is
                        // enough — but loop in case a keepalive or partial event lands first.
                        var found: String? = null
                        while (found == null) {
                            val socket = runCatching { server.accept() }.getOrNull() ?: break
                            socket.use {
                                val request = runCatching { readHttpMessage(it) }.getOrNull().orEmpty()
                                // A GENA NOTIFY wants a 200 or the player retries, then drops the sub.
                                runCatching {
                                    it.getOutputStream().write(
                                        "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray()
                                    )
                                }
                                found = extractVariable(request, "ThirdPartyMediaServersX")
                            }
                        }
                        found
                    }
                } finally {
                    runCatching { unsubscribe(playerIp, sid) }
                }
            }
        }

    /** SUBSCRIBE to ZoneGroupTopology, returning the subscription id to cancel with. */
    private fun subscribe(playerIp: String, callback: String): String? {
        val request = "SUBSCRIBE $EVENT_PATH HTTP/1.1\r\n" +
            "HOST: $playerIp:1400\r\n" +
            "CALLBACK: $callback\r\n" +
            "NT: upnp:event\r\n" +
            "TIMEOUT: Second-60\r\n" +
            "Content-Length: 0\r\n" +
            "Connection: close\r\n\r\n"
        Socket().use { stream ->
            stream.connect(InetSocketAddress(playerIp, 1400), 5_000)
            stream.getOutputStream().write(request.toByteArray())
            return header(readHttpMessage(stream), "SID")
        }
    }

    /** Best-effort UNSUBSCRIBE. A lost one costs nothing: the subscription expires in a minute. */
    private fun unsubscribe(playerIp: String, sid: String) {
        val request = "UNSUBSCRIBE $EVENT_PATH HTTP/1.1\r\n" +
            "HOST: $playerIp:1400\r\n" +
            "SID: $sid\r\n" +
            "Connection: close\r\n\r\n"
        runCatching {
            Socket().use { stream ->
                stream.connect(InetSocketAddress(playerIp, 1400), 2_000)
                stream.getOutputStream().write(request.toByteArray())
                readHttpMessage(stream)
            }
        }
    }

    /** The local address that routes toward [playerIp] — what the player must call back on. */
    private fun localIpToward(playerIp: String): String =
        DatagramSocket().use { probe ->
            // No packet is sent; connecting a UDP socket only selects the source interface.
            probe.connect(InetAddress.getByName(playerIp), 1400)
            probe.localAddress.hostAddress ?: error("no local address toward $playerIp")
        }

    /** Read one HTTP message (head plus any `Content-Length` body) to a string. */
    private fun readHttpMessage(socket: Socket): String = readHttpMessage(socket.getInputStream())

    internal fun readHttpMessage(input: java.io.InputStream): String {
        val raw = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        while (true) {
            val bytes = raw.toByteArray()
            val headEnd = indexOfCrlfCrlf(bytes)
            if (headEnd >= 0) {
                val head = String(bytes, 0, headEnd, Charsets.ISO_8859_1)
                val want = header(head, "Content-Length")?.toIntOrNull() ?: 0
                if (bytes.size >= headEnd + 4 + want) break
            }
            val n = try { input.read(chunk) } catch (_: IOException) { break }
            if (n <= 0) break
            raw.write(chunk, 0, n)
        }
        return String(raw.toByteArray(), Charsets.UTF_8)
    }

    private fun indexOfCrlfCrlf(bytes: ByteArray): Int {
        for (i in 0..bytes.size - 4) {
            if (bytes[i] == '\r'.code.toByte() && bytes[i + 1] == '\n'.code.toByte() &&
                bytes[i + 2] == '\r'.code.toByte() && bytes[i + 3] == '\n'.code.toByte()
            ) return i
        }
        return -1
    }

    /** A header value from an HTTP message head, case-insensitively. */
    internal fun header(message: String, name: String): String? =
        message.lineSequence()
            .mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon < 0) null
                else line.substring(0, colon).trim() to line.substring(colon + 1).trim()
            }
            .firstOrNull { it.first.equals(name, ignoreCase = true) }
            ?.second
            ?.ifEmpty { null }

    /**
     * Pull one evented variable's value out of a GENA property-set NOTIFY body. The body is
     * `<e:propertyset><e:property><Name>value</Name></e:property>…` with the values
     * entity-escaped; the envelope is a flat `2:…` string rather than nested XML, so one
     * unescape and a tag scan is enough.
     */
    internal fun extractVariable(message: String, name: String): String? {
        val unescaped = xmlUnescape(message)
        val open = "<$name>"
        val close = "</$name>"
        val start = unescaped.indexOf(open).takeIf { it >= 0 }?.plus(open.length) ?: return null
        val end = unescaped.indexOf(close, start).takeIf { it >= 0 } ?: return null
        return unescaped.substring(start, end).trim().ifEmpty { null }
    }

    /**
     * Undo the XML entities a GENA property set wraps its values in. The envelope can be
     * escaped once (`&lt;`) or twice; two passes settle both.
     */
    internal fun xmlUnescape(text: String): String {
        fun once(s: String) = s
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&amp;", "&")
        return once(once(text))
    }
}
