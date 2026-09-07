package com.rahga.x2rock.lan

import com.rahga.x2rock.model.Group
import com.rahga.x2rock.model.HomeTheaterFormat
import com.rahga.x2rock.model.ContainerMetadata
import com.rahga.x2rock.model.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Detecting which soundbar the television is plugged into.
 *
 * Shaped after the household this was built against, where three rooms have a Beam and only
 * one is fed by the Shield — so "has an HDMI socket" cannot be the answer on its own.
 */
class TvSoundbarTest {

    private fun player(id: String, vararg caps: String) =
        Player(id = id, name = id, capabilities = caps.toList())

    private fun group(id: String, vararg players: String) =
        Group(id = id, name = id, coordinatorId = players.first(), playerIds = players.toList())

    private val household = HouseholdState(
        connected = true,
        groups = listOf(
            group("living", "beam-living"),
            group("guest", "beam-guest"),
            group("kitchen", "onesl-kitchen"),
        ),
        players = listOf(
            player("beam-living", "PLAYBACK", HT_PLAYBACK),
            player("beam-guest", "PLAYBACK", HT_PLAYBACK),
            player("onesl-kitchen", "PLAYBACK"),
        ),
    )

    private fun onTv(silent: Boolean = true) = GroupState(
        container = ContainerMetadata(
            name = "TV Audio",
            htInputFormat = if (silent) HomeTheaterFormat(2, 0, 0, "Silence")
            else HomeTheaterFormat(5, 1, 0, "Dolby Digital Surround"),
        )
    )

    @Test fun `the one soundbar on its TV input is the television's`() {
        val detected = TvSoundbar.detect(household, mapOf("living" to onTv()))
        assertEquals("beam-living", detected)
    }

    /** A selected input with nothing playing still reports itself, so silence is not absence. */
    @Test fun `a silent input still identifies the room`() {
        assertEquals("beam-living", TvSoundbar.detect(household, mapOf("living" to onTv(silent = true))))
    }

    /** Two televisions on at once is ambiguous, and a guess would be worse than nothing. */
    @Test fun `two soundbars on TV inputs answer nothing`() {
        val detected = TvSoundbar.detect(household, mapOf("living" to onTv(), "guest" to onTv()))
        assertNull(detected)
    }

    @Test fun `no room on a TV input answers nothing`() {
        assertNull(TvSoundbar.detect(household, emptyMap()))
    }

    /** Having an HDMI socket is not the same as being the one in use. */
    @Test fun `a soundbar playing music is not the television's`() {
        val playingMusic = GroupState(container = ContainerMetadata(name = "An Album"))
        assertNull(TvSoundbar.detect(household, mapOf("guest" to playingMusic)))
    }

    /**
     * The reason a *player* is remembered rather than a group: a regroup mints new group
     * ids, and the remembered room has to survive that.
     */
    @Test fun `the remembered soundbar is found again after a regroup`() {
        val regrouped = listOf(
            group("kitchen-9931", "onesl-kitchen", "beam-living"),
            group("guest", "beam-guest"),
        )
        assertEquals("kitchen-9931", TvSoundbar.groupOf("beam-living", regrouped)?.id)
    }

    @Test fun `a soundbar that has left the household is not found`() {
        assertNull(TvSoundbar.groupOf("beam-living", listOf(group("guest", "beam-guest"))))
        assertNull(TvSoundbar.groupOf(null, household.groups))
    }

    /** The HDMI belongs to a member, which need not be the coordinator. */
    @Test fun `the soundbar is found among members, not only the coordinator`() {
        val joined = group("kitchen-9931", "onesl-kitchen", "beam-living")
        val state = household.copy(groups = listOf(joined))
        assertEquals("beam-living", TvSoundbar.soundbarOf(joined, state))
    }
}
