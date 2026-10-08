package com.rahga.x2rock.lan

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Cancellation after the headers: the player sends a status and part of a body, then nothing. The
 * first cancellable call resumed on the headers and left the body to be read uncancellably, so a
 * cancelled caller held its thread to the read timeout (outside review, 2026-10-08). `UpnpCancelTest`
 * covers the case before the headers.
 */
class CallBodyCancelTest {
    @Test(timeout = 15_000) fun `a cancelled call hangs up while its body is still arriving`() = runBlocking {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val peer = AtomicReference<Socket>()
        val readingBody = CountDownLatch(1)
        val hungUp = CountDownLatch(1)
        thread(isDaemon = true) {
            try {
                server.accept().use { socket ->
                    peer.set(socket)
                    val input = socket.getInputStream()
                    val headers = StringBuilder()
                    while (!headers.endsWith("\r\n\r\n")) {
                        val next = input.read()
                        check(next >= 0)
                        headers.append(next.toChar())
                    }
                    val length = Regex("(?i)Content-Length: (\\d+)").find(headers)?.groupValues?.get(1)?.toInt() ?: 0
                    repeat(length) { check(input.read() >= 0) }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 2000\r\n\r\n<".toByteArray())
                        flush()
                    }
                    while (input.read() >= 0) Unit
                    hungUp.countDown()
                }
            } catch (_: Exception) { /* The test closes the socket during cleanup. */ }
        }
        val book = PlayerAddressBook().apply { register("sonos-silent.local", "127.0.0.1") }
        val client = LanHttp.client(book).newBuilder().eventListener(object : EventListener() {
            override fun responseBodyStart(call: Call) { readingBody.countDown() }
        }).build()
        val upnp = Upnp(client, server.localPort)
        val call = async(Dispatchers.Default) { runCatching { upnp.mediaInfo("sonos-silent.local") } }
        try {
            assertTrue("the real response body reader was never entered", readingBody.await(5, TimeUnit.SECONDS))
            call.cancel()
            assertTrue("cancellation after headers left body.string() blocked on the socket",
                hungUp.await(1, TimeUnit.SECONDS))
        } finally {
            peer.get()?.close()
            server.close()
            call.cancel()
            call.join()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
