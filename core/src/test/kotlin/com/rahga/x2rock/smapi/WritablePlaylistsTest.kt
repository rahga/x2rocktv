package com.rahga.x2rock.smapi

import com.rahga.x2rock.lan.FakePlayer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding the playlists an account can add to, on Deezer's own pages captured 2026-10-10 (user and
 * playlist numbers and the username redacted): the root, its My Music, and the My Playlists in it.
 */
class WritablePlaylistsTest {

    private val pages = mapOf(
        "root" to "smapi.deezer.root.xml",
        "mylibrary" to "smapi.deezer.mylibrary.xml",
        "playlists" to "smapi.deezer.playlists.xml",
    )
    private val asked = mutableListOf<String>()
    private suspend fun browse(id: String): List<Item> {
        asked += id
        return parseItems(FakePlayer.fixtureText(pages[id] ?: error("not captured: $id"))).items
    }

    @Test fun `the account's own writable place is found one level in, and its playlists listed`() = runBlocking<Unit> {
        val found = writablePlaylists(::browse)
        assertEquals(listOf("Favorite tracks"), found.map { it.title })
        assertEquals("playlist_spotify:playlist-10000000001", found.single().id)
        assertEquals(listOf("root", "mylibrary", "playlists"), asked)
    }

    /** "Favorite Tracks" at the root is the account's own too, but read-only: never a target. */
    @Test fun `the flags are read off each collection`() {
        val root = parseItems(FakePlayer.fixtureText("smapi.deezer.root.xml")).items
        val loved = root.first { it.title == "Favorite Tracks" }
        assertTrue(loved.userContent)
        assertTrue(loved.readOnly)
        val flow = root.first { it.id == "flow" }
        assertTrue("no flags is read-only", flow.readOnly)
    }

    @Test fun `a service with no place of its own offers nothing`() = runBlocking<Unit> {
        val none = writablePlaylists { listOf(Item("x", "Charts", "trackList", null, null, container = true)) }
        assertTrue(none.isEmpty())
    }
}
