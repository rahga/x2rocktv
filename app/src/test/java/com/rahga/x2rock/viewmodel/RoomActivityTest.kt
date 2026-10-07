package com.rahga.x2rock.viewmodel

import com.rahga.x2rock.model.PlaybackStates
import org.junit.Assert.assertEquals
import org.junit.Test

/** The one rule for what a room is doing, which the list, the pane and the panel share. */
class RoomActivityTest {

    @Test fun `a paused room is paused, whatever it has loaded`() {
        assertEquals(RoomActivity.PAUSED, roomActivity(PlaybackStates.PAUSED, hasSource = true))
    }

    @Test fun `a stream that could only stop is stopped, not idle`() {
        // Pausing a live stream leaves the room IDLE with its station still named: Kitchen on
        // Sonos Radio's Smooth Jazz, 2026-10-06, read "Idle" in the panel and nothing in the list.
        assertEquals(RoomActivity.STOPPED, roomActivity(PlaybackStates.IDLE, hasSource = true))
    }

    @Test fun `a room with nothing loaded says so`() {
        assertEquals(RoomActivity.EMPTY, roomActivity(PlaybackStates.IDLE, hasSource = false))
        assertEquals(RoomActivity.EMPTY, roomActivity(null, hasSource = false))
    }

    @Test fun `buffering counts as playing`() {
        assertEquals(RoomActivity.PLAYING, roomActivity(PlaybackStates.BUFFERING, hasSource = true))
    }

    @Test fun `a station with no track still counts as loaded`() {
        assertEquals(true, HomeViewModel.RoomInfo(isRadio = true).hasSource)
        assertEquals(true, HomeViewModel.RoomInfo(source = "Smooth Jazz").hasSource)
        assertEquals(false, HomeViewModel.RoomInfo().hasSource)
    }
}
