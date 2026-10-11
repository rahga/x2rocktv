package com.rahga.x2rock.store

import com.rahga.x2rock.viewmodel.FakePreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/** Recent searches: newest first, once each whatever the case, at most eight, forgettable, kept. */
class RecentSearchesTest {

    @Test fun `a term goes to the top once, the oldest falls off, and it is kept`() {
        val prefs = FakePreferences()
        val recent = RecentSearches(prefs)
        recent.add("miles davis")
        recent.add("  coltrane ")
        recent.add("Miles Davis")
        assertEquals("the same term again moves up rather than repeating", listOf("Miles Davis", "coltrane"), recent.terms.value)
        recent.add("")
        assertEquals("a blank search is not one", 2, recent.terms.value.size)
        (1..10).forEach { recent.add("term $it") }
        assertEquals(RecentSearches.LIMIT, recent.terms.value.size)
        assertEquals("term 10", recent.terms.value.first())
        recent.remove("term 10")
        assertEquals("term 9", recent.terms.value.first())
        assertEquals(recent.terms.value, RecentSearches(prefs).terms.value)
    }
}
