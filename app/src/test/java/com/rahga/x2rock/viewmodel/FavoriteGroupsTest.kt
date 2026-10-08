package com.rahga.x2rock.viewmodel

import com.google.gson.Gson
import com.rahga.x2rock.lan.FakePlayer
import com.rahga.x2rock.model.FavoritesResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Browse's favourites, grouped and labelled — against the household's captured `getFavorites`. */
class FavoriteGroupsTest {
    private val favorites = Gson().fromJson(FakePlayer.fixtureText("getFavorites.reply.json"), FavoritesResponse::class.java).items

    @Test fun `favourites are grouped by what they are, in the Sonos app's order`() {
        val groups = favoriteGroups(favorites).map { (title, items) -> title to items.map { it.name } }
        assertEquals(
            listOf(
                "Favorite albums" to listOf("Ryo Fukui in New York"),
                "Favorite stations" to listOf("Love Songs Radio"),
                // A favourite whose service was removed has no type left to group it by.
                "Favorites" to listOf("Scenery"),
            ),
            groups,
        )
    }

    @Test fun `a favourite's line says what it is and whose`() {
        assertEquals("Radio · iHeartRadio", favoriteLine(favorites.first { it.name == "Love Songs Radio" }))
        assertEquals("Album · YouTube Music", favoriteLine(favorites.first { it.name == "Ryo Fukui in New York" }))
    }

    /** An album opens on its tracks only where its service can be browsed here, by the service's own id. */
    @Test fun `an album favourite opens on its service's page only where that service is browsable`() {
        val album = favorites.first { it.name == "Ryo Fukui in New York" }
        assertEquals("284:sn_23" to "ALkSOiF60SmN3aW6x6j0p5ECA5_qJlTo", favoritePage(album, setOf("284:sn_23"))?.let { it.first to it.second.take(32) })
        assertNull("YouTube Music is not browsable here", favoritePage(album, emptySet()))
        assertNull("a station plays on a press", favoritePage(favorites.first { it.name == "Love Songs Radio" }, setOf("6:sn_24")))
    }
}
