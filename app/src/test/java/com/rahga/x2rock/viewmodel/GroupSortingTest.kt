package com.rahga.x2rock.viewmodel

import com.rahga.x2rock.model.Group
import com.rahga.x2rock.model.PlaybackStates
import com.rahga.x2rock.store.RoomOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sidebar's order: Sonos's alphabetical by default, or playing rooms first where this device
 * chose it (P-20) — the two sorts the Sonos app offers that the owner wanted here.
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

    /**
     * Playing rooms first, each part alphabetical. Study plays and Kitchen buffers, so both sort
     * above Attic and Bedroom, which alphabetical order puts first: a sort that ignored playback
     * would put Attic on top and fail.
     */
    @Test
    fun `playing first puts playing and buffering rooms on top, each part alphabetical`() {
        val playing = all.map {
            when (it) {
                study -> it.copy(playbackState = PlaybackStates.PLAYING)
                kitchen -> it.copy(playbackState = PlaybackStates.BUFFERING)
                bedroom -> it.copy(playbackState = PlaybackStates.PAUSED)
                else -> it
            }
        }
        assertEquals(listOf("Kitchen", "Study", "Attic", "Bedroom"), sortGroups(playing, RoomOrder.PLAYING_FIRST).map { it.name })
        assertEquals(listOf("Attic", "Bedroom", "Kitchen", "Study"), sortGroups(playing).map { it.name })
    }
}
