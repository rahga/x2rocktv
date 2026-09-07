package com.rahga.x2rock.lan

import com.rahga.x2rock.model.Group
import com.rahga.x2rock.model.PlaybackStates
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression test for a crash seen on device: a `groups:1` event omits `playbackState`,
 * Gson wrote null into a field Kotlin declared non-null, and the first recomposition after
 * a group formed died in `toPlaybackLabel`.
 */
class GroupPlaybackStateTest {

    private fun group(id: String, state: String? = null) =
        Group(id = id, name = id, coordinatorId = id, playerIds = listOf(id), playbackState = state)

    @Test
    fun `a value the event supplied is kept`() {
        val filled = fillPlaybackState(
            listOf(group("a", PlaybackStates.PAUSED)),
            pushed = mapOf("a" to GroupState(playbackState = PlaybackStates.PLAYING)),
            previous = emptyList(),
        )
        assertEquals(PlaybackStates.PAUSED, filled.single().playbackState)
    }

    /** The subscription is more current than the groups list, so it wins over history. */
    @Test
    fun `a missing value comes from the group's own subscription first`() {
        val filled = fillPlaybackState(
            listOf(group("a")),
            pushed = mapOf("a" to GroupState(playbackState = PlaybackStates.PLAYING)),
            previous = listOf(group("a", PlaybackStates.PAUSED)),
        )
        assertEquals(PlaybackStates.PLAYING, filled.single().playbackState)
    }

    @Test
    fun `with nothing pushed yet the last known value carries forward`() {
        val filled = fillPlaybackState(
            listOf(group("a")),
            pushed = emptyMap(),
            previous = listOf(group("a", PlaybackStates.PAUSED)),
        )
        assertEquals(PlaybackStates.PAUSED, filled.single().playbackState)
    }

    /** The case that crashed: a brand-new group, nothing known about it at all. */
    @Test
    fun `an unknown group falls back to idle, never null`() {
        val filled = fillPlaybackState(listOf(group("new")), emptyMap(), emptyList())
        assertEquals(PlaybackStates.IDLE, filled.single().playbackState)
    }

    @Test
    fun `every group comes back with something`() {
        val filled = fillPlaybackState(
            listOf(group("a"), group("b", PlaybackStates.PLAYING), group("c")),
            pushed = mapOf("c" to GroupState(playbackState = PlaybackStates.BUFFERING)),
            previous = listOf(group("a", PlaybackStates.PAUSED)),
        )
        assertEquals(
            listOf(PlaybackStates.PAUSED, PlaybackStates.PLAYING, PlaybackStates.BUFFERING),
            filled.map { it.playbackState },
        )
    }
}
