package com.rahga.x2rock.smapi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A search replaced by a newer one abandons its requests. With `execute()` they could not be
 * stopped: each ran to its end on a thread of its own after the viewer had moved on, and held the
 * search's request permits while it did.
 *
 * The service here takes the request and never answers. What is checked is the connection itself:
 * the client hangs up once the search is cancelled, which only a cancelled call does.
 */
class SmapiCancelTest {

    @Test(timeout = 20_000) fun `a cancelled search hangs up on a service that never answers`() = runBlocking {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val requested = CountDownLatch(1)
        val hungUp = CountDownLatch(1)
        thread(isDaemon = true) {
            server.accept().use { socket ->
                val input = socket.getInputStream()
                // The request arrives, then nothing is sent back; a read of -1 is the client leaving.
                if (input.read() >= 0) requested.countDown()
                while (input.read() >= 0) Unit
                hungUp.countDown()
            }
        }
        val client = SmapiClient(OkHttpClient())
        val service = Service(id = "1", name = "Silent", uri = "http://127.0.0.1:${server.localPort}/smapi", auth = Auth.ANONYMOUS, manifestUri = null)

        val search = async(Dispatchers.Default) { runCatching { client.search(service, null, "tracks", "jazz") } }
        assertTrue("the request never arrived", requested.await(10, TimeUnit.SECONDS))
        delay(100)
        search.cancel()
        assertTrue("the call kept waiting after its search was cancelled", hungUp.await(2, TimeUnit.SECONDS))
        search.join()
        server.close()
    }
}
