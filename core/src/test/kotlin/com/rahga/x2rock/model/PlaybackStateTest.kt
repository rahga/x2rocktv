package com.rahga.x2rock.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStateTest {

    @Test
    fun `only PLAYING counts as playing`() {
        assertTrue(PlaybackStates.PLAYING.isPlaying())
        assertFalse(PlaybackStates.PAUSED.isPlaying())
        assertFalse(PlaybackStates.BUFFERING.isPlaying())
        assertFalse(PlaybackStates.IDLE.isPlaying())
    }

    @Test
    fun `only IDLE has no loaded content`() {
        // This is what decides whether the sidebar poll spends a request on a room.
        assertFalse(PlaybackStates.IDLE.hasLoadedContent())
        assertTrue(PlaybackStates.PAUSED.hasLoadedContent())
        assertTrue(PlaybackStates.PLAYING.hasLoadedContent())
        assertTrue(PlaybackStates.BUFFERING.hasLoadedContent())
    }
}
