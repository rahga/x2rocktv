package com.rahga.x2rock.lan

import com.google.gson.JsonObject
import com.rahga.x2rock.smapi.Thumb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [SonosHousehold.ratingState] and [SonosHousehold.rate] end to end: the player over its
 * socket, `ListAvailableServices` over cleartext UPnP, and the service's manifest,
 * presentation map and SMAPI endpoint — three fakes, because the path really does cross
 * three parties.
 *
 * The SMAPI bodies are x2rock's captures: iHeartRadio's real `NowPlayingRatings` block, and
 * `rateItem` answers as recorded rating a Custom station on 2026-09-12. The track id on an
 * iHeartRadio track is *derived* from the captured Plex `metadataStatus` by swapping its
 * `id`, the same way `FakePlayer.groupedTopology` derives a regroup: no real Custom-station
 * capture is in this repo, and the shape is the one x2rock recorded for one.
 */
class RatingsTest {

    private companion object {
        const val IHEART = "6"
        const val PLEX = "212"
        const val NO_RATINGS = "254"
        // A device-link service the household holds a token for — iHeartRadio's real shape here.
        const val IHEART_LINKED = "1517"
        // A credentialed service whose ratings are favourite/skip, not thumbs — Deezer's shape.
        const val FAVOURITE_SVC = "2"

        const val IHEART_RATINGS = """<Presentation>
            <PresentationMap type="NowPlayingRatings">
                <Match propname="thumbs_up_selected" value="5"><Ratings>
                    <Rating AutoSkip="NEVER" Id="5" StringId="THUMBS_UP_TIP"/>
                    <Rating AutoSkip="NEVER" Id="1" StringId="THUMBS_DOWN_TIP"/>
                </Ratings></Match>
                <Match propname="thumbs_down_selected" value="1"><Ratings>
                    <Rating AutoSkip="NEVER" Id="55" StringId="THUMBS_UP_TIP"/>
                    <Rating AutoSkip="NEVER" Id="11" StringId="THUMBS_DOWN_TIP"/>
                </Ratings></Match>
                <Match propname="unselected" value="0"><Ratings>
                    <Rating AutoSkip="NEVER" Id="555" StringId="THUMBS_UP_TIP"/>
                    <Rating AutoSkip="NEVER" Id="111" StringId="THUMBS_DOWN_TIP"/>
                </Ratings></Match>
            </PresentationMap>
        </Presentation>"""

        /** A presentation map with search categories and nothing else, the ordinary case. */
        const val PLAIN_MAP = """<Presentation><PresentationMap type="Search"><Match>
            <SearchCategories><Category id="stations" mappedId="search:station"/></SearchCategories>
        </Match></PresentationMap></Presentation>"""

        /**
         * Deezer's real shape (read off the household 2026-10-06): a `NowPlayingRatings` map that is
         * favourite/skip, not thumbs — no `UP`/`DOWN` to send. It publishes ratings, so the thumbs
         * must be withheld by something other than "publishes none".
         */
        const val FAVOURITE_MAP = """<Presentation>
            <PresentationMap type="NowPlayingRatings">
                <Match propname="ISFAVORITE" value="0"><Ratings>
                    <Rating Id="3" StringId="SKIP_TRACK"/>
                    <Rating Id="1" StringId="SAVE_TRACK"/>
                </Ratings></Match>
                <Match propname="ISFAVORITE" value="1"><Ratings>
                    <Rating Id="3" StringId="SKIP_TRACK"/>
                    <Rating Id="0" StringId="DELETE_TRACK"/>
                </Ratings></Match>
            </PresentationMap>
        </Presentation>"""

        fun rateItemResponse(shouldSkip: Boolean) =
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>""" +
                """<rateItemResponse xmlns="http://www.sonos.com/Services/1.1"><rateItemResult>""" +
                """<shouldSkip>$shouldSkip</shouldSkip><messageStringId>THUMBS_UP_SUCCESS</messageStringId>""" +
                """</rateItemResult></rateItemResponse></s:Body></s:Envelope>"""

        fun extendedMetadata(propname: String, value: String) =
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>""" +
                """<getExtendedMetadataResponse xmlns="http://www.sonos.com/Services/1.1">""" +
                """<getExtendedMetadataResult><dynamic>""" +
                """<property><name>$propname</name><value>$value</value></property>""" +
                """<property><name>favorite</name><value>false</value></property>""" +
                """</dynamic></getExtendedMetadataResult></getExtendedMetadataResponse></s:Body></s:Envelope>"""
    }

    private lateinit var fake: FakePlayer
    private lateinit var upnp: MockWebServer
    private lateinit var service: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold

    /** What `getExtendedMetadata` reports the track's state as. */
    @Volatile private var state = "unselected" to "0"
    @Volatile private var shouldSkip = false
    /** Every SOAP body the service was sent, in order. */
    private val smapiCalls = CopyOnWriteArrayList<String>()

    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        service = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/manifest/ratings" -> json(url("/map/ratings"))
                    "/manifest/plain" -> json(url("/map/plain"))
                    "/manifest/favourite" -> json(url("/map/favourite"))
                    "/map/ratings" -> MockResponse().setBody(IHEART_RATINGS)
                    "/map/plain" -> MockResponse().setBody(PLAIN_MAP)
                    "/map/favourite" -> MockResponse().setBody(FAVOURITE_MAP)
                    "/smapi" -> {
                        val body = request.body.readUtf8().also { smapiCalls += it }
                        MockResponse().setBody(
                            if ("<rateItem" in body) rateItemResponse(shouldSkip)
                            else extendedMetadata(state.first, state.second)
                        )
                    }
                    else -> MockResponse().setResponseCode(404)
                }

                fun json(mapUrl: Any) = MockResponse().setBody("""{"presentationMap":{"uri":"$mapUrl"}}""")
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        upnp = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path == "/MusicServices/Control") MockResponse().setBody(listAvailableServices())
                    else MockResponse().setResponseCode(404)
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        scope = CoroutineScope(SupervisorJob())
        val book = PlayerAddressBook()
        // The household stores a token for the device-link service, read off a player — seeded here
        // so the real decrypt runs, as a TV would capture it. 1517 * 256 = 388352 is its UDN type.
        val envelope = com.rahga.x2rock.smapi.TestEnvelope.seal(
            """<ThirdPartyMediaServers>
               <MediaServer UDN="SA_RINCON388352_X" SerialNum0="15" Token0="ihr-token" Key0="ihr-key" Nickname0="iHeartRadio"/>
               <MediaServer UDN="SA_RINCON512_X" SerialNum0="16" Token0="dz-token" Key0="dz-key" Nickname0="Deezer"/>
               </ThirdPartyMediaServers>""",
            fake.householdId.substringBefore('.'),
        )
        household = SonosHousehold(
            scope = scope,
            addressBook = book,
            multicast = MulticastGate.None,
            client = LanHttp.client(book),
            internetClient = OkHttpClient(),
            port = fake.port,
            upnpPort = upnp.port,
            accountCapture = { envelope },
        )
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        fake.shutdown()
        upnp.shutdown()
        service.shutdown()
    }

    /**
     * The `MusicServices` reply as the SCPD defines it: the descriptors arrive XML-escaped
     * inside one element. iHeartRadio and the plain service are anonymous, as they are in the
     * real catalogue; Plex is app-link, as it is there too.
     */
    private fun listAvailableServices(): String {
        val smapi = service.url("/smapi")
        val descriptors = """<Services SchemaVersion="1">""" +
            """<Service Id="$IHEART" Name="iHeartRadio" Uri="$smapi" SecureUri="$smapi" ContainerType="MService">""" +
            """<Policy Auth="Anonymous" PollInterval="0"/><Manifest Uri="${service.url("/manifest/ratings")}"/></Service>""" +
            """<Service Id="$NO_RATINGS" Name="TuneIn" Uri="$smapi" SecureUri="$smapi" ContainerType="MService">""" +
            """<Policy Auth="Anonymous" PollInterval="0"/><Manifest Uri="${service.url("/manifest/plain")}"/></Service>""" +
            """<Service Id="$PLEX" Name="Plex" Uri="$smapi" SecureUri="$smapi" ContainerType="MService">""" +
            """<Policy Auth="AppLink" PollInterval="30"/><Manifest Uri="${service.url("/manifest/ratings")}"/></Service>""" +
            """<Service Id="$IHEART_LINKED" Name="iHeartRadio" Uri="$smapi" SecureUri="$smapi" ContainerType="MService">""" +
            """<Policy Auth="DeviceLink" PollInterval="30"/><Manifest Uri="${service.url("/manifest/ratings")}"/></Service>""" +
            """<Service Id="$FAVOURITE_SVC" Name="Deezer" Uri="$smapi" SecureUri="$smapi" ContainerType="MService">""" +
            """<Policy Auth="DeviceLink" PollInterval="30"/><Manifest Uri="${service.url("/manifest/favourite")}"/></Service>""" +
            """</Services>"""
        val escaped = descriptors.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        return """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>""" +
            """<u:ListAvailableServicesResponse xmlns:u="urn:schemas-upnp-org:service:MusicServices:1">""" +
            """<AvailableServiceDescriptorList>$escaped</AvailableServiceDescriptorList>""" +
            """<AvailableServiceTypeList>1543,2311</AvailableServiceTypeList>""" +
            """<AvailableServiceListVersion>RINCON_000000000000001400:1</AvailableServiceListVersion>""" +
            """</u:ListAvailableServicesResponse></s:Body></s:Envelope>"""
    }

    /** Connected, with the captured Plex track playing — or, given a service, that track re-homed there. */
    private fun playing(serviceId: String? = null): String = runBlocking {
        household.connect(
            fake.seed
        )
        withTimeout(5_000) { household.state.first { it.connected } }
        val groupId = fake.groupId(household)
        val metadata = FakePlayer.fixture("event.metadataStatus.json")
        if (serviceId != null) {
            metadata.getAsJsonObject("currentItem").getAsJsonObject("track").add("id", JsonObject().apply {
                addProperty("serviceId", serviceId)
                addProperty("objectId", "artist_radio_track.artist-2648-0")
                addProperty("accountId", "sn_15")
            })
        }
        fake.push("playbackMetadata:1", "metadataStatus", metadata.toString(), groupId)
        withTimeout(5_000) { household.groupStates.first { it[groupId]?.hasTrackId == true } }
        groupId
    }

    @Test fun `a rateable track is offered with the state its service reports`() = runBlocking {
        val groupId = playing(IHEART)

        state = "thumbs_up_selected" to "5"
        assertEquals(SonosHousehold.RatingState("iHeartRadio", Thumb.UP), household.ratingState(groupId))

        state = "thumbs_down_selected" to "1"
        assertEquals(Thumb.DOWN, household.ratingState(groupId)?.current)

        state = "unselected" to "0"
        assertEquals(Thumb.NONE, household.ratingState(groupId)?.current)
    }

    /**
     * The id depends on the state: up from unrated is `555`, up from already-down is `55`.
     * Asserting one state alone would pass against code that ignored the state entirely.
     */
    @Test fun `rating sends the id the track's current state offers`() = runBlocking {
        val groupId = playing(IHEART)

        state = "unselected" to "0"
        val outcome = household.rate(groupId, up = true)
        assertEquals("iHeartRadio", outcome.serviceName)
        assertTrue(smapiCalls.last(), "<rating>555</rating>" in smapiCalls.last())

        state = "thumbs_down_selected" to "1"
        household.rate(groupId, up = true)
        assertTrue(smapiCalls.last(), "<rating>55</rating>" in smapiCalls.last())

        household.rate(groupId, up = false)
        assertTrue(smapiCalls.last(), "<rating>11</rating>" in smapiCalls.last())
    }

    @Test fun `a service asking to skip moves the room on, and one that does not leaves it`() = runBlocking {
        val groupId = playing(IHEART)

        fake.clearHistory()
        assertFalse(household.rate(groupId, up = false).skipped)
        assertEquals(0, fake.commandsNamed("skipToNextTrack"))

        shouldSkip = true
        assertTrue(household.rate(groupId, up = false).skipped)
        fake.awaitCommand("skipToNextTrack")
        Unit
    }

    /**
     * The captured Plex track, unaltered. Its id is real and Plex needs an account, so it
     * must stop before the internet — asserted by the service seeing nothing at all, because
     * a null alone would also come from a fetch that happened and found something wrong.
     */
    @Test fun `a track on a service needing an account is not offered, and nothing leaves the LAN`() = runBlocking {
        val groupId = playing()
        assertNull(household.ratingState(groupId))
        assertEquals(0, service.requestCount)
    }

    /** Never asked for the track's state: there is nothing it could be matched against. */
    @Test fun `a service publishing no ratings is not offered, and its track is never looked up`() = runBlocking {
        val groupId = playing(NO_RATINGS)
        assertNull(household.ratingState(groupId))
        assertTrue("a manifest was not even fetched", service.requestCount > 0)
        assertTrue(smapiCalls.toString(), smapiCalls.isEmpty())
    }

    /**
     * 0.1a: a device-link service (iHeartRadio) is rated with the household's **stored** token
     * rather than refused. Before the household-token read, a non-anonymous service answered
     * "needs an account linked" and the thumbs never appeared; now the token read off a player
     * reaches it, and rides both the state read and the rate call.
     */
    @Test fun `a device-link service is rated with the household's stored token`() = runBlocking {
        val groupId = playing(IHEART_LINKED)

        state = "unselected" to "0"
        assertEquals(
            SonosHousehold.RatingState("iHeartRadio", Thumb.NONE),
            household.ratingState(groupId),
        )
        household.rate(groupId, up = true)
        val rated = smapiCalls.last { "<rateItem" in it }
        assertTrue(rated, "<rating>555</rating>" in rated)
        assertTrue("the stored token rode the rate call", "<token>ihr-token</token>" in rated && "<key>ihr-key</key>" in rated)
    }

    /**
     * A service whose `NowPlayingRatings` is favourite/skip rather than thumbs (Deezer) is offered
     * as what it is — a heart and a ban, as the Sonos app draws it (2026-10-08) — never as thumbs,
     * which would only fail on the press. The heart reads set from the track's own state.
     */
    @Test fun `a favourite-not-thumbs service is offered as a heart, set from the track's state`() = runBlocking {
        val groupId = playing(FAVOURITE_SVC)
        state = "ISFAVORITE" to "0"
        assertEquals(
            SonosHousehold.RatingState("Deezer", Thumb.NONE, SonosHousehold.RatingStyle.FAVORITE),
            household.ratingState(groupId),
        )
        state = "ISFAVORITE" to "1"
        assertEquals(Thumb.UP, household.ratingState(groupId)?.current)
    }

    /** The heart toggles — save on a track that is not a favourite, delete on one that is — and the ban skips. */
    @Test fun `the heart saves or deletes by the track's state, and the ban sends the skip`() = runBlocking {
        val groupId = playing(FAVOURITE_SVC)
        state = "ISFAVORITE" to "0"
        assertEquals("SAVE_TRACK", household.rate(groupId, up = true).stringId)
        assertTrue(smapiCalls.last(), "<rating>1</rating>" in smapiCalls.last())

        state = "ISFAVORITE" to "1"
        assertEquals("DELETE_TRACK", household.rate(groupId, up = true).stringId)
        assertTrue(smapiCalls.last(), "<rating>0</rating>" in smapiCalls.last())

        assertEquals("SKIP_TRACK", household.rate(groupId, up = false).stringId)
        assertTrue(smapiCalls.last(), "<rating>3</rating>" in smapiCalls.last())
    }
}
