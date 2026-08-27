package com.rahga.x2rock.viewmodel

import com.rahga.x2rock.network.RateLimitedException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class PollingBackoffTest {

    private val interval = 5_000L

    @Test
    fun `success returns to the base interval`() {
        assertEquals(interval, nextBackoffMillis(currentWait = 40_000L, intervalMillis = interval, error = null))
    }

    @Test
    fun `consecutive failures double up to the ceiling`() {
        var wait = interval
        val seen = mutableListOf<Long>()
        repeat(6) {
            wait = nextBackoffMillis(wait, interval, IOException("down"))
            seen += wait
        }
        assertEquals(listOf(10_000L, 20_000L, 40_000L, 60_000L, 60_000L, 60_000L), seen)
    }

    @Test
    fun `a rate limit waits exactly as long as the server asked`() {
        assertEquals(
            90_000L,
            nextBackoffMillis(interval, interval, RateLimitedException(retryAfterMillis = 90_000L))
        )
    }

    @Test
    fun `a rate limit with no Retry-After falls back to doubling`() {
        assertEquals(
            2 * interval,
            nextBackoffMillis(interval, interval, RateLimitedException(retryAfterMillis = null))
        )
    }
}
