package com.rahga.x2rock.lan

import com.rahga.x2rock.model.MusicObjectId
import com.rahga.x2rock.model.Track
import com.rahga.x2rock.model.TrackArtist
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Real TIDAL GetPositionInfo/CreateObject/Browse captures, 2026-10-16. */
class TrackFavoritesTest {
    private val id = MusicObjectId("track/2178486", "174", "sn_27")
    private val track = Track("So What (feat. John Coltrane, Cannonball Adderley & Bill Evans)",
        TrackArtist("Miles Davis"), null, null, id = id)
    private fun source() = trackFavoriteSource(FakePlayer.fixtureText("GetPositionInfo.tidal.xml"), track)
    private fun didl(file: String) = Xml.parse(FakePlayer.fixtureText(file)).firstNamed("Result")!!.textContent

    @Test fun `saving preserves the real stream and completes the identity the phone needs`() {
        val source = source()
        val completed = source.withIdentity(id, 44551)
        val payload = Xml.parse(completed.elements("TIDAL"))
        assertEquals("object.item.sonos-favorite", payload.firstNamed("class")!!.textContent)
        assertEquals("instantPlay", payload.firstNamed("type")!!.textContent)
        assertEquals("x-sonos-http:track%2f2178486.flac?sid=174&flags=24616&sn=27", payload.firstNamed("res")!!.textContent)
        assertEquals(track.name, payload.firstNamed("title")!!.textContent)
        val inner = Xml.parse(payload.firstNamed("resMD")!!.textContent)
        assertEquals("00032020track%2f2178486", inner.firstNamed("item")!!.getAttribute("id"))
        assertEquals("SA_RINCON44551_X_#Svc44551-0-Token", inner.firstNamed("desc")!!.textContent)
        val original = Xml.parse(source.metadata)
        for (tag in listOf("res", "creator", "album", "albumArtURI", "title", "class")) {
            assertEquals(tag, original.firstNamed(tag)!!.textContent, inner.firstNamed(tag)!!.textContent)
        }
        assertEquals("sonos.com-http:*:audio/flac:*", inner.firstNamed("res")!!.getAttribute("protocolInfo"))
        assertEquals(completed, completed.withIdentity(id, null))
    }

    @Test fun `both a phone shortcut and our saved track prevent another copy`() {
        assertTrue(containsTrackFavorite(didl("Browse.FV2.shortcuts.xml"), source(), id))
        // Select only the newly created entry; the original phone shortcut must not mask a failure.
        val result = Xml.parse(didl("Browse.FV2.created-track.xml"))
        val saved = result.allNamed("item").single { it.getAttribute("id") == "FV:2/102" }
        result.allNamed("item").filter { it !== saved }.forEach { result.removeChild(it) }
        val metadata = saved.firstChildNamed("resMD")!!.textContent
        val uri = saved.firstChildNamed("res")!!.textContent
        val isolated = "<DIDL-Lite xmlns:r=\"urn:schemas-rinconnetworks-com:metadata-1-0/\"><item><res>${Xml.escape(uri)}</res><r:resMD>${Xml.escape(metadata)}</r:resMD></item></DIDL-Lite>"
        assertTrue(containsTrackFavorite(isolated, source(), id))
        assertTrue(containsTrackFavorite(isolated, source().copy(uri = "different account URI"), id))
        assertFalse(containsTrackFavorite(isolated, source().copy(uri = "other"), id.copy(serviceId = "999")))
        assertFalse(containsTrackFavorite(isolated, source().copy(uri = "other"), id.copy(objectId = "track/other")))
    }

    @Test fun `a track that changed during the read is refused`() {
        assertThrows(IOException::class.java) {
            trackFavoriteSource(FakePlayer.fixtureText("GetPositionInfo.tidal.xml"), track.copy(name = "Another song"))
        }
        assertThrows(IOException::class.java) {
            trackFavoriteSource(FakePlayer.fixtureText("GetPositionInfo.tidal.xml"), track.copy(artist = TrackArtist("Another artist")))
        }
    }

    @Test fun `streams and missing metadata are not saved as songs`() {
        // Deliberately altered captured reply: these are scenarios, not new captures.
        assertThrows(IOException::class.java) {
            trackFavoriteSource(FakePlayer.fixtureText("GetPositionInfo.tidal.xml")
                .replace("object.item.audioItem.musicTrack", "object.item.audioItem.audioBroadcast"), track)
        }
        assertThrows(IOException::class.java) { trackFavoriteSource("<reply><TrackURI>stream</TrackURI></reply>", track) }
        assertThrows(IOException::class.java) { source().withIdentity(id, null) }
    }
}
