package com.rahga.x2rock.lan

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.Socket

/**
 * Runs [block], which reads or writes this socket and blocks doing it, for at most [millis] in all,
 * and only while the caller still wants it: the socket is closed when the time is up or the caller
 * is cancelled, whichever comes first, and on the way out.
 *
 * A blocking read hears nothing of a cancellation and a socket's own timeout is per read, so a peer
 * that sent part of a message, or a byte now and then, kept a cancelled sign-in's or account
 * capture's thread reading — and the capture's service lock with it — for as long as it liked
 * (review, 2026-10-23, P-75 and P-76). Closing is the one thing that ends the read. What the close
 * then throws is reported as the cancellation it was, not as a failure of the socket: a capture's
 * cancellation surfaced as a `SocketException` and was recorded as an account read that failed.
 */
internal suspend fun <T> Socket.within(millis: Long, block: (Socket) -> T): T = coroutineScope {
    val socket = this@within
    val closer = launch(start = CoroutineStart.UNDISPATCHED) {
        try { delay(millis) } finally { runCatching { socket.close() } }
    }
    try {
        block(socket)
    } catch (e: IOException) {
        ensureActive()
        throw e
    } finally {
        closer.cancel()
    }
}
