package com.rahga.x2rock.store

import com.rahga.x2rock.viewmodel.FakePreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AfterPlayingTest {
    @Test fun `back to the room by default, and staying is kept per device`() {
        val prefs = FakePreferences()
        assertFalse(AfterPlaying(prefs).stay.value)
        AfterPlaying(prefs).setStay(true)
        assertTrue("read back on the next launch", AfterPlaying(prefs).stay.value)
        AfterPlaying(prefs).setStay(false)
        assertFalse(AfterPlaying(prefs).stay.value)
    }
}
