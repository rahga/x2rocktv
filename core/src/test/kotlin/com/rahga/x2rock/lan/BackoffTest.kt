package com.rahga.x2rock.lan

import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffTest {

    @Test
    fun `the first wait is a second, not zero`() {
        assertEquals(MIN_BACKOFF_MILLIS, nextBackoff(0))
    }

    @Test
    fun `it doubles`() {
        assertEquals(2_000L, nextBackoff(1_000))
        assertEquals(4_000L, nextBackoff(2_000))
        assertEquals(8_000L, nextBackoff(4_000))
    }

    /** A speaker that is off for the evening must not be retried once an hour. */
    @Test
    fun `it stops at a minute`() {
        assertEquals(MAX_BACKOFF_MILLIS, nextBackoff(32_000))
        assertEquals(MAX_BACKOFF_MILLIS, nextBackoff(MAX_BACKOFF_MILLIS))
        assertEquals(MAX_BACKOFF_MILLIS, nextBackoff(Long.MAX_VALUE / 4))
    }

    @Test
    fun `the whole curve reaches the ceiling in six steps and stays`() {
        val steps = generateSequence(0L) { nextBackoff(it) }.drop(1).take(8).toList()
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L),
            steps,
        )
    }
}
