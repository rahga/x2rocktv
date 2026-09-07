package com.rahga.x2rock.lan

import com.google.gson.Gson
import com.rahga.x2rock.model.HomeTheaterFormat
import com.rahga.x2rock.model.PlaybackMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a soundbar reports about its HDMI input.
 *
 * The captured case is a real Beam taking audio from a Shield with nothing playing, which
 * is exactly the state the widget renders as "Silence 2.0".
 */
class HomeTheaterFormatTest {

    private val gson = Gson()

    private fun parse(json: String) =
        gson.fromJson(json, PlaybackMetadata::class.java)

    @Test fun `the captured TV container parses, format and all`() {
        val meta = gson.fromJson(
            FakePlayer.fixture("event.tvMetadataStatus.json"),
            PlaybackMetadata::class.java,
        )
        val container = assertNotNull(meta.container).let { meta.container!! }
        assertEquals("TV Audio", container.name)
        assertEquals("linein.homeTheater.hdmi", container.type)
        val format = container.htInputFormat!!
        // The exact string the widget shows for this room.
        assertEquals("Silence 2.0", format.summary())
        assertFalse(format.isSurround)
    }

    /**
     * A real 5.1 stream, captured from a Beam with a Sub and two surrounds while a film
     * played on the Shield over HDMI. The stereo capture above cannot exercise any of
     * this: no LFE channel, nothing to call surround, and a codec name that happens to
     * end in a word ("Silence") rather than one that already says "Surround".
     */
    @Test fun `the captured 5 point 1 stream reports its codec and layout`() {
        val meta = gson.fromJson(
            FakePlayer.fixture("event.tvSurroundMetadataStatus.json"),
            PlaybackMetadata::class.java,
        )
        val format = meta.container!!.htInputFormat!!
        assertEquals(5, format.numGroundChannels)
        assertEquals(1, format.numLfeChannels)
        assertEquals("5.1", format.channels)
        assertTrue(format.isSurround)
        assertFalse(format.isSilent)
        // Exactly what the Rust CLI prints for the same stream.
        assertEquals("Dolby Digital Surround 5.1", format.summary())
    }

    /** Still the TV input, and still reported as such, whatever is arriving over it. */
    @Test fun `a surround stream is on the TV input like any other`() {
        val meta = gson.fromJson(
            FakePlayer.fixture("event.tvSurroundMetadataStatus.json"),
            PlaybackMetadata::class.java,
        )
        val state = GroupState(container = meta.container)
        assertTrue(state.onTvInput)
        assertEquals("Dolby Digital Surround 5.1", state.inputFormat)
        assertEquals("TV Audio", meta.container!!.name)
        assertEquals("linein.homeTheater.hdmi", meta.container!!.type)
    }

    /**
     * `numLFEChannels` is not the casing a camelCase convention derives from the field
     * name, so it is mapped explicitly. Getting it wrong silently reports 5.0 for 5.1 —
     * and the captured stream above is the one that would catch it.
     */
    @Test fun `the oddly-cased LFE field is read`() {
        val format = gson.fromJson(
            """{"numGroundChannels":5,"numLFEChannels":1,"streamDescription":"Dolby Digital"}""",
            HomeTheaterFormat::class.java,
        )
        assertEquals(1, format.numLfeChannels)
        assertEquals("Dolby Digital 5.1", format.summary())
        assertTrue(format.isSurround)
    }

    @Test fun `height channels appear as a third number`() {
        val format = HomeTheaterFormat(5, 1, 2, "Dolby Atmos")
        assertEquals("5.1.2", format.channels)
        assertEquals("Dolby Atmos 5.1.2", format.summary())
    }

    /** With the television off there are no channels, and "No Signal 0.0" reads badly. */
    @Test fun `a silent input names only its codec`() {
        assertEquals("No Signal", HomeTheaterFormat(0, 0, 0, "No Signal").summary())
        assertEquals("", HomeTheaterFormat(0, 0, 0, null).summary())
    }

    @Test fun `a format with no codec still reports its layout`() {
        assertEquals("5.1", HomeTheaterFormat(5, 1, 0, null).summary())
    }

    /** Presence is the signal, not the wording: a silent input is still the TV input. */
    @Test fun `on-TV-input is presence, not content`() {
        val silent = parse("""{"container":{"htInputFormat":{"numGroundChannels":0}}}""")
        assertTrue(GroupState(container = silent.container).onTvInput)

        val music = parse("""{"container":{"name":"An Album"}}""")
        assertFalse(GroupState(container = music.container).onTvInput)
    }
}
