package com.rahga.x2rock.lan

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [SonosHousehold.previousInQueue]: the track Prev goes to, read from the queue because the player
 * never names it. The browse pages are `QueueEditTest`'s captured SYMFONISK queue, six to a page,
 * so the second page starts at slot 7. `GetMediaInfo` and `GetPositionInfo` answer the one element
 * each that is read, as `ServiceSearchTest`'s do.
 */
class PreviousTrackTest {

    private lateinit var fake: FakePlayer
    private lateinit var upnp: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold
    private val requests = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()

    @Volatile private var currentUri = "x-rincon-queue:RINCON_X#0"
    @Volatile private var currentTrack = 8

    private fun fields(body: String): Map<String, String> =
        Regex("<(\\w+)>([^<]*)</\\1>").findAll(body).associate { it.groupValues[1] to it.groupValues[2] }

    private fun soap(action: String, body: String) = MockResponse().setBody(
        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
            "<u:${action}Response xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">$body</u:${action}Response>" +
            "</s:Body></s:Envelope>"
    )

    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        upnp = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val action = soapAction(request)
                    val f = fields(request.body.readUtf8())
                    requests += action to f
                    return when (action) {
                        "GetMediaInfo" -> soap(action, "<CurrentURI>$currentUri</CurrentURI>")
                        "GetPositionInfo" -> soap(action, "<Track>$currentTrack</Track>")
                        "Browse" -> MockResponse().setBody(
                            FakePlayer.fixtureText(if (f["StartingIndex"] == "0") "Browse.queue.page1.xml" else "Browse.queue.page2.xml")
                        )
                        else -> MockResponse().setBody(EMPTY_SOAP)
                    }
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        scope = CoroutineScope(SupervisorJob())
        val book = PlayerAddressBook()
        household = SonosHousehold(
            scope = scope, addressBook = book, multicast = MulticastGate.None,
            client = LanHttp.client(book), port = fake.port, upnpPort = upnp.port,
        )
        runBlocking { household.connectTo(fake) }
        requests.clear()
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        fake.shutdown()
        upnp.shutdown()
    }

    private fun group() = household.state.value.groups.first().id

    /** Slot 8 playing: the one track asked for is slot 7, browse index 6. */
    @Test fun `the track before the playing slot is read`() = runBlocking<Unit> {
        val previous = household.previousInQueue(group())
        val browse = requests.single { it.first == "Browse" }.second
        assertEquals("6", browse["StartingIndex"])
        assertEquals("1", browse["RequestedCount"])
        assertEquals("Evan Finds the Third Room", previous?.name)
        assertEquals("Khruangbin", previous?.artist?.name)
    }

    @Test fun `the first slot has nothing before it`() = runBlocking<Unit> {
        currentTrack = 1
        assertNull(household.previousInQueue(group()))
        assertTrue("nothing to browse for", requests.none { it.first == "Browse" })
    }

    /** On a stream the slot reads 1 whatever the queue holds (see `Upnp.currentTrackNumber`). */
    @Test fun `a room not playing its queue has no previous track`() = runBlocking<Unit> {
        currentUri = "x-sonosapi-radio:channel%3a1%3a3%3aresume?sid=308&amp;flags=0"
        currentTrack = 5
        assertNull(household.previousInQueue(group()))
        assertTrue(requests.none { it.first == "Browse" || it.first == "GetPositionInfo" })
    }
}
