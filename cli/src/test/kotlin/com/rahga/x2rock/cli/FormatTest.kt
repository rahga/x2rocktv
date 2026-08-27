package com.rahga.x2rock.cli

import com.rahga.x2rock.model.Track
import com.rahga.x2rock.model.TrackArtist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatTest {

    @Test
    fun `volume accepts absolute and relative forms and clamps`() {
        assertEquals(40, parseVolume("40", current = 10))
        assertEquals(15, parseVolume("+5", current = 10))
        assertEquals(5, parseVolume("-5", current = 10))
        assertEquals(100, parseVolume("+50", current = 80))
        assertEquals(0, parseVolume("-50", current = 10))
        assertEquals(100, parseVolume("250", current = 10))
        assertNull(parseVolume("loud", current = 10))
        assertNull(parseVolume("", current = 10))
    }

    @Test
    fun `clock rolls into hours`() {
        assertEquals("0:07", 7_000L.toClock())
        assertEquals("59:59", 3_599_000L.toClock())
        assertEquals("1:00:00", 3_600_000L.toClock())
        assertEquals("0:00", (-1L).toClock())
    }

    @Test
    fun `one-line track omits whichever half is missing`() {
        assertEquals("Song — Band", Track("Song", TrackArtist("Band"), null, null).oneLine())
        assertEquals("Song", Track("Song", TrackArtist(""), null, null).oneLine())
        assertEquals("Song", Track("Song", null, null, null).oneLine())
        assertNull(Track(null, TrackArtist("Band"), null, null).oneLine())
        assertNull((null as Track?).oneLine())
    }
}
