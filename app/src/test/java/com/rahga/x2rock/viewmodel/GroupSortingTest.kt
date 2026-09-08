package com.rahga.x2rock.viewmodel

import com.rahga.x2rock.model.Group
import com.rahga.x2rock.model.PlaybackStates
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sidebar's order: Sonos's own, which is alphabetical and nothing else.
 *
 * Two hoists have been removed from above this — favourites, then a pinned primary room.
 * Sonos offers no ordering of its own, so there is nothing to reorder *to*, and a list that
 * disagrees with every other controller in the house needs a better reason than either had.
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
        assertEquals(listOf(attic, bedroom, kitchen, study), sortGroups(all))
    }

    @Test
    fun `order does not depend on the order the household reported them in`() {
        assertEquals(sortGroups(all), sortGroups(all.reversed()))
    }
}
