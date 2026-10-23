package com.rahga.x2rock.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mDNS resolve's bound, against a stand-in for the platform's resolver. A resolve that never
 * called back held the lock for good, and every later find, and so every connect, waited on it (P-74).
 */
class OneResolveAtATimeTest {

    @Test(timeout = 10_000) fun `a resolve that never answers gives up, asks it stopped, and lets the next one run`() = runBlocking {
        val resolver = OneResolveAtATime(timeoutMillis = 300)
        var stopped = 0
        val started = System.currentTimeMillis()
        val silent = resolver.resolve<String>(start = { /* the platform never calls back */ }, stop = { stopped++ })
        assertNull(silent)
        assertTrue("took ${System.currentTimeMillis() - started}ms", System.currentTimeMillis() - started < 2_000)
        assertEquals("the silent resolve was not asked to stop", 1, stopped)

        val next = resolver.resolve<String>(start = { answer -> answer("Kitchen") }, stop = { stopped++ })
        assertEquals("Kitchen", next)
        assertEquals("an answered resolve is not stopped", 1, stopped)
    }

    @Test(timeout = 10_000) fun `a failed resolve answers null at once`() = runBlocking {
        val resolver = OneResolveAtATime(timeoutMillis = 5_000)
        val started = System.currentTimeMillis()
        assertNull(resolver.resolve<String>(start = { answer -> answer(null) }, stop = {}))
        assertTrue(System.currentTimeMillis() - started < 1_000)
    }
}
