package com.rahga.x2rock.lan

import com.rahga.x2rock.smapi.Category
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
 * [SonosHousehold.searchableServices] and the search/browse/queue around it, end to end over the
 * same three fakes [RatingsTest] uses: the player's socket, `ListAvailableServices` over cleartext
 * UPnP, and a service's manifest/map/SMAPI endpoint. The token comes from a **sealed envelope**
 * handed to the injected `accountCapture`, so the real decrypt runs — the one thing the emulator
 * and this fake cannot capture off a player.
 *
 * SMAPI bodies are the captured shapes from `SmapiSearchTest` (Deezer's trackMetadata nesting),
 * not invented. The envelope is sealed with the same key derivation `StoredAccounts` decrypts
 * with, so no real token appears here.
 */
class ServiceSearchTest {

    private companion object {
        const val QOBUZ = "31"       // app-link by policy; usable only via the stored token
        const val RADIO = "894"      // anonymous; usable with no credential
        const val APPLE = "204"      // has a token, but excluded (searched via iTunes)

        // 31*256+7 = 7943 (Qobuz), 204*256+7 = 52231 (Apple). The service type list a cdudn needs.
        const val TYPE_LIST = "7943,52231"

        /** A presentation map with one Tracks search category. */
        const val MAP = """<Presentation><PresentationMap type="Search"><Match>
            <SearchCategories><Category id="tracks" mappedId="track"/></SearchCategories>
        </Match></PresentationMap></Presentation>"""

        fun searchResponse() =
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>""" +
                """<searchResponse><searchResult><index>0</index><count>1</count><total>1</total>""" +
                """<mediaMetadata><id>tr-flac:536421002</id><itemType>track</itemType><title>SICKO MODE</title>""" +
                """<trackMetadata><artist>Travis Scott</artist>""" +
                """<albumArtURI>https://cdn.example/cover.jpg</albumArtURI></trackMetadata>""" +
                """</mediaMetadata></searchResult></searchResponse></s:Body></s:Envelope>"""

        /** The `artist` category's answer: the track above again, then an artist station. */
        fun artistResponse() =
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>""" +
                """<searchResponse><searchResult><index>0</index><count>2</count><total>2</total>""" +
                """<mediaMetadata><id>tr-flac:536421002</id><itemType>track</itemType><title>SICKO MODE</title></mediaMetadata>""" +
                """<mediaMetadata><id>artist_radio.1</id><itemType>program</itemType><title>Travis Scott</title></mediaMetadata>""" +
                """</searchResult></searchResponse></s:Body></s:Envelope>"""

        fun metadataResponse() =
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>""" +
                """<getMetadataResponse><getMetadataResult><index>0</index><count>1</count><total>1</total>""" +
                """<mediaCollection><id>album:9</id><itemType>album</itemType><title>ASTROWORLD</title></mediaCollection>""" +
                """</getMetadataResult></getMetadataResponse></s:Body></s:Envelope>"""

        fun mediaUriResponse() =
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>""" +
                """<getMediaURIResponse><getMediaURIResult>http://stream.example/jazz.pls</getMediaURIResult>""" +
                """</getMediaURIResponse></s:Body></s:Envelope>"""

    }

    private lateinit var fake: FakePlayer
    private lateinit var upnp: MockWebServer
    private lateinit var service: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold

    private val smapiCalls = CopyOnWriteArrayList<String>()
    private val avTransport = CopyOnWriteArrayList<String>()

    /** Whether the player refuses `SetAVTransportURI` with a UPnP fault, as one refusing a source would. */
    @Volatile private var refuseSource = false

    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        service = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/manifest" -> MockResponse().setBody("""{"presentationMap":{"uri":"${url("/map")}"}}""")
                    "/map" -> MockResponse().setBody(MAP)
                    "/smapi" -> {
                        val body = request.body.readUtf8().also { smapiCalls += it }
                        MockResponse().setBody(
                            when {
                                "<getMediaURI" in body -> mediaUriResponse()
                                "<getMetadata" in body -> metadataResponse()
                                "<id>artist</id>" in body -> artistResponse()
                                else -> searchResponse()
                            }
                        )
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        upnp = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/MusicServices/Control" -> MockResponse().setBody(listAvailableServices())
                    "/MediaRenderer/AVTransport/Control" -> {
                        val body = request.body.readUtf8().also { avTransport += it }
                        if ("SetAVTransportURI" in body && refuseSource) {
                            MockResponse().setResponseCode(500).setBody(
                                """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>""" +
                                    """<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>""" +
                                    """<UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>714</errorCode></UPnPError>""" +
                                    """</detail></s:Fault></s:Body></s:Envelope>"""
                            )
                        } else {
                            val resp = if ("SetAVTransportURI" in body) {
                                """<u:SetAVTransportURIResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"/>"""
                            } else {
                                """<u:AddURIToQueueResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">""" +
                                    """<FirstTrackNumberEnqueued>1</FirstTrackNumberEnqueued>""" +
                                    """<NumTracksAdded>1</NumTracksAdded><NewQueueLength>1</NewQueueLength>""" +
                                    """</u:AddURIToQueueResponse>"""
                            }
                            MockResponse().setBody("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>$resp</s:Body></s:Envelope>""")
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        scope = CoroutineScope(SupervisorJob())
        val book = PlayerAddressBook()
        // One Qobuz account, one Apple account (both with tokens); the radio service carries none.
        val envelope = com.rahga.x2rock.smapi.TestEnvelope.seal(
            """<ThirdPartyMediaServers>
                <MediaServer UDN="SA_RINCON7943_X" SerialNum0="14" Token0="qb-token" Key0="qb-key"
                  Nickname0="Qb1" Username0="X_#Svc7943-6c0ffea0-Token"/>
                <MediaServer UDN="SA_RINCON52231_X" SerialNum0="19" Token0="apple-token" Key0=""
                  Nickname0="Richard"/>
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

    private fun listAvailableServices(): String {
        val smapi = service.url("/smapi")
        val manifest = service.url("/manifest")
        val descriptors = """<Services SchemaVersion="1">""" +
            """<Service Id="$QOBUZ" Name="Qobuz" Uri="$smapi" SecureUri="$smapi">""" +
            """<Policy Auth="AppLink" PollInterval="30"/><Manifest Uri="$manifest"/></Service>""" +
            """<Service Id="$RADIO" Name="80er-Radio" Uri="$smapi" SecureUri="$smapi">""" +
            """<Policy Auth="Anonymous" PollInterval="0"/><Manifest Uri="$manifest"/></Service>""" +
            """<Service Id="$APPLE" Name="Apple Music" Uri="$smapi" SecureUri="$smapi">""" +
            """<Policy Auth="AppLink" PollInterval="30"/></Service>""" +
            """</Services>"""
        val escaped = descriptors.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        return """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>""" +
            """<u:ListAvailableServicesResponse xmlns:u="urn:schemas-upnp-org:service:MusicServices:1">""" +
            """<AvailableServiceDescriptorList>$escaped</AvailableServiceDescriptorList>""" +
            """<AvailableServiceTypeList>$TYPE_LIST</AvailableServiceTypeList>""" +
            """<AvailableServiceListVersion>RINCON:1</AvailableServiceListVersion>""" +
            """</u:ListAvailableServicesResponse></s:Body></s:Envelope>"""
    }

    private fun connected(): String = runBlocking {
        household.connect(fake.seed)
        withTimeout(5_000) { household.state.first { it.connected } }
        fake.groupId(household)
    }

    private fun qobuz() = runBlocking {
        household.searchableServices().first { it.service.id == QOBUZ }
    }

    @Test fun `the services list is anonymous plus credentialed, and excludes Apple`() = runBlocking {
        connected()
        val services = household.searchableServices()
        val byId = services.associateBy { it.service.id }
        assertTrue("Qobuz is usable through its stored token", byId.containsKey(QOBUZ))
        assertTrue("the radio service is usable anonymously", byId.containsKey(RADIO))
        assertNull("Apple is searched via iTunes, not listed here", byId[APPLE])
        assertEquals("Qb1", byId.getValue(QOBUZ).nickname)
        assertEquals("sn_14", byId.getValue(QOBUZ).accountId)
    }

    @Test fun `a category that is two searches lists the second's hits after the first's, once each`() = runBlocking {
        connected()
        val page = household.searchService(qobuz(), Category("stations", "track", thenMappedId = "artist"), term = "x")
        assertEquals(listOf("SICKO MODE", "Travis Scott"), page.items.map { it.title })
        assertEquals("both categories were asked", 2, smapiCalls.count { "<search" in it })
    }

    @Test fun `a search carries the stored token and parses the hits`() = runBlocking {
        connected()
        val page = household.searchService(qobuz(), Category("tracks", "track"), term = "sicko")
        assertEquals(1, page.items.size)
        assertEquals("SICKO MODE", page.items[0].title)
        assertEquals("Travis Scott", page.items[0].summary)
        assertFalse(page.items[0].container)
        // The household's own token rode in the loginToken header — not sent, this would be anon.
        val search = smapiCalls.first { "<search" in it }
        assertTrue(search, "<token>qb-token</token>" in search && "<key>qb-key</key>" in search)
    }

    @Test fun `a browse returns the container's children`() = runBlocking {
        connected()
        val page = household.browseService(qobuz(), id = "root")
        assertEquals(1, page.items.size)
        assertTrue("an album is a place to open", page.items[0].container)
        assertEquals("ASTROWORLD", page.items[0].title)
    }

    @Test fun `queuing an album sends the cpcontainer uri and the account's cdudn`() = runBlocking {
        val groupId = connected()
        val album = com.rahga.x2rock.smapi.Item(
            id = "album:9", title = "ASTROWORLD", itemType = "album", summary = null, artUrl = null, container = true,
        )
        household.queueServiceItem(groupId, qobuz(), album)
        val body = avTransport.first { "AddURIToQueue" in it }
        // The URI expands the container; the DIDL's cdudn names Qobuz's account by its selector.
        assertTrue(body, "x-rincon-cpcontainer:1004206calbum%3a9?sid=31&amp;flags=8300&amp;sn=14" in body)
        assertTrue(body, "SA_RINCON7943_X_#Svc7943-6c0ffea0-Token" in body)
    }

    @Test fun `a stream plays through a session from its media URI, not loadContent`() = runBlocking {
        val groupId = connected()
        val radio = household.searchableServices().first { it.service.id == RADIO }
        val station = com.rahga.x2rock.smapi.Item(
            id = "s249973", title = "Smooth Jazz", itemType = "stream", summary = null, artUrl = null, container = false,
        )
        val started = async(Dispatchers.Default) {
            household.playServiceItem(groupId, radio, station)
        }
        fake.awaitCommand(5_000) { it.get("command")?.asString == "createSession" }
        fake.awaitCommand(5_000) { it.get("command")?.asString == "loadStreamUrl" }
        fake.pushPlaybackStatus(groupId, "PLAYBACK_STATE_PLAYING")
        started.await()
        // The URL came from getMediaURI, and the station did not go down the loadContent path.
        assertTrue(smapiCalls.any { "<getMediaURI" in it })
        assertEquals(0, fake.commandsNamed("loadContent"))
        assertEquals("http://stream.example/jazz.pls", fake.lastCommandBody("loadStreamUrl")!!.get("streamUrl").asString)
    }

    @Test fun `a radio program plays as the room source, not loadContent or a stream`() = runBlocking {
        val groupId = connected()
        // A program (a channel) uses the credentialed service here only for its service type → cdudn.
        val program = com.rahga.x2rock.smapi.Item(
            id = "channel:5:4:resume", title = "Main Mix", itemType = "program", summary = null, artUrl = null, container = false,
        )
        val started = async(Dispatchers.Default) { household.playServiceItem(groupId, qobuz(), program) }
        fake.awaitCommand(5_000) { it.get("command")?.asString == "play" }
        fake.pushPlaybackStatus(groupId, "PLAYBACK_STATE_PLAYING")
        started.await()
        val set = avTransport.first { "SetAVTransportURI" in it }
        // x-sonosapi-radio with flags=0, the colon escaped; an audioBroadcast DIDL; no queue, no stream.
        assertTrue(set, "x-sonosapi-radio:channel%3a5%3a4%3aresume?sid=31" in set)
        assertTrue("an audioBroadcast, not a track", "object.item.audioItem.audioBroadcast" in set)
        assertEquals(0, fake.commandsNamed("loadContent"))
        assertTrue("a program is not resolved by getMediaURI", smapiCalls.none { "<getMediaURI" in it })
        assertTrue("a program is not enqueued", avTransport.none { "AddURIToQueue" in it })
    }

    @Test fun `a program the player refuses as a source is streamed instead`() = runBlocking {
        val groupId = connected()
        refuseSource = true
        val program = com.rahga.x2rock.smapi.Item(
            id = "channel:5:4:resume", title = "Main Mix", itemType = "program", summary = null, artUrl = null, container = false,
        )
        val started = async(Dispatchers.Default) { household.playServiceItem(groupId, qobuz(), program) }
        fake.awaitCommand(5_000) { it.get("command")?.asString == "loadStreamUrl" }
        fake.pushPlaybackStatus(groupId, "PLAYBACK_STATE_PLAYING")
        started.await()
        // The source was asked for and refused; the stream came from getMediaURI, and no play was
        // pressed on the refused source.
        assertTrue("the radio source was tried first", avTransport.any { "SetAVTransportURI" in it })
        assertTrue(smapiCalls.any { "<getMediaURI" in it })
        assertEquals("http://stream.example/jazz.pls", fake.lastCommandBody("loadStreamUrl")!!.get("streamUrl").asString)
        assertEquals(0, fake.commandsNamed("play"))
    }

    @Test fun `queuing an artist is refused before anything is sent`() = runBlocking {
        val groupId = connected()
        val artist = com.rahga.x2rock.smapi.Item(
            id = "artist:1", title = "Travis Scott", itemType = "artist", summary = null, artUrl = null, container = true,
        )
        val failed = runCatching { household.queueServiceItem(groupId, qobuz(), artist) }.isFailure
        assertTrue("an artist holds no tracks to enqueue", failed)
        assertTrue("nothing was sent to the player", avTransport.none { "AddURIToQueue" in it })
    }
}
