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
}
