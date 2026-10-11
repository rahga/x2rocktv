package com.rahga.x2rock.viewmodel

import com.rahga.x2rock.lan.FakePlayer
import com.rahga.x2rock.smapi.parseShelves
import org.junit.Assert.assertEquals
import org.junit.Test

/** [shelfCount] on Sonos Radio's captured page: a shelf of stations, and Browse Radio's places. */
class ShelfCountTest {
    private val shelves = parseShelves(FakePlayer.fixtureText("sonosradio.browse.json"))

    @Test fun `a shelf of stations counts stations`() {
        assertEquals("10 stations", shelfCount(shelves.first { it.title == "Trending Now" }.items))
    }

    @Test fun `a shelf of albums and playlists counts items`() {
        val spotify = parseShelves(FakePlayer.fixtureText("spotify.browse.json"))
        assertEquals("10 items", shelfCount(spotify.first { it.title == "Recommended for today" }.items))
    }

    @Test fun `Browse Radio counts categories, not stations`() {
        assertEquals("4 categories", shelfCount(shelves.first { it.title == "Browse Radio" }.items))
    }
}
