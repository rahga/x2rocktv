package com.rahga.x2rock.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MatchHouseholdTest {

    private val households = mapOf(
        "household-1" to listOf("Bedroom", "Dining Room", "Guest TV", "Kitchen", "Living Room"),
        "household-2" to listOf("Media Room")
    )

    @Test
    fun `finds the household containing the named room, case-insensitively`() {
        assertEquals("household-1", matchHousehold(households, "kitchen"))
        assertEquals("household-2", matchHousehold(households, "MEDIA ROOM"))
    }

    @Test
    fun `no household has that room is an error naming the query`() {
        val e = assertThrows(NoSuchHouseholdException::class.java) { matchHousehold(households, "Garage") }
        assertEquals("Garage", e.query)
    }

    @Test
    fun `a room name in more than one household is an error naming every match`() {
        val ambiguous = mapOf(
            "household-1" to listOf("Kitchen"),
            "household-2" to listOf("Kitchen")
        )
        val e = assertThrows(AmbiguousHouseholdException::class.java) { matchHousehold(ambiguous, "Kitchen") }
        assertEquals(listOf("Kitchen", "Kitchen"), e.candidates)
    }
}
