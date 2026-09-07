package com.rahga.x2rock.lan

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frames here are trimmed from real traffic captured off a household on 2026-09-07
 * (see `docs/lan-transport.md`), not invented, so the discriminator being tested is the
 * one the players actually send.
 */
class FrameTest {

    private val getGroupsReply = """
        [{"namespace":"groups:1","householdId":"Sonos_abc","locationId":"lc_1",
          "response":"getGroups","success":true,"type":"groups","cmdId":"1"},
         {"_objectType":"groups","groups":[{"id":"RINCON_A:1","name":"Living Room",
          "coordinatorId":"RINCON_A","playbackState":"PLAYBACK_STATE_PLAYING",
          "playerIds":["RINCON_A"]}]}]
    """.trimIndent()

    private val groupVolumeEvent = """
        [{"namespace":"groupVolume:1","householdId":"Sonos_abc","locationId":"lc_1",
          "groupId":"RINCON_A:1","name":"groupVolume","type":"groupVolume"},
         {"volume":20,"muted":false,"fixed":false}]
    """.trimIndent()

    @Test
    fun `a reply carries success and is correlated by cmdId`() {
        val frame = Frames.decode(getGroupsReply)
        assertTrue(frame is SonosReply)
        val reply = frame as SonosReply
        assertTrue(reply.isSuccess)
        assertEquals("1", reply.header.cmdId)
        assertEquals("groups:1", reply.header.namespace)
        assertNull(reply.errorOrNull())
    }

    /** The whole dispatch design rests on this: events never carry `success`. */
    @Test
    fun `an event has no success field and so is not mistaken for a reply`() {
        val frame = Frames.decode(groupVolumeEvent)
        assertTrue(frame is SonosEvent)
        val event = frame as SonosEvent
        assertEquals("groupVolume:1", event.namespace)
        assertEquals("groupVolume", event.type)
        assertEquals("RINCON_A:1", event.header.groupId)
        assertEquals(20, event.body.asJsonObject.get("volume").asInt)
    }

    /**
     * A subscription's opening snapshot and the pushes that follow share namespace and
     * type, so nothing but `success` can tell a reply from an event.
     */
    @Test
    fun `namespace alone cannot discriminate`() {
        val subscribeReply = """
            [{"namespace":"groupVolume:1","groupId":"RINCON_A:1","response":"subscribe",
              "success":true,"type":"none","cmdId":"5"},{}]
        """.trimIndent()
        assertTrue(Frames.decode(subscribeReply) is SonosReply)
        assertTrue(Frames.decode(groupVolumeEvent) is SonosEvent)
    }

    @Test
    fun `a refusal reports the player's own error code`() {
        val refusal = """
            [{"namespace":"playerVolume:1","success":false,"cmdId":"7"},
             {"errorCode":"ERROR_INVALID_OBJECT_ID","reason":"Incorrect playerId"}]
        """.trimIndent()
        val reply = Frames.decode(refusal) as SonosReply
        val error = reply.errorOrNull()
        assertNotNull(error)
        assertTrue(error!!.contains("ERROR_INVALID_OBJECT_ID"))
        assertTrue(error.contains("Incorrect playerId"))
    }

    @Test
    fun `anything that is not a two-element array is ignored rather than thrown`() {
        assertNull(Frames.decode(""))
        assertNull(Frames.decode("not json"))
        assertNull(Frames.decode("{}"))
        assertNull(Frames.decode("[{}]"))
        assertNull(Frames.decode("""[{},{},{}]"""))
    }

    @Test
    fun `encode puts cmdId in the header and keeps the two-element shape`() {
        val header = Frames.onGroup("playback:1", "play", "RINCON_A:1")
        val encoded = Frames.encode(header, JsonObject(), "42")
        assertEquals(
            """[{"namespace":"playback:1","command":"play","groupId":"RINCON_A:1","cmdId":"42"},{}]""",
            encoded,
        )
        // cmdId is a string on the wire, not a number.
        assertTrue(encoded.contains("\"cmdId\":\"42\""))
    }

    @Test
    fun `encode does not mutate the caller's header`() {
        val header = Frames.onGroup("playback:1", "play", "RINCON_A:1")
        Frames.encode(header, JsonObject(), "1")
        Frames.encode(header, JsonObject(), "2")
        assertNull(header.get("cmdId"))
    }

    @Test
    fun `scope helpers name the right field`() {
        assertEquals("RINCON_A:1", Frames.onGroup("playback:1", "play", "RINCON_A:1").get("groupId").asString)
        assertEquals("RINCON_A", Frames.onPlayer("playerVolume:1", "getVolume", "RINCON_A").get("playerId").asString)
        assertEquals("Sonos_abc", Frames.onHousehold("groups:1", "getGroups", "Sonos_abc").get("householdId").asString)
    }
}
