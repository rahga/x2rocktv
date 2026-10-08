package com.rahga.x2rock.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.rahga.x2rock.lan.FakePlayer
import com.rahga.x2rock.lan.LanHttp
import com.rahga.x2rock.lan.MulticastGate
import com.rahga.x2rock.lan.PlayerAddressBook
import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.lan.connectTo
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress

/**
 * The browse/search *navigation* of [ServiceBrowseViewModel] — the part that is the view model's
 * own rather than the household's: opening a service, searching it, descending into a container,
 * and Back climbing back out a level at a time to the service list. The household end is covered
 * by `ServiceSearchTest`; this drives the same fakes through the view model.
 */
class ServiceBrowseViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    private lateinit var fake: FakePlayer
    private lateinit var upnp: MockWebServer
    private lateinit var service: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold
    private lateinit var viewModel: ServiceBrowseViewModel
    /** Whether the service answers its root with nothing at all, as Sonos Radio does over SMAPI. */
    @Volatile private var emptyRoot = false
    /** Whether the service's manifest names a browse endpoint, as Sonos Radio's does. */
    @Volatile private var shelvesOn = false

    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        service = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/manifest" -> MockResponse().setBody(
                        if (shelvesOn) """{"presentationMap":{"uri":"${url("/map")}"},"endpoints":[{"type":"browse","uri":"${url("/browse")}"}]}"""
                        else """{"presentationMap":{"uri":"${url("/map")}"}}"""
                    )
                    "/browse" -> MockResponse().setBody(FakePlayer.fixtureText("sonosradio.browse.json"))
                    "/map" -> MockResponse().setBody(MAP)
                    "/smapi" -> {
                        val body = request.body.readUtf8()
                        MockResponse().setBody(when {
                            "<getMetadata" !in body -> SEARCH_ALBUM
                            "<id>root</id>" in body && emptyRoot -> EMPTY
                            "<id>long</id><index>0</index>" in body -> page(0, 2, total = 3)
                            "<id>long</id><index>2</index>" in body -> page(2, 1, total = 3)
                            else -> CHILDREN
                        })
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        upnp = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path == "/MusicServices/Control") MockResponse().setBody(services())
                    else MockResponse().setBody("<s:Envelope><s:Body><u:Resp/></s:Body></s:Envelope>")
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        scope = CoroutineScope(SupervisorJob())
        val book = PlayerAddressBook()
        val envelope = seal(
            """<ThirdPartyMediaServers><MediaServer UDN="SA_RINCON7943_X" SerialNum0="14"
               Token0="t" Key0="k" Nickname0="Qb1" Username0="X_#Svc7943-abc-Token"/></ThirdPartyMediaServers>""",
            fake.householdId.substringBefore('.'),
        )
        household = SonosHousehold(
            scope = scope, addressBook = book, multicast = MulticastGate.None,
            client = LanHttp.client(book), internetClient = OkHttpClient(),
            port = fake.port, upnpPort = upnp.port, accountCapture = { envelope },
        )
        runBlocking { household.connectTo(fake) }
        viewModel = ServiceBrowseViewModel(household, SavedStateHandle(mapOf("groupId" to fake.groupId(household))))
    }

    @After fun tearDown() {
        household.disconnect(); scope.cancel(); fake.shutdown(); upnp.shutdown(); service.shutdown()
    }

    private suspend fun ready(): List<com.rahga.x2rock.smapi.LinkedService> =
        (withTimeout(5_000) { viewModel.services.first { it is ServiceBrowseViewModel.Services.Ready } }
            as ServiceBrowseViewModel.Services.Ready).services

    private suspend fun found(): List<com.rahga.x2rock.smapi.Item> =
        (withTimeout(5_000) { viewModel.results.first { it is ServiceBrowseViewModel.Results.Found } }
            as ServiceBrowseViewModel.Results.Found).items

    /** The captured history holds an Apple Music album, so this household's account is known. */
    @Test fun `Apple Music is offered where the household has played from it`() = runBlocking<Unit> {
        val ready = withTimeout(5_000) { viewModel.services.first { it is ServiceBrowseViewModel.Services.Ready } }
            as ServiceBrowseViewModel.Services.Ready
        org.junit.Assert.assertTrue(ready.appleMusic)
    }

    @Test fun `opening a service exposes its search categories`() = runBlocking {
        val qobuz = ready().first { it.service.id == "31" }
        viewModel.open(qobuz)
        withTimeout(5_000) { viewModel.categories.first { it.isNotEmpty() } }
        assertEquals("tracks", viewModel.categories.value.first().id)
    }

    /**
     * A service that can be searched still opens on its library: it used to open on a search field,
     * which left every library behind one — Deezer's Flow, Qobuz's playlists — out of reach.
     */
    @Test fun `a searchable service opens on its library, not on the search`() = runBlocking {
        val qobuz = ready().first { it.service.id == "31" }
        viewModel.open(qobuz)
        assertEquals("SICKO MODE", found().first().title)
        withTimeout(5_000) { viewModel.categories.first { it.isNotEmpty() } }
        assertFalse("the search waits to be asked for", viewModel.searching.value)
    }

    /** Sonos Radio answers its SMAPI root with nothing, so its search is all there is to open on. */
    @Test fun `a searchable service with an empty root opens on the search`() = runBlocking {
        emptyRoot = true
        val qobuz = ready().first { it.service.id == "31" }
        viewModel.open(qobuz)
        withTimeout(5_000) { viewModel.searching.first { it } }
        assertTrue(viewModel.back())
        assertEquals("Back from that search leaves the service", null, viewModel.active.value)
    }

    /** Sonos Radio: an empty SMAPI root, and a page of shelves from its own browse endpoint instead. */
    @Test fun `an empty root with a browse endpoint opens on its shelves`() = runBlocking {
        emptyRoot = true
        shelvesOn = true
        val qobuz = ready().first { it.service.id == "31" }
        viewModel.open(qobuz)
        val shelves = withTimeout(5_000) { viewModel.results.first { (it as? ServiceBrowseViewModel.Results.Found)?.items?.isNotEmpty() == true } }
            as ServiceBrowseViewModel.Results.Found
        assertEquals("Trending Now", shelves.items.first().title)
        assertFalse(viewModel.searching.value)
        viewModel.select(shelves.items.first()) {}
        val stations = viewModel.results.value as ServiceBrowseViewModel.Results.Found
        assertTrue(stations.items.any { it.id == "sonos:2997" })
        assertTrue(viewModel.back())
        assertEquals("Trending Now", (viewModel.results.value as ServiceBrowseViewModel.Results.Found).items.first().title)
    }

    @Test fun `descending into a container, then Back, climbs out a level at a time`() = runBlocking {
        val qobuz = ready().first { it.service.id == "31" }
        viewModel.open(qobuz)
        assertEquals("SICKO MODE", found().first().title)
        withTimeout(5_000) { viewModel.category.first { it != null } }

        viewModel.openSearch()
        assertTrue(viewModel.searching.value)
        viewModel.setQuery("astroworld")
        viewModel.search()
        withTimeout(5_000) { viewModel.results.first { (it as? ServiceBrowseViewModel.Results.Found)?.items?.firstOrNull()?.container == true } }
        val hits = found()
        assertEquals("the search returns an album to open", "ASTROWORLD", hits.first().title)

        // Open the album: results become its children.
        viewModel.select(hits.first()) {}
        withTimeout(5_000) { viewModel.results.first { (it as? ServiceBrowseViewModel.Results.Found)?.items?.firstOrNull()?.container == false } }
        assertEquals("SICKO MODE", found().first().title)
        assertFalse(viewModel.searching.value)

        // Back out of the container — still inside the service, and back on the search's hits
        // rather than an empty list under the query.
        assertTrue(viewModel.back())
        assertTrue(viewModel.searching.value)
        assertEquals("ASTROWORLD", (viewModel.results.value as ServiceBrowseViewModel.Results.Found).items.first().title)
        // Back out of the search — onto the library it was entered from, as it was left.
        assertTrue(viewModel.back())
        assertFalse(viewModel.searching.value)
        assertTrue(viewModel.active.value != null)
        assertEquals("SICKO MODE", (viewModel.results.value as ServiceBrowseViewModel.Results.Found).items.first().title)
        // Back again — out to the service list.
        assertTrue(viewModel.back())
        assertEquals(null, viewModel.active.value)
        // Back once more — nothing left to climb; the screen itself should close.
        assertFalse(viewModel.back())
    }

    /**
     * Opened from a Browse favourite, on its album: the album's tracks, with Play and Shuffle. Built
     * in the constructor, which is where this crashed on the TV — a flow the entry path set was
     * declared below the `init` that reached it, so it was still null.
     */
    @Test fun `a favourite's album opens on its tracks, playable whole`() = runBlocking {
        val groupId = fake.groupId(household)
        // Already read, as it is by the time Browse opens one: with nothing to wait for, the entry
        // runs inside the constructor — the order the TV crashed in.
        ready()
        val fromFavorite = ServiceBrowseViewModel(household, SavedStateHandle(mapOf(
            "groupId" to groupId, "service" to "31:sn_14", "container" to "album:9", "title" to "ASTROWORLD", "favorite" to "84",
        )))
        val tracks = withTimeout(5_000) { fromFavorite.results.first { it is ServiceBrowseViewModel.Results.Found } }
        assertEquals("SICKO MODE", (tracks as ServiceBrowseViewModel.Results.Found).items.first().title)
        assertTrue(fromFavorite.playsWhole.value)
        assertFalse("its top is where Back leaves, for Browse", fromFavorite.back())
    }

    /** A container longer than one answer is read on as the list nears its end, not cut short. */
    @Test fun `a long container is read a page at a time`() = runBlocking {
        val qobuz = ready().first { it.service.id == "31" }
        viewModel.open(qobuz)
        found()
        viewModel.browse("long", "Long")
        withTimeout(5_000) { viewModel.results.first { (it as? ServiceBrowseViewModel.Results.Found)?.total == 3 } }
        val first = viewModel.results.value as ServiceBrowseViewModel.Results.Found
        assertEquals(2, first.items.size)
        assertTrue(first.hasMore)

        viewModel.loadMore()
        val all = withTimeout(5_000) {
            viewModel.results.first { (it as? ServiceBrowseViewModel.Results.Found)?.items?.size == 3 }
        } as ServiceBrowseViewModel.Results.Found
        assertEquals(listOf("t0", "t1", "t2"), all.items.map { it.id })
        assertFalse(all.hasMore)
    }

    /**
     * A browse-only service opens on its root, and that root is its top: Back from it leaves the
     * service. It used to re-read the root, which pushed it onto the trail again, so Back never
     * left — every anonymous radio service was a trap (Piraten.FM, on the Streamer, 2026-10-07).
     */
    @Test fun `Back from a browse-only service's root leaves the service`() = runBlocking {
        val radio = ready().first { it.service.id == "894" }
        viewModel.open(radio)
        found()
        assertTrue(viewModel.categories.value.isEmpty())
        assertTrue(viewModel.back())
        assertEquals("Back left the service", null, viewModel.active.value)
        assertFalse(viewModel.back())
    }

    @Test fun `queuing a container that holds no tracks is turned away`() = runBlocking {
        val qobuz = ready().first { it.service.id == "31" }
        viewModel.open(qobuz)
        withTimeout(5_000) { viewModel.category.first { it != null } }
        val artist = com.rahga.x2rock.smapi.Item("artist:1", "x", "artist", null, null, container = true)
        viewModel.menu.queue(ServiceItemMenu.Target.Service(qobuz, artist))
        assertTrue(withTimeout(5_000) { viewModel.notice.first { it != null } }!!.contains("isn't something"))
    }

    private fun services(): String {
        val smapi = service.url("/smapi"); val manifest = service.url("/manifest")
        val descriptors = """<Services SchemaVersion="1"><Service Id="31" Name="Qobuz" Uri="$smapi" SecureUri="$smapi">""" +
            """<Policy Auth="AppLink"/><Manifest Uri="$manifest"/></Service>""" +
            // An anonymous radio service with no presentation map: nothing to search, browse only.
            """<Service Id="894" Name="Pirate Radio" Uri="$smapi" SecureUri="$smapi"><Policy Auth="Anonymous"/></Service>""" +
            """</Services>"""
        val escaped = descriptors.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        return """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>""" +
            """<u:ListAvailableServicesResponse xmlns:u="urn:schemas-upnp-org:service:MusicServices:1">""" +
            """<AvailableServiceDescriptorList>$escaped</AvailableServiceDescriptorList>""" +
            """<AvailableServiceTypeList>7943</AvailableServiceTypeList></u:ListAvailableServicesResponse></s:Body></s:Envelope>"""
    }

    private companion object {
        const val MAP = """<Presentation><PresentationMap type="Search"><Match>
            <SearchCategories><Category id="tracks" mappedId="track"/></SearchCategories></Match></PresentationMap></Presentation>"""
        const val SEARCH_ALBUM =
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><searchResponse><searchResult>""" +
                """<total>1</total><mediaCollection><id>album:9</id><itemType>album</itemType><title>ASTROWORLD</title>""" +
                """</mediaCollection></searchResult></searchResponse></s:Body></s:Envelope>"""
        const val CHILDREN =
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><getMetadataResponse><getMetadataResult>""" +
                """<total>1</total><mediaMetadata><id>tr:1</id><itemType>track</itemType><title>SICKO MODE</title></mediaMetadata>""" +
                """</getMetadataResult></getMetadataResponse></s:Body></s:Envelope>"""

        const val EMPTY =
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><getMetadataResponse><getMetadataResult>""" +
                """<total>0</total></getMetadataResult></getMetadataResponse></s:Body></s:Envelope>"""

        /** [count] tracks from [index] of a container of [total]. */
        fun page(index: Int, count: Int, total: Int) =
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><getMetadataResponse><getMetadataResult>""" +
                "<index>$index</index><count>$count</count><total>$total</total>" +
                (index until index + count).joinToString("") {
                    "<mediaMetadata><id>t$it</id><itemType>track</itemType><title>Track $it</title></mediaMetadata>"
                } +
                """</getMetadataResult></getMetadataResponse></s:Body></s:Envelope>"""

        fun seal(xml: String, householdId: String) = com.rahga.x2rock.smapi.TestEnvelope.seal(xml, householdId)
    }
}
