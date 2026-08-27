package com.rahga.x2rock.cli

import com.rahga.x2rock.model.Group
import com.rahga.x2rock.model.PlaybackStates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MatchRoomTest {

    private fun group(name: String, vararg players: String) =
        Group(id = name, name = name, coordinatorId = players.first(), playerIds = players.toList(), playbackState = PlaybackStates.IDLE)

    private val players = mapOf("p1" to "Kitchen", "p2" to "Kids Room", "p3" to "Living Room", "p4" to "Office")
    private val kitchenGroup = group("Kitchen + 1", "p1", "p3")   // Kitchen grouped with Living Room
    private val kids = group("Kids Room", "p2")
    private val office = group("Office", "p4")
    private val all = listOf(kitchenGroup, kids, office)

    private fun match(q: String) = matchRoom(all, q, players::getValue)

    @Test
    fun `exact name wins regardless of case`() {
        assertEquals(office, match("office"))
        assertEquals(kids, match("KIDS ROOM"))
    }

    @Test
    fun `a unique prefix is enough`() {
        assertEquals(office, match("off"))
        assertEquals(kids, match("kid"))
    }

    @Test
    fun `a speaker name finds the group it currently belongs to`() {
        // The group is called "Kitchen + 1", but the person still calls the room Kitchen.
        assertEquals(kitchenGroup, match("kitchen"))
        assertEquals(kitchenGroup, match("Living Room"))
    }

    @Test
    fun `an ambiguous prefix is an error that names the candidates`() {
        val e = assertThrows(AmbiguousRoomException::class.java) { match("ki") }
        assertEquals(setOf("Kitchen + 1", "Kids Room"), e.candidates.toSet())
    }

    @Test
    fun `no match is null, not an exception`() {
        assertNull(match("garage"))
    }
}
