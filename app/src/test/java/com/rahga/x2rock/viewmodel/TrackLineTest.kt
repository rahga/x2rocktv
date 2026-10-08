package com.rahga.x2rock.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

/** [trackLine]: the line under a track's title in the pane. */
class TrackLineTest {
    /** Sonos Radio's Hit List, as the pane had it on 2026-10-08: a track, an artist, the station. */
    @Test fun `a station's track names the station after its artist`() {
        val state = PlayerUiState(trackName = "Save Your Tears (Remix)", artistName = "The Weeknd", sourceName = "Hit List", isRadio = true)
        assertEquals("The Weeknd • Hit List", state.trackLine())
    }

    /** A queue's container is the album or playlist it came from, which the album already says or need not. */
    @Test fun `a track off the queue is its artist and album alone`() {
        val state = PlayerUiState(trackName = "FE!N", artistName = "Travis Scott", albumName = "UTOPIA", sourceName = "Favorite tracks")
        assertEquals("Travis Scott • UTOPIA", state.trackLine())
    }

    @Test fun `a station named as its album is not said twice`() {
        val state = PlayerUiState(trackName = "x", artistName = "A", albumName = "Hit List", sourceName = "Hit List", isRadio = true)
        assertEquals("A • Hit List", state.trackLine())
    }
}
