package com.rahga.x2rock.cli.mpris

import com.rahga.x2rock.model.PlaybackStates
import com.rahga.x2rock.model.RepeatModes
import com.rahga.x2rock.model.Track
import com.rahga.x2rock.model.TrackAlbum
import com.rahga.x2rock.model.TrackArtist
import org.freedesktop.dbus.DBusPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MprisMappingTest {

    private val track = Track("Song", TrackArtist("Band"), TrackAlbum("LP"), "https://img/x.jpg", durationMillis = 240_000)

    private fun snap(
        state: String = PlaybackStates.PLAYING,
        track: Track? = this.track,
        position: Long = 60_000,
        volume: Int = 40,
        muted: Boolean = false,
        capturedAt: Long = 1_000_000
    ) = PlayerSnapshot("Kitchen", state, track, position, volume, muted, false, RepeatModes.NONE, capturedAt)

    @Test
    fun `playback status collapses Sonos states onto the three MPRIS values`() {
        assertEquals("Playing", mprisPlaybackStatus(PlaybackStates.PLAYING))
        assertEquals("Playing", mprisPlaybackStatus(PlaybackStates.BUFFERING))
        assertEquals("Paused", mprisPlaybackStatus(PlaybackStates.PAUSED))
        assertEquals("Stopped", mprisPlaybackStatus(PlaybackStates.IDLE))
        assertEquals("Stopped", mprisPlaybackStatus("PLAYBACK_STATE_SOMETHING_NEW"))
    }

    @Test
    fun `loop status round-trips`() {
        for (r in listOf(RepeatModes.NONE, RepeatModes.ALL, RepeatModes.ONE)) {
            assertEquals(r, sonosRepeat(mprisLoopStatus(r)))
        }
    }

    @Test
    fun `position advances while playing and freezes while paused`() {
        val playing = snap(position = 60_000, capturedAt = 1_000_000)
        assertEquals(63_000_000L, mprisPositionMicros(playing, nowMillis = 1_003_000))

        val paused = snap(state = PlaybackStates.PAUSED, position = 60_000, capturedAt = 1_000_000)
        assertEquals(60_000_000L, mprisPositionMicros(paused, nowMillis = 1_003_000))
    }

    @Test
    fun `position never overshoots the track or runs backwards`() {
        val nearEnd = snap(position = 239_000, capturedAt = 1_000_000)
        assertEquals(240_000_000L, mprisPositionMicros(nearEnd, nowMillis = 1_010_000))
        // A clock that appears to go backwards (NTP step) must not produce a negative elapsed.
        assertEquals(239_000_000L, mprisPositionMicros(nearEnd, nowMillis = 999_000))
    }

    @Test
    fun `metadata carries the xesam keys with the right D-Bus types`() {
        val m = mprisMetadata(track)
        assertEquals("Song", m.getValue("xesam:title").value)
        assertEquals(listOf("Band"), m.getValue("xesam:artist").value)
        assertEquals("as", m.getValue("xesam:artist").sig)
        assertEquals("LP", m.getValue("xesam:album").value)
        assertEquals("https://img/x.jpg", m.getValue("mpris:artUrl").value)
        assertEquals(240_000_000L, m.getValue("mpris:length").value)
        assertTrue(m.getValue("mpris:trackid").value is DBusPath)
    }

    @Test
    fun `metadata for no track is just the NoTrack id`() {
        val m = mprisMetadata(null)
        assertEquals(setOf("mpris:trackid"), m.keys)
        assertEquals("/org/mpris/MediaPlayer2/TrackList/NoTrack", (m.getValue("mpris:trackid").value as DBusPath).path)
    }

    @Test
    fun `blank artist and zero duration are omitted rather than sent as empty`() {
        val m = mprisMetadata(Track("Radio", TrackArtist(""), null, null, durationMillis = 0))
        assertNull(m["xesam:artist"])
        assertNull(m["mpris:length"])
        assertNull(m["xesam:album"])
    }

    @Test
    fun `track id is stable for the same track and differs between tracks`() {
        assertEquals(mprisTrackId(track), mprisTrackId(track.copy(imageUrl = "other")))
        assertNotEquals(mprisTrackId(track), mprisTrackId(track.copy(name = "Other Song")))
        assertTrue(mprisTrackId(track).path.startsWith("/com/rahga/x2rock/track/"))
    }

    @Test
    fun `volume scales both ways and mute reads as zero`() {
        assertEquals(0.4, mprisVolume(snap(volume = 40)), 1e-9)
        assertEquals(0.0, mprisVolume(snap(volume = 40, muted = true)), 1e-9)
        assertEquals(40, sonosVolume(0.4))
        assertEquals(100, sonosVolume(1.7))
        assertEquals(0, sonosVolume(-0.2))
        assertEquals(33, sonosVolume(0.333))
    }

    @Test
    fun `capabilities follow whether there is a track`() {
        val with = playerProperties(snap())
        assertEquals(true, with.getValue("CanPlay").value)
        assertEquals(true, with.getValue("CanSeek").value)

        val without = playerProperties(snap(track = null, state = PlaybackStates.IDLE))
        assertEquals(false, without.getValue("CanPlay").value)
        assertEquals(false, without.getValue("CanGoNext").value)
        assertEquals(false, without.getValue("CanSeek").value)
        assertEquals(true, without.getValue("CanControl").value)

        val radio = playerProperties(snap(track = track.copy(durationMillis = 0)))
        assertEquals(false, radio.getValue("CanSeek").value)
    }

    @Test
    fun `changed properties is the whole set on first publish and only the diff afterwards`() {
        val first = snap()
        assertEquals(playerProperties(first).keys, changedPlayerProperties(null, first).keys)

        val paused = first.copy(playbackState = PlaybackStates.PAUSED)
        assertEquals(setOf("PlaybackStatus"), changedPlayerProperties(first, paused).keys)

        val louder = first.copy(volume = 55)
        assertEquals(setOf("Volume"), changedPlayerProperties(first, louder).keys)

        // Position moves every poll and is deliberately not a signalled property.
        val later = first.copy(positionMillis = 65_000, capturedAtMillis = 1_005_000)
        assertTrue(changedPlayerProperties(first, later).isEmpty())
    }

    @Test
    fun `a track change flips Metadata and nothing spurious`() {
        val a = snap()
        val b = a.copy(track = track.copy(name = "Next Song"))
        val changed = changedPlayerProperties(a, b)
        assertEquals(setOf("Metadata"), changed.keys)
        assertFalse(changed.containsKey("PlaybackStatus"))
    }
}
