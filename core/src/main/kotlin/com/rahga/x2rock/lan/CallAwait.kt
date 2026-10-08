package com.rahga.x2rock.lan

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

/**
 * The call, answered — and cancelled with the coroutine that waits for it. `execute()` blocks and a
 * cancelled caller could not stop it: every request a search had out ran to its end, holding a
 * thread each, after the viewer had already searched for something else. UPnP's calls were the same
 * until an outside review noticed (2026-10-08): a queue read outlived the screen that asked for it,
 * and Apple's search and the radio directory's after it.
 */
// `resume` with an onCancellation handler, which closes a response that arrives after the caller left.
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { waiting ->
    waiting.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = waiting.resume(response) { response.close() }
        override fun onFailure(call: Call, e: IOException) {
            if (!waiting.isCancelled) waiting.resumeWithException(e)
        }
    })
}
