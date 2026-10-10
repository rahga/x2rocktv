package com.rahga.x2rock.ui.screens

import com.rahga.x2rock.model.PlaybackStates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [livePosition]: where a bar composed after the last report starts. */
class LivePositionTest {
    @Test fun `a playing track has moved on since it was reported`() {
        val now = livePosition(60_000, System.currentTimeMillis() - 5_000, PlaybackStates.PLAYING, 300_000)
        assertTrue("expected about 65s, got $now", now in 64_500..66_000)
    }

    @Test fun `a paused track is where it was reported`() {
        assertEquals(60_000, livePosition(60_000, System.currentTimeMillis() - 5_000, PlaybackStates.PAUSED, 300_000))
    }

    @Test fun `never past the end`() {
        assertEquals(300_000, livePosition(299_000, System.currentTimeMillis() - 5_000, PlaybackStates.PLAYING, 300_000))
    }
}
