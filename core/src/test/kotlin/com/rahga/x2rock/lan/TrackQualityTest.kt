package com.rahga.x2rock.lan

import com.google.gson.Gson
import com.rahga.x2rock.model.PlaybackMetadata
import com.rahga.x2rock.model.TrackQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A track's `quality` (issue #2), from the three captures that carry one: Plex's empty object, an
 * iHeartRadio station's lossy stream, and Apple Music's lossless track.
 */
class TrackQualityTest {

    private fun quality(fixture: String) =
        Gson().fromJson(FakePlayer.fixture("event.$fixture.json"), PlaybackMetadata::class.java).currentItem?.track?.quality

    @Test fun `each captured quality is said as the player gave it`() {
        assertEquals(TrackQuality(bitDepth = 16, sampleRate = 44100, lossless = true, immersive = false), quality("losslessMetadataStatus"))
        assertEquals("Lossless · 16-bit · 44.1 kHz", quality("losslessMetadataStatus")?.summary())
        assertEquals("a lossy stream's bit depth is not named", "Compressed · 24 kHz", quality("stationMetadataStatus")?.summary())
        assertNull("Plex sends the object with nothing in it", quality("metadataStatus")?.summary())
    }

    /** Not captured: what a hi-res or immersive track would read, from the same rules. */
    @Test fun `a rate in kHz keeps what it has and drops what it lacks`() {
        assertEquals("Lossless · 24-bit · 96 kHz", TrackQuality(24, 96000, lossless = true).summary())
        assertEquals("Lossless · 24-bit · 88.2 kHz", TrackQuality(24, 88200, lossless = true).summary())
        assertEquals("Immersive · Lossless · 24-bit · 48 kHz", TrackQuality(24, 48000, lossless = true, immersive = true).summary())
        assertEquals("Immersive", TrackQuality(immersive = true).summary())
        assertNull(TrackQuality(bitDepth = 16, sampleRate = 44100).summary())
    }

    @Test fun `a room's track carries its quality`() = runBlocking<Unit> {
        val fake = FakePlayer().also { it.start() }
        val scope = CoroutineScope(SupervisorJob())
        val household = fakeHousehold(fake, scope)
        try {
            household.connectTo(fake)
            val groupId = household.state.value.groups.first().id
            fake.pushFixture("losslessMetadataStatus", groupId)
            val state = withTimeout(5_000) { household.groupStates.first { it[groupId]?.track?.quality != null } }
            assertEquals(true, state[groupId]?.track?.quality?.lossless)
        } finally {
            household.disconnect()
            scope.cancel()
            fake.shutdown()
        }
    }
}
