package com.rahga.x2rock.lan

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A reply read whole: its status, and its body as text. */
internal class HttpReply(val code: Int, val body: String) {
    val isSuccessful: Boolean get() = code in 200..299
}

/**
 * The call, answered and read — and cancelled with the coroutine that waits for it, **body
 * included**. `execute()` blocks and a cancelled caller could not stop it: every request a search
 * had out ran to its end, holding a thread each, after the viewer had already searched for
 * something else; UPnP's calls were the same until an outside review noticed (2026-10-08).
 *
 * The body is read on OkHttp's own thread, inside the wait. A first version resumed on the headers
 * and left the caller to read the body, by when the cancellation hook had nothing to cancel: a
 * player that sent its headers and stalled mid-body held a cancelled caller's thread to the read
 * timeout (a second outside review, the same day). Cancelling now closes the call, and the read
 * on OkHttp's thread fails rather than waiting.
 */
internal suspend fun Call.awaitReply(): HttpReply = suspendCancellableCoroutine { waiting ->
    waiting.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            val read = runCatching { response.use { HttpReply(it.code, it.body?.string().orEmpty()) } }
            read.fold(
                onSuccess = { waiting.resume(it) },
                onFailure = { if (!waiting.isCancelled) waiting.resumeWithException(it) },
            )
        }
        override fun onFailure(call: Call, e: IOException) {
            if (!waiting.isCancelled) waiting.resumeWithException(e)
        }
    })
}
