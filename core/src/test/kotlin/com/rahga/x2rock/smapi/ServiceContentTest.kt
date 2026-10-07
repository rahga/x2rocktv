package com.rahga.x2rock.smapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The queue-URI and DIDL construction, checked against the forms x2rock read off a player
 * (`bookmarks.rs`). The live `AddURIToQueue` leg mutates a room and is verified on hardware; this
 * pins the strings the player is handed, which is where a wrong colon or flag would be.
 */
class ServiceContentTest {

    private fun track(id: String, title: String = "t") =
        Item(id = id, title = title, itemType = "track", summary = null, artUrl = null, container = false)

    private fun container(id: String, type: String, title: String = "c") =
        Item(id = id, title = title, itemType = type, summary = null, artUrl = null, container = true)

    @Test fun `a track is fetched under the hls-static scheme`() {
        val uri = ServiceContent.enqueueUri(track("tr-flac:536421002"), serviceId = "2", serial = "10")
        assertEquals("x-sonosapi-hls-static:tr-flac%3a536421002?sid=2&flags=65544&sn=10", uri)
    }

    @Test fun `a container is expanded under cpcontainer with its own prefix`() {
        val uri = ServiceContent.enqueueUri(container("album:123", "album"), serviceId = "2", serial = "10")
        assertEquals("x-rincon-cpcontainer:1004206calbum%3a123?sid=2&flags=8300&sn=10", uri)
    }

    @Test fun `Spotify keeps its own scheme`() {
        val uri = ServiceContent.enqueueUri(track("spotify:track:abc"), serviceId = "12", serial = null)
        assertTrue(uri.startsWith("x-sonos-spotify:spotify%3atrack%3aabc?sid=12&flags=65544"))
        assertFalse("no sn when the account is unnamed", uri.contains("&sn="))
    }

    @Test fun `the cdudn names the service type and the account selector`() {
        // Qobuz: 31 * 256 + 7 = 7943. A real selector picks one of several accounts.
        assertEquals("SA_RINCON7943_X_#Svc7943-6c0ffea0-Token", ServiceContent.cdudn(7943, "6c0ffea0"))
        // No selector, or the literal 0, is the `-0-` form that names only the service.
        assertEquals("SA_RINCON7943_X_#Svc7943-0-Token", ServiceContent.cdudn(7943, ""))
        // No service type (not in the player's list) means no cdudn can be built.
        assertEquals(null, ServiceContent.cdudn(null, "x"))
    }

    @Test fun `a track DIDL carries the musicTrack class and the cdudn`() {
        val didl = ServiceContent.enqueueDidl(track("tr-flac:1", "SICKO & MODE"), "SA_RINCON514_X_#Svc514-0-Token")
        assertTrue(didl.contains("""<item id="00032020tr-flac%3a1" parentID="-1""""))
        assertTrue(didl.contains("object.item.audioItem.musicTrack"))
        assertTrue("title is xml-escaped", didl.contains("SICKO &amp; MODE"))
        assertTrue(didl.contains(""">SA_RINCON514_X_#Svc514-0-Token</desc>"""))
    }

    @Test fun `an album DIDL is a musicAlbum container under parent 0`() {
        val didl = ServiceContent.enqueueDidl(container("album:9", "album"), "cd")
        assertTrue(didl.contains("""<item id="1004206calbum%3a9" parentID="0""""))
        assertTrue(didl.contains("object.container.album.musicAlbum"))
    }

    private fun leaf(id: String, type: String) =
        Item(id = id, title = "x", itemType = type, summary = null, artUrl = null, container = false)

    @Test fun `what a queue can and cannot hold`() {
        assertTrue(ServiceContent.canEnqueue(track("x")))
        assertTrue(ServiceContent.canEnqueue(container("album:1", "album")))
        assertTrue(ServiceContent.canEnqueue(container("pl:1", "playlist")))
        // An artist holds other containers, not tracks — a queue refuses it (UPnP 804).
        assertFalse(ServiceContent.canEnqueue(container("artist:1", "artist")))
        assertFalse(ServiceContent.canEnqueue(container("genre:1", "genre")))
        // A stream is played, not queued; a radio program is refused by AddURIToQueue (800).
        assertFalse(ServiceContent.canEnqueue(leaf("s1", "stream")))
        assertFalse(ServiceContent.canEnqueue(leaf("channel:5", "program")))
    }

    @Test fun `a radio program plays as the room's source`() {
        val program = leaf("channel:5:4:resume", "program")
        assertTrue(ServiceContent.isRadioProgram(program))
        // Read off Radio Paradise as the Sonos app started it: x-sonosapi-radio, flags=0, colon escaped.
        assertEquals(
            "x-sonosapi-radio:channel%3a5%3a4%3aresume?sid=308&flags=0&sn=20",
            ServiceContent.radioUri(program, serviceId = "308", serial = "20"),
        )
        val didl = ServiceContent.radioDidl(program, "SA_RINCON78855_X_#Svc78855-0-Token")
        assertTrue(didl, """<item id="000c0000channel%3a5%3a4%3aresume" parentID="-1"""" in didl)
        assertTrue("a broadcast, not a track", "object.item.audioItem.audioBroadcast" in didl)
        assertTrue(didl.contains(">SA_RINCON78855_X_#Svc78855-0-Token</desc>"))
    }

    @Test fun `a program with no account serial sends no sn`() {
        val uri = ServiceContent.radioUri(leaf("c:1", "program"), serviceId = "308", serial = null)
        assertEquals("x-sonosapi-radio:c%3a1?sid=308&flags=0", uri)
    }

    @Test fun `only a colon and other reserved bytes are escaped`() {
        // YouTube Music's alphanumeric-ish ids pass through; a colon does not.
        assertEquals("abc_DEF-123.~", ServiceContent.encodeObjectId("abc_DEF-123.~"))
        assertEquals("cloudcast%3a2191051074", ServiceContent.encodeObjectId("cloudcast:2191051074"))
    }
}
