package com.rahga.x2rock.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

/** A volume press's size: 2, 3, then 5 within a burst; at most 2 below 10 and 1 below 4. */
class VolumeBurstTest {
    private var now = 0L
    private val burst = VolumeBurst { now }

    /** Presses from [from] in [direction], each [gapMillis] after the last; the levels they reach. */
    private fun presses(from: Int, direction: Int, count: Int, gapMillis: Long = 200): List<Int> {
        var level = from
        return List(count) {
            now += gapMillis
            level += burst.step(level, direction)
            level
        }
    }

    @Test fun `a burst climbs 2, 3, then 5 and stays at 5`() {
        assertEquals(listOf(22, 25, 30, 35, 40), presses(20, +1, 5))
    }

    @Test fun `a pause starts the next burst at 2`() {
        presses(20, +1, 3)
        assertEquals(listOf(32), presses(30, +1, 1, gapMillis = 2_000))
    }

    @Test fun `a turn the other way starts the next burst at 2`() {
        presses(20, +1, 3)
        assertEquals(listOf(28), presses(30, -1, 1))
    }

    /** Fine at the bottom, whatever the burst has reached: 1s below 4, 2s below 10, then the climb. */
    @Test fun `quiet levels step finely going up`() {
        assertEquals(listOf(1, 2, 3, 4, 6, 8, 10, 15), presses(0, +1, 8))
    }

    /** Judged by where the level is going: down from 10 is already in the fine range. */
    @Test fun `quiet levels step finely going down`() {
        assertEquals(listOf(28, 25, 20, 15, 10, 8, 6, 4, 3, 2), presses(30, -1, 10))
    }

    /** No cap at the top: 98 and 100 sound the same, so a press there is as big as anywhere. */
    @Test fun `loud levels are not capped`() {
        assertEquals(listOf(92, 95, 100), presses(90, +1, 3))
    }
}
