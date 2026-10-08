package com.rahga.x2rock.lan

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A UPnP call whose caller has gone hangs up rather than waiting out its timeout on a thread of its
 * own — a queue read outliving the screen that asked for it. With `execute()` it could not be
 * stopped (outside review, 2026-10-08). The same check as `SmapiCancelTest`: the player takes the
 * request and never answers, and only a cancelled call closes the connection.
 */
class UpnpCancelTest {

    @Test(timeout = 20_000) fun `a cancelled call hangs up on a player that never answers`() = runBlocking {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val requested = CountDownLatch(1)
        val hungUp = CountDownLatch(1)
        thread(isDaemon = true) {
            server.accept().use { socket ->
                val input = socket.getInputStream()
                if (input.read() >= 0) requested.countDown()
                while (input.read() >= 0) Unit
                hungUp.countDown()
            }
        }
        val book = PlayerAddressBook().apply { register("sonos-silent.local", "127.0.0.1") }
        val upnp = Upnp(LanHttp.client(book), server.localPort)

        val call = async(Dispatchers.Default) { runCatching { upnp.mediaInfo("sonos-silent.local") } }
        assertTrue("the request never arrived", requested.await(10, TimeUnit.SECONDS))
        delay(100)
        call.cancel()
        assertTrue("the call kept waiting after its caller was cancelled", hungUp.await(2, TimeUnit.SECONDS))
        call.join()
        server.close()
    }
}
