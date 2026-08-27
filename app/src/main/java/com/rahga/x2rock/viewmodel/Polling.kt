package com.rahga.x2rock.viewmodel

import com.rahga.x2rock.network.RateLimitedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Ceiling for the failure backoff — a poller never sleeps longer than this. */
const val MAX_POLL_BACKOFF_MILLIS = 60_000L

/**
 * Runs [fetch] every [intervalMillis] until cancelled. Each consecutive failure doubles the wait
 * up to [MAX_POLL_BACKOFF_MILLIS]; a success resets it. A 429 waits exactly as long as the server
 * asked rather than guessing.
 */
suspend fun pollLoop(
    intervalMillis: Long,
    onError: (Throwable) -> Unit = {},
    fetch: suspend () -> Unit
) {
    var wait = intervalMillis
    while (currentCoroutineContext().isActive) {
        wait = try {
            fetch()
            intervalMillis
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onError(e)
            nextBackoffMillis(wait, intervalMillis, e)
        }
        delay(wait)
    }
}

/** Extracted so the backoff curve is testable without a live poller. */
fun nextBackoffMillis(currentWait: Long, intervalMillis: Long, error: Throwable?): Long = when {
    error == null -> intervalMillis
    error is RateLimitedException && error.retryAfterMillis != null -> error.retryAfterMillis
    else -> (currentWait * 2).coerceAtMost(MAX_POLL_BACKOFF_MILLIS)
}
