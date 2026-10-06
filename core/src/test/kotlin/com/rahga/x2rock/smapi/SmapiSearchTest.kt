package com.rahga.x2rock.smapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing tests for SMAPI search / browse, ported from x2rock's `sonos/smapi.rs` test module.
 * Every payload below is a shape x2rock captured verbatim off a real service and recorded in
 * that module — Plex's collection-declaring-track, Deezer's `trackMetadata` nesting, Hype
 * Machine's custom category, YouTube Music's duplicate library block — not invented here.
 */
class SmapiSearchTest {

    @Test fun `a collection declaring itself a track is not a container`() {
        // Plex, 2026-08-31: a tracks search answered in mediaCollection whose itemType is
        // track. The id inside is playable, so the wrapping must not outrank the declared leaf.
        val body = """<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body>
            <searchResponse><searchResult><index>0</index><count>2</count><total>2</total>
              <mediaCollection>
                <id>c69ee188::68163:track</id><itemType>track</itemType>
                <title>Señorita</title><summary>Various Artists on Prime</summary><canPlay>true</canPlay>
              </mediaCollection>
              <mediaCollection>
                <id>c69ee188::68162:album</id><itemType>album</itemType>
                <title>Now 103</title><canPlay>true</canPlay>
              </mediaCollection>
            </searchResult></searchResponse></soap:Body></soap:Envelope>"""
        val page = parseItems(body)
        assertEquals(2, page.total)
        assertFalse("a track hit must be playable", page.items[0].container)
        assertTrue("an album stays a place to open", page.items[1].container)
    }

    @Test fun `a track keeps its artist and cover inside track metadata`() {
        // Deezer's real shape: the element carries only id/itemType/title, everything a picker
        // shows is one level down. Reading direct children alone found no artist and no art.
        val body = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
            <searchResponse><searchResult><index>0</index><count>1</count><total>1</total>
              <mediaMetadata>
                <id>tr-flac:536421002</id><itemType>track</itemType><title>SICKO MODE</title>
                <trackMetadata>
                  <artist>Travis Scott</artist><album>ASTROWORLD</album>
                  <albumArtURI>https://cdn-images.dzcdn.net/cover.jpg</albumArtURI><duration>313</duration>
                </trackMetadata>
              </mediaMetadata>
            </searchResult></searchResponse></s:Body></s:Envelope>"""
        val page = parseItems(body)
        assertEquals(1, page.total)
        assertEquals("Travis Scott", page.items[0].summary)
        assertEquals("https://cdn-images.dzcdn.net/cover.jpg", page.items[0].artUrl)
        assertFalse("a track is not a place to open", page.items[0].container)
    }

    @Test fun `a direct summary still outranks the nested artist`() {
        // The nested read is a fallback, not a replacement.
        val body = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
            <searchResponse><searchResult><total>1</total>
              <mediaMetadata>
                <id>x:1</id><itemType>track</itemType><title>Señorita</title>
                <summary>Various Artists on Prime</summary>
                <trackMetadata><artist>Shawn Mendes</artist></trackMetadata>
              </mediaMetadata>
            </searchResult></searchResponse></s:Body></s:Envelope>"""
        assertEquals("Various Artists on Prime", parseItems(body).items[0].summary)
    }

    @Test fun `search hits keep the service's document order when the types are mixed`() {
        // A mixed page must not be reordered into all-metadata-then-all-collections.
        val body = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
            <searchResponse><searchResult><total>3</total>
              <mediaCollection><id>a</id><itemType>artist</itemType><title>A</title></mediaCollection>
              <mediaMetadata><id>t</id><itemType>track</itemType><title>T</title></mediaMetadata>
              <mediaCollection><id>b</id><itemType>album</itemType><title>B</title></mediaCollection>
            </searchResult></searchResponse></s:Body></s:Envelope>"""
        assertEquals(listOf("a", "t", "b"), parseItems(body).items.map { it.id })
    }

    @Test fun `a custom category is searchable under the name the service gave it`() {
        // Hype Machine's real map: Blogs is a shelf Sonos never standardised.
        val body = """<Presentation>
          <PresentationMap type="Search"><Match><SearchCategories>
            <Category id="artists" mappedId="SART"/>
            <Category id="tracks" mappedId="STRK"/>
            <CustomCategory stringId="Blogs" mappedId="SBLG"/>
          </SearchCategories></Match></PresentationMap>
        </Presentation>"""
        assertEquals(
            listOf("artists" to "SART", "tracks" to "STRK", "Blogs" to "SBLG"),
            parseSearchCategories(body).map { it.id to it.mappedId },
        )
    }

    @Test fun `a standard category may leave its mapped id out`() {
        val body = """<PresentationMap type="Search"><Match><SearchCategories>
            <Category id="albums"/>
        </SearchCategories></Match></PresentationMap>"""
        val got = parseSearchCategories(body)
        assertEquals("albums", got[0].id)
        assertEquals("sent as itself", "albums", got[0].mappedId)
    }

    @Test fun `categories come only from the search map when there is one`() {
        val body = """<Presentation>
            <PresentationMap type="DisplayType">
                <Category id="not-a-search-category" mappedId="nope"/>
            </PresentationMap>
            <PresentationMap type="Search"><Match><SearchCategories>
                <Category id="tracks" mappedId="STRK"/>
            </SearchCategories></Match></PresentationMap>
        </Presentation>"""
        val got = parseSearchCategories(body)
        assertEquals(1, got.size)
        assertEquals("tracks", got[0].id)
    }

    @Test fun `a library category repeating a canonical id keeps its own name`() {
        // YouTube Music's real map: two SearchCategories blocks, the second the person's uploads.
        val body = """<PresentationMap type="Search"><Match>
            <SearchCategories stringId="YouTube Music">
                <Category id="tracks" mappedId="SONGS"/>
                <Category id="albums" mappedId="ALBUMS"/>
            </SearchCategories>
            <SearchCategories stringId="Library">
                <Category id="tracks" mappedId="UPLOADED_SONGS"/>
                <Category id="albums" mappedId="UPLOADED_ALBUMS"/>
            </SearchCategories>
        </Match></PresentationMap>"""
        assertEquals(
            listOf(
                "tracks" to "SONGS", "albums" to "ALBUMS",
                "UPLOADED_SONGS" to "UPLOADED_SONGS", "UPLOADED_ALBUMS" to "UPLOADED_ALBUMS",
            ),
            parseSearchCategories(body).map { it.id to it.mappedId },
        )
    }

    @Test fun `a half declared custom category is skipped rather than guessed at`() {
        val body = """<PresentationMap type="Search"><Match><SearchCategories>
            <CustomCategory stringId="Blogs"/>
            <CustomCategory mappedId="SBLG"/>
            <Category id="tracks" mappedId="STRK"/>
        </SearchCategories></Match></PresentationMap>"""
        val got = parseSearchCategories(body)
        assertEquals("only the whole one survives", 1, got.size)
        assertEquals("tracks", got[0].id)
    }

    @Test fun `an audiobook's resume point is read from its positionInformation`() {
        // Audible's real getMetadata shape (Dune, 2026-10-06): the book is a list of chapter
        // tracks, and positionInformation says which chapter to resume and how far in.
        val body = """<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body>
            <getMetadataResponse><getMetadataResult><index>0</index><count>20</count><total>53</total>
              <positionInformation>
                <id>refchapter:B002V1OF70_119953071_com_119000_1795000</id>
                <index>0</index><offsetMillis>689440</offsetMillis>
              </positionInformation>
              <mediaMetadata><id>refchapter:B002V1OF70_119953071_com_0_61000</id>
                <itemType>track</itemType><title>Opening Credits</title></mediaMetadata>
            </getMetadataResult></getMetadataResponse></soap:Body></soap:Envelope>"""
        val resume = parsePositionInformation(body)!!
        assertEquals("refchapter:B002V1OF70_119953071_com_119000_1795000", resume.id)
        assertEquals(0, resume.index)
        assertEquals(689440L, resume.offsetMillis)
        // The chapters parse as ordinary playable tracks.
        assertEquals("Opening Credits", parseItems(body).items.single().title)
        assertFalse(parseItems(body).items.single().container)
    }

    @Test fun `no positionInformation means no resume point`() {
        val body = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
            <getMetadataResponse><getMetadataResult><total>1</total>
              <mediaMetadata><id>x</id><itemType>track</itemType><title>t</title></mediaMetadata>
            </getMetadataResult></getMetadataResponse></s:Body></s:Envelope>"""
        assertNull(parsePositionInformation(body))
    }

    @Test fun `the service type is read from the type list for a cdudn`() {
        // 7943 = 31 * 256 + 7, Qobuz. Absent when the type list does not mention the service.
        val descriptors = """<Services SchemaVersion="1">
            <Service Id="31" Name="Qobuz" SecureUri="https://q/smapi"><Policy Auth="DeviceLink"/></Service>
            <Service Id="999" Name="Nameless" SecureUri="https://n/smapi"><Policy Auth="Anonymous"/></Service>
        </Services>"""
        val services = parseServices(descriptors, "7943,0").associateBy { it.id }
        assertEquals(7943L, services["31"]!!.serviceType)
        assertEquals("not in the type list", null, services["999"]!!.serviceType)
    }
}
