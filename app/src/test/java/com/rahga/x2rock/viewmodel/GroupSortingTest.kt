package com.rahga.x2rock.viewmodel

import com.rahga.x2rock.model.Group
import com.rahga.x2rock.model.PlaybackStates
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupSortingTest {

    private fun group(id: String, name: String) =
        Group(id, name, coordinatorId = id, playerIds = listOf(id), playbackState = PlaybackStates.IDLE)

    private val kitchen = group("k", "Kitchen")
    private val bedroom = group("b", "Bedroom")
    private val attic = group("a", "Attic")
    private val study = group("s", "Study")
    private val all = listOf(kitchen, bedroom, attic, study)

    @Test
    fun `sorts alphabetically when nothing is pinned`() {
        assertEquals(
            listOf(attic, bedroom, kitchen, study),
            sortGroups(all, primaryId = null, favoriteIds = emptySet())
        )
    }

    @Test
    fun `primary is pinned first, then favourites, then the rest`() {
        assertEquals(
            listOf(study, bedroom, kitchen, attic),
            sortGroups(all, primaryId = "s", favoriteIds = setOf("b", "k"))
        )
    }

    @Test
    fun `primary that is also a favourite is not listed twice`() {
        val sorted = sortGroups(all, primaryId = "k", favoriteIds = setOf("k", "b"))
        assertEquals(all.size, sorted.size)
        assertEquals(listOf(kitchen, bedroom, attic, study), sorted)
    }

    @Test
    fun `a primary id that no longer matches any group is ignored`() {
        assertEquals(
            listOf(attic, bedroom, kitchen, study),
            sortGroups(all, primaryId = "gone", favoriteIds = emptySet())
        )
    }
}
