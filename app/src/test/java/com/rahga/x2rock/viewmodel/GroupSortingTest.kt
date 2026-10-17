package com.rahga.x2rock.viewmodel

import com.rahga.x2rock.model.Group
import com.rahga.x2rock.model.PlaybackStates
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sidebar's order: rooms this device has placed first, in their order, then the rest
 * alphabetically, which is Sonos's own (P-20).
 */
class GroupSortingTest {

    private fun group(id: String, name: String, vararg more: String) =
        Group(id, name, coordinatorId = id, playerIds = listOf(id) + more, playbackState = PlaybackStates.IDLE)

    private val kitchen = group("k", "Kitchen")
    private val bedroom = group("b", "Bedroom")
    private val attic = group("a", "Attic")
    private val study = group("s", "Study")
    private val all = listOf(kitchen, bedroom, attic, study)
    private fun names(groups: List<Group>) = groups.map { it.name }

    @Test
    fun `sorts alphabetically when nothing is placed`() {
        assertEquals(listOf(attic, bedroom, kitchen, study), sortGroups(all))
    }

    @Test
    fun `order does not depend on the order the household reported them in`() {
        assertEquals(sortGroups(all), sortGroups(all.reversed()))
    }

    /** Placed rooms first, in their order, whatever the alphabet says; the rest stay alphabetical. */
    @Test
    fun `placed rooms lead in their order, the rest follow alphabetically`() {
        assertEquals(listOf("Study", "Kitchen", "Attic", "Bedroom"), names(sortGroups(all, listOf("s", "k"))))
    }

    /** A group stands where its best-placed speaker does, so grouping never sinks a placed room. */
    @Test
    fun `a group takes the place of its best-placed speaker`() {
        val grouped = listOf(group("a", "Attic", "s"), bedroom, kitchen)
        assertEquals(listOf("Attic", "Bedroom", "Kitchen"), names(sortGroups(grouped, listOf("x", "s"))))
    }

    /**
     * Moving a room places it and every room above it, in the order shown, so the list is what the
     * viewer saw: Study moved up one lands above Kitchen and does not jump to the top, and Kitchen
     * — above it — is placed too. A rule that placed only the moved room would put Study first.
     */
    @Test
    fun `moving a room places it and everything above it, exactly as shown`() {
        val ranks = movedRanks(all, emptyList(), "s", -1)
        assertEquals(listOf("a", "b", "s"), ranks)
        assertEquals(listOf("Attic", "Bedroom", "Study", "Kitchen"), names(sortGroups(all, ranks)))
    }

    /** Moving a placed room down past unplaced ones keeps every room that was placed, placed. */
    @Test
    fun `moving down keeps every placed room placed`() {
        val ranks = movedRanks(all, listOf("s", "k", "a"), "s", +1)
        assertEquals(listOf("Kitchen", "Study", "Attic", "Bedroom"), names(sortGroups(all, ranks)))
        assertEquals(listOf("k", "s", "a"), ranks)
    }

    /** A placed speaker that is not in the household right now keeps its place for when it returns. */
    @Test
    fun `a placed speaker away from the household keeps its place`() {
        val ranks = movedRanks(all, listOf("gone", "k"), "s", -3)
        assertEquals(listOf("s", "k", "gone"), ranks)
    }

    @Test
    fun `a move past either end does nothing`() {
        assertEquals(emptyList<String>(), movedRanks(all, emptyList(), "a", -1))
    }
}
