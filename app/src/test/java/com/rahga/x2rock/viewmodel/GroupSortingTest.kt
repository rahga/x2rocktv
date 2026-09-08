package com.rahga.x2rock.viewmodel

import com.rahga.x2rock.model.Group
import com.rahga.x2rock.model.PlaybackStates
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sidebar's order: the pinned room, then Sonos's own alphabetical order.
 *
 * There was a second tier here — favourites, between the two — and it is gone. Pinning a
 * primary room already does the useful version of it, and a room list that disagrees with
 * every other controller in the house needs a better reason than a rarely-touched star.
 */
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
        assertEquals(listOf(attic, bedroom, kitchen, study), sortGroups(all, primaryId = null))
    }

    @Test
    fun `the pinned room comes first, the rest stay alphabetical`() {
        assertEquals(listOf(study, attic, bedroom, kitchen), sortGroups(all, primaryId = "s"))
    }

    @Test
    fun `the pinned room is not listed twice`() {
        val sorted = sortGroups(all, primaryId = "k")
        assertEquals(all.size, sorted.size)
        assertEquals(listOf(kitchen, attic, bedroom, study), sorted)
    }

    @Test
    fun `a primary id that no longer matches any group is ignored`() {
        assertEquals(listOf(attic, bedroom, kitchen, study), sortGroups(all, primaryId = "gone"))
    }
}
