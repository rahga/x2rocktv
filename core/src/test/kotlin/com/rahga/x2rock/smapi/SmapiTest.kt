package com.rahga.x2rock.smapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing tests for the SMAPI wire shapes, ported from x2rock's own `sonos/smapi.rs` test
 * module (its `docs/openphonos-ratings-findings.md` records the research) rather than
 * invented — [IHEART_RATINGS] in particular is the same real block that document
 * records fetching verbatim off a real household's iHeartRadio manifest on 2026-09-12, and
 * the `rateItem`/`getExtendedMetadata` bodies below are the same captured responses its
 * `sonos/smapi.rs` test module carries.
 */
class SmapiTest {

    private companion object {
        /**
         * iHeartRadio's real `NowPlayingRatings` block, trimmed of icon sub-elements
         * (irrelevant here) but otherwise verbatim — fetched 2026-09-12 from the manifest at
         * `https://cf.ws.sonos.com/p/m/36760215-347b-405c-a07e-90b4dff74b92`. Three states,
         * each with its own pair of ids — the fact [RatingsMatch.find] exists to handle.
         */
        const val IHEART_RATINGS = """<Presentation>
            <PresentationMap type="NowPlayingRatings">
                <Match propname="thumbs_up_selected" value="5">
                    <Ratings>
                        <Rating AutoSkip="NEVER" Id="5" StringId="THUMBS_UP_TIP" OnSuccessStringId="THUMBS_UP_SUCCESS"/>
                        <Rating AutoSkip="NEVER" Id="1" StringId="THUMBS_DOWN_TIP" OnSuccessStringId="THUMBS_DOWN_SUCCESS"/>
                    </Ratings>
                </Match>
                <Match propname="thumbs_down_selected" value="1">
                    <Ratings>
                        <Rating AutoSkip="NEVER" Id="55" StringId="THUMBS_UP_TIP" OnSuccessStringId="THUMBS_UP_SUCCESS"/>
                        <Rating AutoSkip="NEVER" Id="11" StringId="THUMBS_DOWN_TIP" OnSuccessStringId="THUMBS_DOWN_SUCCESS"/>
                    </Ratings>
                </Match>
                <Match propname="unselected" value="0">
                    <Ratings>
                        <Rating AutoSkip="NEVER" Id="555" StringId="THUMBS_UP_TIP" OnSuccessStringId="THUMBS_UP_SUCCESS"/>
                        <Rating AutoSkip="NEVER" Id="111" StringId="THUMBS_DOWN_TIP" OnSuccessStringId="THUMBS_DOWN_SUCCESS"/>
                    </Ratings>
                </Match>
            </PresentationMap>
        </Presentation>"""

        const val DESCRIPTORS = """<Services SchemaVersion="1">
            <Service Id="254" Name="TuneIn" Uri="http://legato/x" SecureUri="https://legato/x"
                     ContainerType="MService" Capabilities="29364801">
              <Policy Auth="Anonymous" PollInterval="0"/>
              <Manifest Version="259" Uri="https://cdn/m/tunein"/>
            </Service>
            <Service Id="284" Name="YouTube Music" Uri="https://ytm/x" ContainerType="MService">
              <Policy Auth="AppLink" PollInterval="60"/>
            </Service>
            <Service Id="200" Name="Bandcamp" Uri="https://bandcamp/smapi" ContainerType="MService">
              <Policy Auth="DeviceLink" PollInterval="0"/>
            </Service>
            <Service Id="999" Name="Broken"/>
          </Services>"""
    }

    // ---------------------------------------------------------------- parseServices

    @Test fun `an anonymous service carries its manifest uri and no account requirement`() {
        val tunein = parseServices(DESCRIPTORS).first { it.name == "TuneIn" }
        assertEquals(Auth.ANONYMOUS, tunein.auth)
        assertEquals("https://legato/x", tunein.uri)
        assertEquals("https://cdn/m/tunein", tunein.manifestUri)
    }

    @Test fun `secureUri is preferred over the plain one when both are present`() {
        val tunein = parseServices(DESCRIPTORS).first { it.name == "TuneIn" }
        assertEquals("https://legato/x", tunein.uri)
    }

    @Test fun `device-link and app-link policies are told apart`() {
        val services = parseServices(DESCRIPTORS)
        assertEquals(Auth.DEVICE_LINK, services.first { it.name == "Bandcamp" }.auth)
        assertEquals(Auth.APP_LINK, services.first { it.name == "YouTube Music" }.auth)
    }

    @Test fun `a service with no uri at all is skipped rather than crashing the parse`() {
        val services = parseServices(DESCRIPTORS)
        assertTrue(services.none { it.name == "Broken" })
    }

    // ---------------------------------------------------------------- parseRatingsMap

    @Test fun `iHeart's three rating states each carry their own id pair`() {
        val matches = parseRatingsMap(IHEART_RATINGS)
        assertEquals(3, matches.size)

        val unselected = matches.first { it.propname == "unselected" }
        assertEquals("0", unselected.value)
        assertEquals(2, unselected.ratings.size)

        // The point of the whole exercise: "thumbs up" is id 555 while unrated, 5 while
        // already down, and never the same id twice — a caller that cached "5 means up"
        // from one track would send the wrong id on the next.
        assertEquals("555", RatingsMatch.find(matches, "unselected", "0", up = true)?.id)
        assertEquals("55", RatingsMatch.find(matches, "thumbs_down_selected", "1", up = true)?.id)
        assertEquals("5", RatingsMatch.find(matches, "thumbs_up_selected", "5", up = true)?.id)
    }

    @Test fun `find reads direction from the string id, not position`() {
        val matches = parseRatingsMap(IHEART_RATINGS)
        val down = RatingsMatch.find(matches, "unselected", "0", up = false)
        assertEquals("111", down?.id)
        assertTrue(down!!.stringId.uppercase().contains("DOWN"))
    }

    @Test fun `find is none for a property extended metadata never reported`() {
        // The gate a Live broadcast's track falls through: its getExtendedMetadata reports
        // none of these three properties at all, so nothing here matches — no special-casing
        // "is this Live" needed anywhere in this function.
        val matches = parseRatingsMap(IHEART_RATINGS)
        assertNull(RatingsMatch.find(matches, "some_other_property", "1", up = true))
    }

    @Test fun `a service with no ratings block publishes none`() {
        val plain = """<Presentation>
            <PresentationMap type="ArtWorkSizeMap">
                <Match><imageSizeMap/></Match>
            </PresentationMap>
        </Presentation>"""
        assertTrue(parseRatingsMap(plain).isEmpty())
    }

    @Test fun `the _v2 form Pandora uses is matched by prefix`() {
        val v2 = IHEART_RATINGS.replace("NowPlayingRatings", "NowPlayingRatings_v2")
        assertEquals(3, parseRatingsMap(v2).size)
    }

    // ---------------------------------------------------------------- parseRateResult

    @Test fun `a rateItem response carries shouldSkip and the message id`() {
        val body = """<rateItemResponse xmlns="http://www.sonos.com/Services/1.1">
            <rateItemResult>
                <shouldSkip>true</shouldSkip>
                <messageStringId>THUMBS_DOWN_SUCCESS</messageStringId>
            </rateItemResult>
        </rateItemResponse>"""
        val result = parseRateResult(body)
        assertEquals(true, result.shouldSkip)
        assertEquals("THUMBS_DOWN_SUCCESS", result.messageStringId)
    }

    @Test fun `iHeart's real response reports shouldSkip explicitly as false`() {
        // Captured live against a real household's iHeartRadio account (2026-09-12), rating
        // an actual Custom/Artist-Radio track: not absent, as `AutoSkip="NEVER"` alone might
        // suggest, but an explicit `false`. Confirms shouldSkip (the live answer) is read
        // separately from AutoSkip (the declared policy).
        val body = """<rateItemResponse xmlns="http://www.sonos.com/Services/1.1">
            <rateItemResult>
                <shouldSkip>false</shouldSkip>
                <messageStringId>THUMBS_UP_SUCCESS</messageStringId>
            </rateItemResult>
        </rateItemResponse>"""
        val result = parseRateResult(body)
        assertEquals(false, result.shouldSkip)
        assertEquals("THUMBS_UP_SUCCESS", result.messageStringId)
    }

    @Test fun `a rateItem response with neither field is not an error`() {
        val result = parseRateResult("<rateItemResponse><rateItemResult/></rateItemResponse>")
        assertNull(result.shouldSkip)
        assertNull(result.messageStringId)
    }

    // ---------------------------------------------------------------- parseDynamicProperties

    @Test fun `extended metadata dynamic properties parse into pairs, in order`() {
        val body = """<getExtendedMetadataResponse>
            <getExtendedMetadataResult>
                <dynamic>
                    <property><name>thumbs_up_selected</name><value>5</value></property>
                    <property><name>favorite</name><value>false</value></property>
                </dynamic>
            </getExtendedMetadataResult>
        </getExtendedMetadataResponse>"""
        val props = parseDynamicProperties(body)
        assertEquals(listOf("thumbs_up_selected" to "5", "favorite" to "false"), props)
    }

    @Test fun `a response with no dynamic block reports no properties`() {
        // Not itself a captured Live-broadcast response — a Live track's Control API id is
        // already absent (verified on real hardware, 2026-09-12), so this is never reached
        // without one. This just pins the shape: a response naming nothing dynamic parses to
        // an empty list rather than an error.
        val body = """<getExtendedMetadataResponse>
            <getExtendedMetadataResult><mediaMetadata/></getExtendedMetadataResult>
        </getExtendedMetadataResponse>"""
        assertTrue(parseDynamicProperties(body).isEmpty())
    }

    private fun captured(name: String) = com.rahga.x2rock.lan.FakePlayer.fixtureText(name)

    /** Captured off Deezer, 2026-10-08: ids in `trackMetadata`, a "Mix" `relatedPlay`, `ISFAVORITE` set. */
    @Test fun `a Deezer track's details name its artist, album, radio and favourite state`() {
        val d = parseItemDetails(captured("getExtendedMetadata.deezer.track.xml"))
        assertEquals("artist-4495513", d.artistId)
        assertEquals("Travis Scott", d.artist)
        assertEquals("album-469682765", d.albumId)
        assertEquals("UTOPIA", d.album)
        assertEquals("radio-track-2386586085", d.radio?.id)
        assertEquals("program", d.radio?.itemType)
        assertEquals(false, d.radio?.container)
        assertTrue(d.canFavorite)
        assertEquals(true, d.favorite)
    }

    /** Qobuz says a track can be a favourite but not whether it is one, and offers no radio. */
    @Test fun `a Qobuz track's details carry no favourite state and no radio`() {
        val d = parseItemDetails(captured("getExtendedMetadata.qobuz.track.xml"))
        assertEquals("artist:42031", d.artistId)
        assertEquals("album:5414939963827", d.albumId)
        assertEquals(null, d.radio)
        assertTrue(d.canFavorite)
        assertEquals(null, d.favorite)
    }

    @Test fun `a Saavn track's details offer its radio but no favourite`() {
        val d = parseItemDetails(captured("getExtendedMetadata.saavn.track.xml"))
        assertEquals("ARTIST:16756017", d.artistId)
        assertEquals("Piya Bedardi Radio", d.radio?.title)
        assertEquals(false, d.canFavorite)
    }

    /** Sonos Radio's own page, captured from its browse endpoint 2026-10-08: shelves of stations. */
    @Test fun `a browse endpoint's page is its shelves, each with its stations`() {
        val shelves = parseShelves(captured("sonosradio.browse.json"))
        assertEquals("Trending Now", shelves.first().title)
        val hitList = shelves.first().items.first { it.title == "Hit List" }
        // The id Sonos Radio's SMAPI search returns for the same station, so it plays the same way.
        assertEquals("sonos:2997", hitList.id)
        assertEquals("program", hitList.itemType)
        assertEquals(false, hitList.container)
        assertEquals(28, shelves.size)
    }

    /**
     * Browse Radio's four rows play nothing and open (canEnumerate): kept as containers, with the
     * path ids SMAPI's getMetadata answers. Left out until 2026-10-11, when no way to open them was known.
     */
    @Test fun `Browse Radio's rows are places to open`() {
        val browse = parseShelves(captured("sonosradio.browse.json")).single { it.title == "Browse Radio" }
        assertEquals(listOf("Music", "News & Talk", "Sports", "Locations"), browse.items.map { it.title })
        assertTrue(browse.items.all { it.container })
        assertEquals("/stations/en-US/US/EgdVUzpHOjIy", browse.items.first { it.title == "News & Talk" }.id)
        assertEquals("no blank line under each", null, browse.items.first().summary)
    }

    /** What opening News & Talk answers, captured anonymously off Sonos Radio's SMAPI: stations to play. */
    @Test fun `a Browse Radio row opens on stations`() {
        val page = parseItems(captured("smapi.sonosradio.newsTalk.xml"))
        assertEquals(5637, page.total)
        assertTrue(page.items.isNotEmpty() && page.items.none { it.container })
        assertEquals("stream", page.items.first().itemType)
    }

    /**
     * Spotify's page, fetched with the account's token (captured 2026-10-11, four of its 22 views):
     * no policies anywhere, so the type decides — albums, playlists and artists are places to open,
     * by ids SMAPI's getMetadata answers.
     */
    @Test fun `Spotify's page is shelves of albums and playlists to open`() {
        val shelves = parseShelves(captured("spotify.browse.json"))
        assertEquals("More like Tyler, The Creator", shelves.first().title)
        val album = shelves.single { it.title == "Recommended for today" }.items.first()
        assertEquals("spotify:album:3MJGcgbroIjiO90yi7RMhy", album.id)
        assertEquals("album", album.itemType)
        assertTrue("an album opens", album.container)
        assertEquals("Jack Harlow", album.summary)
        assertTrue(shelves.flatMap { it.items }.all { it.container })
    }

    /**
     * A JSON `null` where a string is expected throws in Gson; the captured page with one row's art
     * and summary nulled keeps that row and every other, rather than throwing the page away.
     */
    @Test fun `a null field costs nothing but itself`() {
        val page = com.google.gson.JsonParser.parseString(captured("spotify.browse.json")).asJsonObject
        val row = page.getAsJsonArray("views").map { it.asJsonObject }
            .first { it.getAsJsonObject("content").getAsJsonObject("container").get("name").asString == "Recommended for today" }
            .getAsJsonArray("items").first().asJsonObject.getAsJsonObject("content").getAsJsonObject("container")
        row.add("imageUrl", com.google.gson.JsonNull.INSTANCE)
        row.add("summary", com.google.gson.JsonNull.INSTANCE)

        val shelves = parseShelves(page.toString())
        assertEquals(parseShelves(captured("spotify.browse.json")).map { it.items.size }, shelves.map { it.items.size })
        val album = shelves.single { it.title == "Recommended for today" }.items.first()
        assertEquals("spotify:album:3MJGcgbroIjiO90yi7RMhy", album.id)
        assertEquals(null, album.artUrl)
    }

    /**
     * Deezer's page (captured 2026-10-11, three of its nine views): albums say they both play and
     * open, and open; Flow's moods are programs, and play.
     */
    @Test fun `Deezer's page opens its albums and plays its Flow`() {
        val shelves = parseShelves(captured("deezer.browse.json"))
        assertEquals(listOf("Favorites", "Recently played", "Flow: play how you feel"), shelves.map { it.title })
        val album = shelves[1].items.first()
        assertEquals("album-262078", album.id)
        assertTrue("an album that can be played opens all the same", album.container)
        val flow = shelves[2].items.first()
        assertEquals("program", flow.itemType)
        assertEquals(false, flow.container)
    }

    /**
     * TuneIn's page, fetched with the household token from OkHttp (2026-10-12, two of its views):
     * its stations say `station`, playable and not openable, and are streams to play — the same ids
     * its SMAPI search answers as `stream`.
     */
    @Test fun `TuneIn's page stations are streams to play`() {
        val stations = parseShelves(captured("tunein.browse.json")).first().items
        assertTrue(stations.isNotEmpty())
        assertTrue("every one a stream", stations.all { it.itemType == "stream" && !it.container })
        assertEquals("Today's Hits", stations.first { it.id == "s242677" }.title)
    }
}
