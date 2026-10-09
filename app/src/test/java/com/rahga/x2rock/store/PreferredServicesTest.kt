package com.rahga.x2rock.store

import com.rahga.x2rock.viewmodel.FakePreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/** The preferred tier: added at its foot, moved within it, taken out of it, and kept. */
class PreferredServicesTest {

    @Test fun `a service is added at the foot, moved within the tier, and removed`() {
        val prefs = FakePreferences()
        val preferred = PreferredServices(prefs)
        preferred.setPreferred("2", true)
        preferred.setPreferred("31", true)
        preferred.setPreferred("apple", true)
        assertEquals(listOf("2", "31", "apple"), preferred.order.value)
        preferred.move("apple", -1)
        assertEquals(listOf("2", "apple", "31"), preferred.order.value)
        // Moving past either end stops there.
        preferred.move("2", -5)
        assertEquals(listOf("2", "apple", "31"), preferred.order.value)
        preferred.setPreferred("2", false)
        assertEquals(listOf("apple", "31"), preferred.order.value)
        // And it is still so on the next launch.
        assertEquals(listOf("apple", "31"), PreferredServices(prefs).order.value)
    }

    @Test fun `a service not in the tier ranks after every one that is`() {
        assertEquals(1, preferredRank("31", listOf("2", "31")))
        assertEquals(Int.MAX_VALUE, preferredRank("254", listOf("2", "31")))
    }
}
