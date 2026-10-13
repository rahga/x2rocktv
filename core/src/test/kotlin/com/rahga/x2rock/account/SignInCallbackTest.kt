package com.rahga.x2rock.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI

/** The TV's listener, as a phone's browser meets it: other requests turned away, the right one redeemed. */
class SignInCallbackTest {

    private fun get(port: Int, path: String): Pair<Int, String> {
        val c = URI("http://127.0.0.1:$port$path").toURL().openConnection() as java.net.HttpURLConnection
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream).bufferedReader().readText()
        return code to body
    }

    @Test fun `only the redirect carrying this sign-in's state is redeemed`() = runBlocking {
        val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        val waiting = async(Dispatchers.Default) { SignInCallback.awaitCode(server, "s-1") }

        assertEquals(404, get(server.localPort, "/favicon.ico").first)
        assertEquals("another sign-in's state", 400, get(server.localPort, "/callback?code=x&state=s-0").first)
        val (status, page) = get(server.localPort, "/callback?code=the%2Bcode&state=s-1")

        assertEquals(200, status)
        assertTrue(page, "Signed in" in page)
        assertEquals("the+code", withTimeout(5_000) { waiting.await() })
        assertTrue("the listener closes with the sign-in", server.isClosed || runCatching { server.close() }.isSuccess)
    }

    @Test fun `an error from Sonos ends the wait in its words`() = runBlocking {
        val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        val waiting = async(Dispatchers.Default) { runCatching { SignInCallback.awaitCode(server, "s-1") } }
        get(server.localPort, "/callback?error=access_denied&error_description=Declined&state=s-1")
        val failed = withTimeout(5_000) { waiting.await() }.exceptionOrNull()
        assertTrue(failed is SignInException)
        assertTrue(failed!!.message!!, "access_denied: Declined" in failed.message!!)
    }

    @Test fun `cancelling the wait closes the listener`() = runBlocking {
        val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        val waiting = async(Dispatchers.Default) { SignInCallback.awaitCode(server, "s-1") }
        kotlinx.coroutines.delay(200)
        waiting.cancel()
        runCatching { waiting.await() }
        assertTrue(server.isClosed)
    }
}
