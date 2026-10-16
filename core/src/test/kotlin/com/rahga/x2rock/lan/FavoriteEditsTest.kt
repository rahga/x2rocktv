package com.rahga.x2rock.lan

import com.rahga.x2rock.smapi.Auth
import com.rahga.x2rock.smapi.Item
import com.rahga.x2rock.smapi.LinkedService
import com.rahga.x2rock.smapi.Service
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Saving a service's item to Sonos Favorites and removing one (T-32). The replies are the office One
 * SL's, 2026-10-16: `CreateObject` of a Saavn track written as the queue writes it (`FV:2/18`), the
 * 803 for saving an item already a favourite, and `DestroyObject`'s empty reply and its 701 once gone.
 */
class FavoriteEditsTest {

    private lateinit var fake: FakePlayer
    private lateinit var upnp: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold
    private val requests = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
    @Volatile private var create = "CreateObject.serviceTrack.xml"
    @Volatile private var destroy = "DestroyObject.favorite.xml"

    private val saavn = LinkedService(
        Service("164", "Saavn", "https://saavn.example/smapi", Auth.APP_LINK, null, serviceType = 41991),
        token = null, accountId = "sn_13", selector = "acdaed4d", nickname = "",
    )
    private val track = Item("TRACK_NEW:-sJuFNFc", "Sammakka", "track", summary = null, artUrl = "https://c.saavncdn.com/a.jpg", container = false)
    private val album = Item("ALBUM:48037104", "Jawan", "album", summary = null, artUrl = null, container = true)

    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        upnp = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val action = soapAction(request)
                    requests += action to soapFields(request.body.readUtf8())
                    fun reply(file: String) = MockResponse().setResponseCode(if ("fault" in file) 500 else 200).setBody(FakePlayer.fixtureText(file))
                    return when (action) {
                        "CreateObject" -> reply(create)
                        "DestroyObject" -> reply(destroy)
                        else -> MockResponse().setBody(EMPTY_SOAP)
                    }
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        scope = CoroutineScope(SupervisorJob())
        household = fakeHousehold(fake, scope, addressBook = PlayerAddressBook(), upnpPort = upnp.port)
        runBlocking { household.connectTo(fake, 10_000) }
        requests.clear()
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        upnp.shutdown()
        fake.shutdown()
    }

    private fun created() = requests.single { it.first == "CreateObject" }.second

    /** `Elements` as the player reads it: the envelope's own escaping taken off, once. */
    private fun elements() = created()["Elements"]!!
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")

    @Test fun `a track is saved as the queue would be given it`() = runBlocking<Unit> {
        val saved = household.saveServiceFavorite(saavn, track)
        assertEquals("Sammakka", saved.name)
        assertFalse(saved.alreadySaved)
        assertEquals("FV:2", created()["ContainerID"])
        val elements = elements()
        assertTrue(elements, "<res protocolInfo=\"x-sonosapi-hls-static:*:*:*\">x-sonosapi-hls-static:TRACK_NEW%3a-sJuFNFc?sid=164&amp;flags=65544&amp;sn=13</res>" in elements)
        assertTrue(elements, "<r:type>instantPlay</r:type>" in elements && "<r:description>Saavn</r:description>" in elements)
        assertTrue(elements, "SA_RINCON41991_X_#Svc41991-acdaed4d-Token" in elements)
        assertTrue(elements, "<upnp:albumArtURI>https://c.saavncdn.com/a.jpg</upnp:albumArtURI>" in elements)
    }

    @Test fun `an album is saved as its container`() = runBlocking<Unit> {
        household.saveServiceFavorite(saavn, album)
        val elements = elements()
        assertTrue(elements, "<res protocolInfo=\"x-rincon-cpcontainer:*:*:*\">x-rincon-cpcontainer:1004206cALBUM%3a48037104?sid=164&amp;flags=8300&amp;sn=13</res>" in elements)
        assertTrue(elements, "object.container.album.musicAlbum" in elements)
    }

    @Test fun `an item already a favourite is said to be one`() = runBlocking<Unit> {
        create = "CreateObject.exists.fault803.xml"
        assertTrue(household.saveServiceFavorite(saavn, track).alreadySaved)
    }

    @Test fun `any other refusal of a save stands`() = runBlocking<Unit> {
        create = "DestroyObject.gone.fault701.xml"
        try {
            household.saveServiceFavorite(saavn, track)
            fail("a refusal was taken for a save")
        } catch (e: UpnpRefusedException) {
            assertEquals("701", e.upnpCode)
        }
    }

    @Test fun `a favourite is removed by its FV id, and one already gone is no error`() = runBlocking<Unit> {
        household.removeFavorite("17")
        assertEquals("FV:2/17", requests.single { it.first == "DestroyObject" }.second["ObjectID"])
        destroy = "DestroyObject.gone.fault701.xml"
        household.removeFavorite("17")
    }

    @Test fun `any other refusal of a removal stands`() = runBlocking<Unit> {
        destroy = "CreateObject.exists.fault803.xml"
        try {
            household.removeFavorite("17")
            fail("a refusal was taken for a removal")
        } catch (e: UpnpRefusedException) {
            assertEquals("803", e.upnpCode)
        }
    }

    /** By service and the service's own id: `getFavorites` writes an album's with its container prefix. */
    @Test fun `an item is found among the favourites by its service's id`() = runBlocking<Unit> {
        val ytm = saavn.copy(service = saavn.service.copy(id = "284", name = "YouTube Music"))
        val listed = Item("ALkSOiF60SmN3aW6x6j0p5ECA5_qJlToFZPtLSwFkyBNbzYO", "Ryo Fukui in New York", "album", null, null, container = true)
        assertEquals("83", household.favoriteFor(ytm, listed)?.id)
        assertNull(household.favoriteFor(saavn, listed))
        assertNull(household.favoriteFor(ytm, listed.copy(id = "ALkSOiOther")))
    }
}
