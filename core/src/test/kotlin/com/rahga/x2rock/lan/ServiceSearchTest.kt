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
        const val MIXCLOUD = "181"   // has a token; its items play through the queue
        const val BANDCAMP = "157"   // has a token the speakers alone may use (P-28)
        const val YTM = "284"        // SMAPI refused (403); listed only through a build's own page source

        // 31*256+7 = 7943 (Qobuz), 204*256+7 = 52231 (Apple), 181*256+7 = 46343 (Mixcloud),
        // 157*256+7 = 40199 (Bandcamp). The service type list a cdudn needs.
        const val TYPE_LIST = "7943,52231,46343,40199,72711"

        /** A presentation map with one Tracks search category. */
        const val MAP = """<Presentation><PresentationMap type="Search"><Match>
            <SearchCategories><Category id="tracks" mappedId="track"/></SearchCategories>
        </Match></PresentationMap></Presentation>"""

        /** Deezer's `NowPlayingRatings`, as its presentation map published it (2026-10-08), icons left out. */
        const val DEEZER_RATINGS = """<Presentation><PresentationMap type="NowPlayingRatings">
            <Match propname="ISFAVORITE" value="0"><Ratings>
                <Rating AutoSkip="ALWAYS" Id="3" StringId="SKIP_TRACK" OnSuccessStringId="SKIP_TRACK_SUCCESS"></Rating>
                <Rating AutoSkip="NEVER" Id="1" StringId="SAVE_TRACK" OnSuccessStringId="SAVE_TRACK_SUCCESS"></Rating>
            </Ratings></Match>
            <Match propname="ISFAVORITE" value="1"><Ratings>
                <Rating AutoSkip="ALWAYS" Id="3" StringId="SKIP_TRACK" OnSuccessStringId="SKIP_TRACK_SUCCESS"></Rating>
                <Rating AutoSkip="NEVER" Id="0" StringId="DELETE_TRACK" OnSuccessStringId="DELETE_TRACK_SUCCESS"></Rating>
            </Ratings></Match>
        </PresentationMap></Presentation>"""

        fun searchResponse() =
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>""" +
                """<searchResponse><searchResult><index>0</index><count>1</count><total>1</total>""" +
                """<mediaMetadata><id>tr-flac:536421002</id><itemType>track</itemType><title>SICKO MODE</title>""" +
                """<trackMetadata><artist>Travis Scott</artist>""" +
                """<albumArtURI>https://cdn.example/cover.jpg</albumArtURI></trackMetadata>""" +
                """</mediaMetadata></searchResult></searchResponse></s:Body></s:Envelope>"""

        fun metadataResponse() =
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>""" +
                """<getMetadataResponse><getMetadataResult><index>0</index><count>1</count><total>1</total>""" +
                """<mediaCollection><id>album:9</id><itemType>album</itemType><title>ASTROWORLD</title></mediaCollection>""" +
                """</getMetadataResult></getMetadataResponse></s:Body></s:Envelope>"""

        /**
         * Pandora's refusal of a day-old household token, by its message as x2rock recorded it
         * (2026-10-12); the code beside it is SOAP's generic one, which x2rock did not record.
         */
        val TOKEN_REFUSAL =
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>""" +
                """<faultcode>s:Server</faultcode><faultstring>Failed to reauth device id</faultstring>""" +
                """</s:Fault></s:Body></s:Envelope>"""

        fun mediaUriResponse() =
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>""" +
                """<getMediaURIResponse><getMediaURIResult>http://stream.example/jazz.pls</getMediaURIResult>""" +
                """</getMediaURIResponse></s:Body></s:Envelope>"""

    }

    private lateinit var fake: FakePlayer
    private lateinit var upnp: MockWebServer
    private lateinit var service: MockWebServer
    private lateinit var scope: CoroutineScope
    private val captureEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
    private var captureGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    private lateinit var household: SonosHousehold

    private val smapiCalls = CopyOnWriteArrayList<String>()
    private val avTransport = CopyOnWriteArrayList<String>()

    /** Whether the player refuses `SetAVTransportURI` with a UPnP fault, as one refusing a source would. */
    @Volatile private var refuseSource = false

    /** What `GetMediaInfo` says the room plays from, and the queue slot `GetPositionInfo` says is playing. */
    @Volatile private var currentUri = "x-rincon-queue:RINCON_X#0"
    @Volatile private var currentTrack = 3

    /** A token the service refuses as Pandora refused a day-old one, and the fault it answers with. */
    @Volatile private var refusedToken: String? = null
    @Volatile private var refusal = TOKEN_REFUSAL

    /** The account store a player hands over, and how many times one was asked for. */
    @Volatile private var envelope = ""
    private val captures = java.util.concurrent.atomic.AtomicInteger()

    /**
     * A build's own source of service pages ([com.rahga.x2rock.smapi.ServicePages]), as a stand-in: none
     * ships in the repository, so what is tested here is the household's routing, not any source.
     */
    private inner class Pages : com.rahga.x2rock.smapi.ServicePages {
        @Volatile var answering = false
        @Volatile var refuseHome = false
        val calls = CopyOnWriteArrayList<String>()
        override val onlyHere = setOf(YTM)
        override val frontPages = setOf(YTM, QOBUZ)
        override suspend fun ready() = answering
        override fun objectIdOf(id: String) = id.removePrefix("pages:").takeIf { it != id }
        override suspend fun home(household: String, linked: com.rahga.x2rock.smapi.LinkedService): List<com.rahga.x2rock.smapi.Item> {
            calls += "home ${linked.service.id}"
            if (refuseHome) throw java.io.IOException("refused")
            return listOf(com.rahga.x2rock.smapi.Item("pages:shelf", "A shelf", "container", null, null, container = true))
        }
        override suspend fun browse(household: String, linked: com.rahga.x2rock.smapi.LinkedService, id: String) =
            listOf(com.rahga.x2rock.smapi.Item("track-1", "So What", "track", null, null, container = false)).also { calls += "browse $id" }
        override suspend fun search(household: String, linked: com.rahga.x2rock.smapi.LinkedService, term: String) =
            com.rahga.x2rock.smapi.ItemPage(listOf(com.rahga.x2rock.smapi.Item("pages:album-9", "Kind Of Blue", "album", null, null, container = true)), 1)
                .also { calls += "search $term" }
    }
    private val pages = Pages()

    /** The presentation map the service publishes; a test about ratings swaps in one that has them. */
    @Volatile private var map = MAP

    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        service = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/manifest" -> MockResponse().setBody("""{"presentationMap":{"uri":"${url("/map")}"}}""")
                    "/map" -> MockResponse().setBody(map)
                    "/smapi" -> {
                        val body = request.body.readUtf8().also { smapiCalls += it }
                        MockResponse().setBody(
                            when {
                                refusedToken?.let { "<token>$it</token>" in body } == true -> refusal
                                "<getMediaURI" in body -> mediaUriResponse()
                                "<getMetadata" in body -> metadataResponse()
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
                            } else if ("GetMediaInfo" in body) {
                                """<u:GetMediaInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">""" +
                                    """<CurrentURI>$currentUri</CurrentURI></u:GetMediaInfoResponse>"""
                            } else if ("GetPositionInfo" in body) {
                                """<u:GetPositionInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">""" +
                                    """<Track>$currentTrack</Track></u:GetPositionInfoResponse>"""
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
        envelope = store("qb-token")
        household = fakeHousehold(
            fake,
            scope,
            addressBook = book,
            upnpPort = upnp.port,
            servicePages = pages,
            accountCapture = {
                captures.incrementAndGet()
                captureEntered.complete(Unit)
                captureGate?.await()
                envelope
            },
        )
    }

    /** The household's sealed account store, with [qobuzToken] as Qobuz's. */
    private fun store(qobuzToken: String) =
        com.rahga.x2rock.smapi.TestEnvelope.seal(
            """<ThirdPartyMediaServers>
                <MediaServer UDN="SA_RINCON7943_X" SerialNum0="14" Token0="$qobuzToken" Key0="qb-key"
                  Nickname0="Qb1" Username0="X_#Svc7943-6c0ffea0-Token"/>
                <MediaServer UDN="SA_RINCON52231_X" SerialNum0="19" Token0="apple-token" Key0=""
                  Nickname0="Richard"/>
                <MediaServer UDN="SA_RINCON46343_X" SerialNum0="41" Token0="mc-token" Key0="mc-key"
                  Nickname0="Mixcloud" Username0="X_#Svc46343-0-Token"/>
                <MediaServer UDN="SA_RINCON40199_X" SerialNum0="43" Token0="bc-token" Key0="bc-key"
                  Nickname0="Bandcamp" Username0="X_#Svc40199-0-Token"/>
                <MediaServer UDN="SA_RINCON72711_X" SerialNum0="23" Token0="yt-token" Key0="yt-key"
                  Nickname0="YouTube Music" Username0="X_#Svc72711-0-Token"/>
            </ThirdPartyMediaServers>""",
            fake.householdId.substringBefore('.'),
        )

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
            """<Service Id="$BANDCAMP" Name="Bandcamp" Uri="$smapi" SecureUri="$smapi">""" +
            """<Policy Auth="DeviceLink" PollInterval="30"/><Manifest Uri="$manifest"/></Service>""" +
            """<Service Id="$YTM" Name="YouTube Music" Uri="$smapi" SecureUri="$smapi">""" +
            """<Policy Auth="AppLink" PollInterval="30"/><Manifest Uri="$manifest"/></Service>""" +
            """<Service Id="$MIXCLOUD" Name="Mixcloud" Uri="$smapi" SecureUri="$smapi">""" +
            """<Policy Auth="DeviceLink" PollInterval="30"/><Manifest Uri="$manifest"/></Service>""" +
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
        assertNull("Bandcamp answers the household token only from the speakers", byId[BANDCAMP])
        assertTrue("Mixcloud, a token like it, is listed", byId.containsKey(MIXCLOUD))
        assertEquals("Qb1", byId.getValue(QOBUZ).nickname)
        assertEquals("sn_14", byId.getValue(QOBUZ).accountId)
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

    @Test fun `a TuneIn episode plays from its media URI, not loadContent`() = runBlocking {
        val groupId = connected()
        // The radio service stands in for TuneIn's id here: the rule is TuneIn's, by service id.
        val tunein = household.searchableServices().first { it.service.id == RADIO }.let { it.copy(service = it.service.copy(id = "333")) }
        val episode = com.rahga.x2rock.smapi.Item("t215839089:p2729788", "Jazz + Filme", "track", null, null, container = false)
        val started = async(Dispatchers.Default) { household.playServiceItem(groupId, tunein, episode) }
        fake.awaitCommand(5_000) { it.get("command")?.asString == "loadStreamUrl" }
        fake.pushPlaybackStatus(groupId, "PLAYBACK_STATE_PLAYING")
        started.await()
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

    /**
     * A Mixcloud item through `loadContent` restarted Dining Room's player (2026-10-11); the Sonos app
     * queues it. Replaced queue, the cloudcast added under the account's serial, slot 1 played.
     */
    @Test fun `a Mixcloud item plays through the queue, never loadContent`() = runBlocking {
        val groupId = connected()
        val mixcloud = household.searchableServices().first { it.service.id == MIXCLOUD }
        val mix = com.rahga.x2rock.smapi.Item(
            id = "cloudcast:116676861", title = "#ChilloutSession 7", itemType = "track", summary = null, artUrl = null, container = false,
        )
        val started = async(Dispatchers.Default) { household.playServiceItem(groupId, mixcloud, mix) }
        fake.awaitCommand(5_000) { it.get("command")?.asString == "play" }
        fake.pushPlaybackStatus(groupId, "PLAYBACK_STATE_PLAYING")
        started.await()
        assertEquals("loadContent restarts the player for Mixcloud", 0, fake.commandsNamed("loadContent"))
        val actions = avTransport.map { body -> Regex("<u:(\\w+)").find(body)?.groupValues?.get(1) }
        val cleared = actions.indexOf("RemoveAllTracksFromQueue")
        val added = actions.indexOf("AddURIToQueue")
        val seek = actions.indexOf("Seek")
        assertTrue("queue replaced, item added, slot played, in that order: $actions", cleared in 0 until added && added < seek)
        val add = avTransport[added]
        assertTrue(add, "cloudcast%3a116676861" in add && "sid=181" in add && "sn=41" in add)
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

    private val album = com.rahga.x2rock.smapi.Item(
        id = "album:9", title = "ASTROWORLD", itemType = "album", summary = null, artUrl = null, container = true,
    )

    /** "Next" is a slot, not a flag: `EnqueueAsNext` alone still appends (x2rock, verified). */
    @Test fun `play next puts it in the slot after the track playing`() = runBlocking {
        val groupId = connected()
        household.queueServiceItem(groupId, qobuz(), album, next = true)
        val body = avTransport.first { "AddURIToQueue" in it }
        assertTrue(body, "<DesiredFirstTrackNumberEnqueued>4</DesiredFirstTrackNumberEnqueued>" in body)
        assertTrue(body, "<EnqueueAsNext>1</EnqueueAsNext>" in body)
    }

    /** On a station the track number says nothing about the queue, so "next" appends. */
    @Test fun `play next off the queue appends`() = runBlocking {
        currentUri = "x-sonosapi-radio:hitlist?sid=303"
        val groupId = connected()
        household.queueServiceItem(groupId, qobuz(), album, next = true)
        val body = avTransport.first { "AddURIToQueue" in it }
        assertTrue(body, "<DesiredFirstTrackNumberEnqueued>0</DesiredFirstTrackNumberEnqueued>" in body)
    }

    private val track = com.rahga.x2rock.smapi.Item(
        id = "track:41805683:7", title = "Your Freedom Is the End of Me", itemType = "track", summary = null, artUrl = null, container = false,
    )

    /** Qobuz publishes no favourite-shaped ratings, so its favourites are SMAPI's own create/delete. */
    @Test fun `a favourite is created and deleted where no rating says how`() = runBlocking {
        connected()
        household.setServiceFavorite(qobuz(), track, favorite = true)
        household.setServiceFavorite(qobuz(), track, favorite = false)
        assertTrue(smapiCalls.any { "<createItem" in it && "<favorite>track:41805683:7</favorite>" in it })
        assertTrue(smapiCalls.any { "<deleteItem" in it && "<favorite>track:41805683:7</favorite>" in it })
        assertTrue(smapiCalls.none { "<rateItem" in it })
    }

    /** Deezer's heart is its ratings: `SAVE_TRACK` (1) and `DELETE_TRACK` (0), sent through `rateItem`. */
    @Test fun `a favourite is a rating where the service's ratings are favourite-shaped`() = runBlocking {
        map = DEEZER_RATINGS
        connected()
        household.setServiceFavorite(qobuz(), track, favorite = true)
        household.setServiceFavorite(qobuz(), track, favorite = false)
        val rates = smapiCalls.filter { "<rateItem" in it }
        assertTrue(rates.toString(), rates.any { "<rating>1</rating>" in it })
        assertTrue(rates.toString(), rates.any { "<rating>0</rating>" in it })
        assertTrue(smapiCalls.none { "<createItem" in it || "<deleteItem" in it })
    }
    @Test fun `a cancelled account capture leaves the service list to be read again`() = runBlocking<Unit> {
        connected()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        captureGate = gate
        val first = async(Dispatchers.Default) { household.searchableServices() }
        withTimeout(5_000) { captureEntered.await() }
        first.cancel()
        first.join()
        gate.complete(Unit)
        val retried = household.searchableServices()
        assertTrue("cancelled discovery cached only ${retried.map { it.service.name }} for the entire session",
            retried.any { it.service.id == QOBUZ && it.added })
    }

    @Test fun `a token refused as stale is read again from the household and the call retried`() = runBlocking {
        connected()
        val linked = qobuz()
        refusedToken = "qb-token"
        envelope = store("qb-token-2")

        val page = household.searchService(linked, Category("tracks", "track"), term = "sicko")

        assertEquals("the retry's hits came back", "SICKO MODE", page.items.single().title)
        assertEquals("the household's store was read once more", 2, captures.get())
        val searches = smapiCalls.filter { "<search" in it }
        assertTrue(searches.last(), "<token>qb-token-2</token>" in searches.last())
        // Kept: the next call goes straight out with the newer token.
        household.searchService(linked, Category("tracks", "track"), term = "again")
        assertEquals(2, captures.get())
        assertTrue("<token>qb-token-2</token>" in smapiCalls.last())
    }

    /**
     * However it is worded: a garbled Pandora token is refused with a script error, not anything about
     * a login (x2rock, 2026-10-13). A newer token on the household is what tells.
     */
    @Test fun `a refusal in any words is retried with a newer household token`() = runBlocking {
        connected()
        val linked = qobuz()
        refusedToken = "qb-token"
        refusal = TOKEN_REFUSAL.replace("Failed to reauth device id", "TypeError: Cannot read properties of null (reading 'getListenerId')")
        envelope = store("qb-token-2")

        val page = household.searchService(linked, Category("tracks", "track"), term = "x")
        assertEquals("SICKO MODE", page.items.single().title)
        assertEquals(2, captures.get())
    }

    @Test fun `one re-read answers every refusal for a while`() = runBlocking {
        connected()
        val linked = qobuz()
        refusedToken = "qb-token"

        repeat(3) { assertTrue(runCatching { household.searchService(linked, Category("tracks", "track"), "x") }.isFailure) }

        assertEquals("the household holds nothing newer, and was asked once", 2, captures.get())
        assertEquals("each refusal is final with nothing newer to try", 3, smapiCalls.count { "<search" in it })
    }

    @Test fun `a service only a page source reaches is listed only while it answers`() = runBlocking {
        connected()
        assertTrue("not answering", household.searchableServices().none { it.service.id == YTM })
        pages.answering = true
        assertTrue(household.searchableServices().any { it.service.id == YTM })
        pages.answering = false
        assertTrue("and gone again", household.searchableServices().none { it.service.id == YTM })
    }

    @Test fun `its root, containers and search go to the page source, never its SMAPI`() = runBlocking {
        connected()
        pages.answering = true
        val ytm = household.searchableServices().first { it.service.id == YTM }

        assertEquals(listOf("A shelf"), household.browseService(ytm).items.map { it.title })
        assertEquals(listOf("So What"), household.browseService(ytm, "pages:shelf").items.map { it.title })
        val category = household.serviceCategories(ytm).single()
        assertEquals("Kind Of Blue", household.searchService(ytm, category, "miles davis").items.single().title)
        assertTrue("one page, whole", household.searchService(ytm, category, "miles davis", index = 1).items.isEmpty())

        assertEquals(listOf("home $YTM", "browse pages:shelf", "search miles davis"), pages.calls)
        assertTrue("no SMAPI call for it", smapiCalls.none { "yt-token" in it })
    }

    @Test fun `a front page the source refuses falls back to the SMAPI root`() = runBlocking {
        connected()
        pages.answering = true
        pages.refuseHome = true
        assertEquals("ASTROWORLD", household.browseService(qobuz()).items.single().title)
        assertEquals(listOf("home $QOBUZ"), pages.calls)
    }

    @Test fun `a page source's item plays by the object id it stands for`() = runBlocking<Unit> {
        val groupId = connected()
        pages.answering = true
        val ytm = household.searchableServices().first { it.service.id == YTM }
        val album = household.searchService(ytm, household.serviceCategories(ytm).single(), "miles davis").items.single()
        val started = async(Dispatchers.Default) { household.playServiceItem(groupId, ytm, album) }
        fake.awaitCommand("loadContent", 5_000)
        val load = fake.lastCommandBody("loadContent").toString()
        assertTrue(load, "\"objectId\":\"album-9\"" in load)
        fake.pushPlaybackStatus(groupId, "PLAYBACK_STATE_PLAYING")
        started.await()
    }

    @Test fun `a capture left to finish lists the credentialed service`() = runBlocking<Unit> {
        connected()
        assertTrue(household.searchableServices().any { it.service.id == QOBUZ && it.added })
    }

}
