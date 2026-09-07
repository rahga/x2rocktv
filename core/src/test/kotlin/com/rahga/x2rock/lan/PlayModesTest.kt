package com.rahga.x2rock.lan

import com.google.gson.JsonParser
import com.rahga.x2rock.model.PlayModeState
import com.rahga.x2rock.model.RepeatModes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sonos states repeat as two booleans; the app models it as one of three strings. The
 * round trip has to survive that, and `repeatOne` has to win when both are set.
 */
class PlayModesTest {

    private fun modes(json: String) =
        PlayModes.fromJson(JsonParser.parseString(json).asJsonObject)

    @Test
    fun `both flags false is no repeat`() {
        assertEquals(RepeatModes.NONE, modes("""{"repeat":false,"repeatOne":false}""").repeat)
    }

    @Test
    fun `repeat alone is repeat-all`() {
        assertEquals(RepeatModes.ALL, modes("""{"repeat":true,"repeatOne":false}""").repeat)
    }

    @Test
    fun `repeatOne is repeat-one`() {
        assertEquals(RepeatModes.ONE, modes("""{"repeat":false,"repeatOne":true}""").repeat)
    }

    /** Players have been seen setting both; the narrower mode is the true one. */
    @Test
    fun `repeatOne wins when both flags are set`() {
        assertEquals(RepeatModes.ONE, modes("""{"repeat":true,"repeatOne":true}""").repeat)
    }

    @Test
    fun `absent flags default to off rather than throwing`() {
        val m = modes("""{"shuffle":true}""")
        assertEquals(RepeatModes.NONE, m.repeat)
        assertTrue(m.shuffle)
        assertFalse(m.crossfade)
    }

    @Test
    fun `a missing playModes object is not an error`() {
        assertEquals(PlayModeState(), PlayModes.fromJson(null))
    }

    /** Every flag is stated on the way out, so a set never inherits the other's old value. */
    @Test
    fun `the body always carries all four flags`() {
        val body = PlayModes.toBody(PlayModeState(repeat = RepeatModes.ONE, shuffle = true))
            .getAsJsonObject("playModes")
        assertEquals(setOf("repeat", "repeatOne", "shuffle", "crossfade"), body.keySet())
        assertFalse(body.get("repeat").asBoolean)
        assertTrue(body.get("repeatOne").asBoolean)
        assertTrue(body.get("shuffle").asBoolean)
        assertFalse(body.get("crossfade").asBoolean)
    }

    @Test
    fun `every repeat mode survives a round trip`() {
        listOf(RepeatModes.NONE, RepeatModes.ALL, RepeatModes.ONE).forEach { repeat ->
            val original = PlayModeState(repeat = repeat, shuffle = true, crossfade = true)
            val back = PlayModes.fromJson(PlayModes.toBody(original).getAsJsonObject("playModes"))
            assertEquals(original, back)
        }
    }
}
