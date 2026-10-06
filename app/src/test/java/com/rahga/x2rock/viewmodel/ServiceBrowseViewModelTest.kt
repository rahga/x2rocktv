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

    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        service = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/manifest" -> MockResponse().setBody("""{"presentationMap":{"uri":"${url("/map")}"}}""")
                    "/map" -> MockResponse().setBody(MAP)
                    "/smapi" -> {
                        val body = request.body.readUtf8()
                        MockResponse().setBody(if ("<getMetadata" in body) CHILDREN else SEARCH_ALBUM)
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

    @Test fun `opening a service exposes its search categories`() = runBlocking {
        val qobuz = ready().first { it.service.id == "31" }
        viewModel.open(qobuz)
        withTimeout(5_000) { viewModel.categories.first { it.isNotEmpty() } }
        assertEquals("tracks", viewModel.categories.value.first().id)
    }

    @Test fun `descending into a container, then Back, climbs out a level at a time`() = runBlocking {
        val qobuz = ready().first { it.service.id == "31" }
        viewModel.open(qobuz)
        withTimeout(5_000) { viewModel.category.first { it != null } }

        viewModel.setQuery("astroworld")
        viewModel.search()
        val hits = found()
        assertEquals("the search returns an album to open", "ASTROWORLD", hits.first().title)
        assertTrue(hits.first().container)

        // Open the album: results become its children.
        viewModel.select(hits.first()) {}
        withTimeout(5_000) { viewModel.results.first { (it as? ServiceBrowseViewModel.Results.Found)?.items?.firstOrNull()?.container == false } }
        assertEquals("SICKO MODE", found().first().title)

        // Back out of the container — still inside the service.
        assertTrue(viewModel.back())
        assertTrue(viewModel.active.value != null)
        // Back again — out to the service list.
        assertTrue(viewModel.back())
        assertEquals(null, viewModel.active.value)
        // Back once more — nothing left to climb; the screen itself should close.
        assertFalse(viewModel.back())
    }

    @Test fun `queuing a container that holds no tracks is turned away`() = runBlocking {
        val qobuz = ready().first { it.service.id == "31" }
        viewModel.open(qobuz)
        withTimeout(5_000) { viewModel.category.first { it != null } }
        val artist = com.rahga.x2rock.smapi.Item("artist:1", "x", "artist", null, null, container = true)
        viewModel.queue(artist)
        assertTrue(withTimeout(5_000) { viewModel.notice.first { it != null } }!!.contains("isn't something"))
    }

    private fun services(): String {
        val smapi = service.url("/smapi"); val manifest = service.url("/manifest")
        val descriptors = """<Services SchemaVersion="1"><Service Id="31" Name="Qobuz" Uri="$smapi" SecureUri="$smapi">""" +
            """<Policy Auth="AppLink"/><Manifest Uri="$manifest"/></Service></Services>"""
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

        fun seal(xml: String, householdId: String) = com.rahga.x2rock.smapi.TestEnvelope.seal(xml, householdId)
    }
}
