package com.rahga.x2rock.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatTest {

    @Test
    fun `pads seconds`() {
        assertEquals("0:00", 0L.toTimeString())
        assertEquals("0:07", 7_000L.toTimeString())
        assertEquals("3:45", 225_000L.toTimeString())
    }

    @Test
    fun `rolls over into hours instead of counting past sixty minutes`() {
        assertEquals("59:59", 3_599_000L.toTimeString())
        assertEquals("1:00:00", 3_600_000L.toTimeString())
        assertEquals("1:20:00", 4_800_000L.toTimeString())
    }

    @Test
    fun `a negative position clamps rather than printing a negative clock`() {
        assertEquals("0:00", (-5_000L).toTimeString())
    }
}
