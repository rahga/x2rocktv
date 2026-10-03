package com.rahga.x2rock.lan

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** The `HH:MM:SS` the player takes, from a device that may count in other digits. */
class ClockFormatTest {

    @Test fun `the clock is in ASCII digits whatever the device's locale`() {
        val before = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        try {
            assertEquals("00:30:00", formatClock(30 * 60_000L))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test fun `the clock rounds down to the second and never goes negative`() {
        assertEquals("01:02:03", formatClock(3_723_999L))
        assertEquals("00:00:00", formatClock(-5_000L))
    }
}
