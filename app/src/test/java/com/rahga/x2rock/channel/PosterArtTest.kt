package com.rahga.x2rock.channel

import com.rahga.x2rock.lan.PlayerAddressBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Posters the launcher fetches itself, with a resolver that knows no `.local` names. */
class PosterArtTest {

    private val book = PlayerAddressBook().apply { register("sonos-aa00bbccddee.local", "192.168.1.20") }

    @Test fun `a player's art is given by address`() {
        assertEquals(
            "http://192.168.1.20:1400/getaa?s=1&u=x-sonos-spotify%3Atrack",
            PosterArt.forLauncher("http://sonos-AA00BBCCDDEE.local:1400/getaa?s=1&u=x-sonos-spotify%3Atrack", book),
        )
    }

    @Test fun `a service's https art is left as it is`() {
        val url = "https://i.scdn.co/image/ab67616d0000b273"
        assertEquals(url, PosterArt.forLauncher(url, book))
    }

    /** Published, it would be a tile with a poster that cannot load. */
    @Test fun `art that cannot be fetched is no poster`() {
        assertNull("a player not in the book", PosterArt.forLauncher("http://sonos-ffeeddccbbaa.local:1400/getaa", book))
        assertNull("cleartext from elsewhere", PosterArt.forLauncher("http://cdn.example/art.jpg", book))
        assertNull(PosterArt.forLauncher(null, book))
    }
}
