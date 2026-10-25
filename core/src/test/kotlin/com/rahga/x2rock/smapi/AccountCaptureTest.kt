package com.rahga.x2rock.smapi

import org.junit.Assert.assertTrue
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the pure half of the account capture — extracting the envelope from a GENA NOTIFY
 * body, reading an HTTP message, and finding a header. The network half
 * ([AccountCapture.captureEnvelope]) is checked here only for how it lets go — a deadline, a
 * refusal, a caller leaving — against a stand-in on loopback; capturing a
 * real envelope needs a real player, and is verified there, not here.
 */
class AccountCaptureTest {

    @Test fun `an escaped property value is recovered`() {
        // The player entity-escapes the value inside the property set. The test used to hand
        // in the tags unescaped, so the unescape it names was never exercised.
        val body = "<e:propertyset><e:property>" +
            "&lt;ThirdPartyMediaServersX&gt;2:AAAA&lt;/ThirdPartyMediaServersX&gt;" +
            "</e:property></e:propertyset>"
        assertEquals("2:AAAA", AccountCapture.extractVariable(body, "ThirdPartyMediaServersX"))
    }

    @Test fun `a doubly escaped property value is recovered`() {
        // Some paths escape the outer document again, so &amp;lt; must resolve to <.
        val body = "&lt;e:propertyset&gt;&lt;e:property&gt;" +
            "&amp;lt;ThirdPartyMediaServersX&amp;gt;2:AAAA&amp;lt;/ThirdPartyMediaServersX&amp;gt;" +
            "&lt;/e:property&gt;&lt;/e:propertyset&gt;"
        assertEquals("2:AAAA", AccountCapture.extractVariable(body, "ThirdPartyMediaServersX"))
    }

    @Test fun `an absent variable is null not blank`() {
        assertNull(AccountCapture.extractVariable("<e:propertyset/>", "ThirdPartyMediaServersX"))
        // Present but empty is also null — an empty announcement is not a credential.
        assertNull(AccountCapture.extractVariable("<ThirdPartyMediaServersX></ThirdPartyMediaServersX>", "ThirdPartyMediaServersX"))
    }

    @Test fun `a header is read case-insensitively`() {
        val head = "HTTP/1.1 200 OK\r\nSID: uuid:abc-123\r\nContent-Length: 0\r\n\r\n"
        assertEquals("uuid:abc-123", AccountCapture.header(head, "sid"))
        assertNull(AccountCapture.header(head, "Nonexistent"))
    }

    @Test fun `an http message is read head plus its content-length body`() {
        val msg = "NOTIFY /notify HTTP/1.1\r\nContent-Length: 5\r\n\r\nHELLO"
        val read = AccountCapture.readHttpMessage(msg.byteInputStream())
        assertEquals(msg, read)
    }

    /**
     * A player that takes the SUBSCRIBE and never connects back — a VPN, a firewall, the emulator's
     * NAT — must cost the timeout and no more. `accept` blocks and a coroutine timeout cannot
     * interrupt it, so this used to hang for good, holding the service lock above it.
     */
    @Test(timeout = 20_000) fun `a player that never calls back gives up at the timeout`() = runBlocking {
        val player = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val answering = thread(isDaemon = true) {
            runCatching {
                while (true) player.accept().use { s ->
                    AccountCapture.readHttpMessage(s.getInputStream())
                    s.getOutputStream().write("HTTP/1.1 200 OK\r\nSID: uuid:test\r\nContent-Length: 0\r\n\r\n".toByteArray())
                }
            }
        }
        val started = System.currentTimeMillis()
        // No coroutine timeout here: it could not interrupt a blocked accept either. The JUnit
        // timeout above is what fails a hang.
        val envelope = AccountCapture.captureEnvelope("127.0.0.1", timeoutMillis = 1_000, playerPort = player.localPort)
        val took = System.currentTimeMillis() - started
        player.close()
        answering.join(2_000)
        assertNull(envelope)
        assertTrue("took ${took}ms", took in 900..5_000)
    }

    /** A stand-in player on loopback that answers every SUBSCRIBE with [reply] and never calls back. */
    private fun silentPlayer(reply: String): java.net.ServerSocket {
        val player = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            runCatching {
                while (true) player.accept().use { s ->
                    AccountCapture.readHttpMessage(s.getInputStream())
                    s.getOutputStream().write(reply.toByteArray())
                }
            }
        }
        return player
    }

    /**
     * A caller that leaves — a search replaced by another — must let go of the listener at once,
     * not when the player's deadline runs out: the capture holds the service lock above it (P-46).
     */
    @Test(timeout = 20_000) fun `a cancelled capture lets go at once rather than at the deadline`() = runBlocking {
        val player = silentPlayer("HTTP/1.1 200 OK\r\nSID: uuid:test\r\nContent-Length: 0\r\n\r\n")
        val capture = launch(kotlinx.coroutines.Dispatchers.Default) {
            AccountCapture.captureEnvelope("127.0.0.1", timeoutMillis = 10_000, playerPort = player.localPort)
        }
        kotlinx.coroutines.delay(500)
        val started = System.currentTimeMillis()
        capture.cancelAndJoin()
        val took = System.currentTimeMillis() - started
        player.close()
        assertTrue("took ${took}ms", took < 3_000)
    }

    @Test(timeout = 20_000) fun `a player that refuses the subscription answers null without waiting`() = runBlocking {
        val player = silentPlayer("HTTP/1.1 412 Precondition Failed\r\nContent-Length: 0\r\n\r\n")
        val started = System.currentTimeMillis()
        val envelope = AccountCapture.captureEnvelope("127.0.0.1", timeoutMillis = 10_000, playerPort = player.localPort)
        val took = System.currentTimeMillis() - started
        player.close()
        assertNull(envelope)
        assertTrue("took ${took}ms", took < 3_000)
    }

    /**
     * A stand-in player that takes the SUBSCRIBE, then calls back with [notify] on a connection it
     * holds open until the test ends; its UNSUBSCRIBE is answered too. [called] is completed once the
     * NOTIFY is sent.
     */
    private fun callingPlayer(notify: (java.io.OutputStream) -> Unit, called: CompletableDeferred<Unit>): java.net.ServerSocket {
        val player = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            runCatching {
                player.accept().use { s ->
                    val request = AccountCapture.readHttpMessage(s.getInputStream())
                    val target = java.net.URI(AccountCapture.header(request, "CALLBACK")!!.removeSurrounding("<", ">"))
                    s.getOutputStream().write("HTTP/1.1 200 OK\r\nSID: uuid:test\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    thread(isDaemon = true) {
                        runCatching { java.net.Socket(target.host, target.port).use { peer -> notify(peer.getOutputStream()); called.complete(Unit); Thread.sleep(15_000) } }
                    }
                }
                while (true) player.accept().use { s ->
                    AccountCapture.readHttpMessage(s.getInputStream())
                    s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
                }
            }
        }
        return player
    }

    /**
     * The whole exchange, as a player makes it: SUBSCRIBE answered with a SID, a NOTIFY to the
     * CALLBACK carrying the variable entity-escaped (as the player sends it, per the test above), a
     * 200 back, the value extracted, and an UNSUBSCRIBE naming the SID. Nothing tested it end to end
     * (P-67). The envelope is a placeholder: the real one is verified against a player, not here.
     */
    @Test(timeout = 20_000) fun `a player that calls back is answered, its variable kept, and let go`() = runBlocking {
        val player = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val notifyAnswer = CompletableDeferred<String>()
        val unsubscribe = CompletableDeferred<String>()
        thread(isDaemon = true) {
            runCatching {
                player.accept().use { s ->
                    val request = AccountCapture.readHttpMessage(s.getInputStream())
                    val target = java.net.URI(AccountCapture.header(request, "CALLBACK")!!.removeSurrounding("<", ">"))
                    s.getOutputStream().write("HTTP/1.1 200 OK\r\nSID: uuid:test-sid\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    val body = "<e:propertyset><e:property>&lt;ThirdPartyMediaServersX&gt;2:AAAA&lt;/ThirdPartyMediaServersX&gt;</e:property></e:propertyset>"
                    java.net.Socket(target.host, target.port).use { peer ->
                        peer.getOutputStream().write(
                            ("NOTIFY ${target.path} HTTP/1.1\r\nNT: upnp:event\r\nSID: uuid:test-sid\r\n" +
                                "Content-Length: ${body.toByteArray().size}\r\n\r\n$body").toByteArray()
                        )
                        notifyAnswer.complete(peer.getInputStream().bufferedReader().readLine().orEmpty())
                    }
                }
                player.accept().use { s ->
                    unsubscribe.complete(AccountCapture.readHttpMessage(s.getInputStream()))
                    s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
                }
            }
        }
        val envelope = AccountCapture.captureEnvelope("127.0.0.1", timeoutMillis = 10_000, playerPort = player.localPort)
        assertEquals("2:AAAA", envelope)
        assertEquals("HTTP/1.1 200 OK", withTimeout(5_000) { notifyAnswer.await() })
        val unsub = withTimeout(5_000) { unsubscribe.await() }
        assertTrue(unsub, unsub.startsWith("UNSUBSCRIBE ") && AccountCapture.header(unsub, "SID") == "uuid:test-sid")
        player.close()
    }

    /**
     * A NOTIFY whose body never comes in full, and a caller that leaves meanwhile. Only the listener
     * was closed on a cancellation, so the read of the accepted connection went on — the service lock
     * held above it — and when it did end it was as a `SocketException`, recorded as the sign-ins
     * being unreadable rather than as the caller leaving (P-75).
     */
    @Test(timeout = 20_000) fun `a cancelled capture lets go of a half-sent notify, as a cancellation`() = runBlocking {
        val called = CompletableDeferred<Unit>()
        val player = callingPlayer({ it.write("NOTIFY /notify HTTP/1.1\r\nContent-Length: 100\r\n\r\nx".toByteArray()) }, called)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val capture = launch(kotlinx.coroutines.Dispatchers.Default) {
            try {
                AccountCapture.captureEnvelope("127.0.0.1", timeoutMillis = 10_000, playerPort = player.localPort)
            } catch (e: Throwable) {
                failure.set(e); throw e
            }
        }
        withTimeout(5_000) { called.await() }
        delay(250)
        capture.cancel()
        // Well inside the 5s a read may take, with the player's connection still open.
        withTimeout(1_500) { capture.join() }
        player.close()
        assertTrue("ended as ${failure.get()}", failure.get() is CancellationException)
    }

    /**
     * A NOTIFY trickled a byte at a time: every read inside the read timeout, so the capture's own
     * deadline was passed by as long as the peer kept going (P-75).
     */
    @Test(timeout = 25_000) fun `a trickled notify does not hold the capture past its bound`() = runBlocking {
        val called = CompletableDeferred<Unit>()
        val player = callingPlayer({ out ->
            out.write("NOTIFY /notify HTTP/1.1\r\nContent-Length: 100000\r\n\r\n".toByteArray())
            runCatching { repeat(60) { out.write('x'.code); out.flush(); Thread.sleep(300) } }
        }, called)
        val started = System.currentTimeMillis()
        val envelope = AccountCapture.captureEnvelope("127.0.0.1", timeoutMillis = 1_000, playerPort = player.localPort)
        val took = System.currentTimeMillis() - started
        player.close()
        assertNull(envelope)
        assertTrue("took ${took}ms", took < 9_000)
    }

    @Test(timeout = 20_000) fun `a message is not read past its bound`() {
        val endless = object : java.io.InputStream() {
            var served = 0L
            override fun read(): Int { served++; return 'a'.code }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                java.util.Arrays.fill(b, off, off + len, 'a'.code.toByte()); served += len; return len
            }
        }
        val message = AccountCapture.readHttpMessage(endless)
        assertTrue("read ${endless.served} bytes", endless.served <= (1 shl 20) + 4096)
        assertTrue(message.isNotEmpty())
    }
}
