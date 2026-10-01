package com.rahga.x2rock.lan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Playing a queue track from whatever the room is on. The hardware half is
 * `LiveHouseholdTest`'s "a queue track plays whatever the room was playing", run 2026-10-01
 * on a One SL playing Radio Paradise; this is the half CI can run.
 *
 * The UPnP fake answers `GetMediaInfo` with the `CurrentURI` that One SL reported, and
 * refuses `Seek` with 701 unless the queue has been made the source — as the player did.
 */
class QueueSourceTest {

    private companion object {
        const val RADIO = "x-sonosapi-radio:channel%3a1%3a3%3aresume?sid=308&amp;flags=0"
    }

    private lateinit var fake: FakePlayer
    private lateinit var upnp: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold

    /** What the transport plays from; set by `SetAVTransportURI`, as on a player. */
    @Volatile private var source = RADIO
    /** Each SOAP action, in order, with the URI where one was set. */
    private val actions = CopyOnWriteArrayList<String>()

    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        upnp = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val action = request.getHeader("SOAPAction").orEmpty().substringAfter('#').trim('"')
                    val body = request.body.readUtf8()
                    return when (action) {
                        "GetMediaInfo" -> {
                            actions += action
                            soap("<CurrentURI>$source</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>", action)
                        }
                        "SetAVTransportURI" -> {
                            source = Regex("<CurrentURI>([^<]*)</CurrentURI>").find(body)!!.groupValues[1]
                            actions += "$action $source"
                            soap("", action)
                        }
                        "Seek" -> {
                            actions += action
                            if (source.startsWith("x-rincon-queue:")) soap("", action)
                            else MockResponse().setResponseCode(500).setBody(
                                "<s:Envelope><s:Body><s:Fault><detail><UPnPError><errorCode>701</errorCode>" +
                                    "</UPnPError></detail></s:Fault></s:Body></s:Envelope>"
                            )
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        scope = CoroutineScope(SupervisorJob())
        val book = PlayerAddressBook()
        household = SonosHousehold(
            scope = scope,
            addressBook = book,
            multicast = MulticastGate.None,
            client = LanHttp.client(book),
            port = fake.port,
            upnpPort = upnp.port,
        )
        runBlocking {
            household.connect(Discovery.DiscoveredPlayer(fake.id, InetAddress.getByName("127.0.0.1"), fake.householdId))
            withTimeout(5_000) { household.state.first { it.connected } }
        }
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        fake.shutdown()
        upnp.shutdown()
    }

    private fun soap(inner: String, action: String) = MockResponse().setBody(
        """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>""" +
            """<u:${action}Response xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">$inner""" +
            """</u:${action}Response></s:Body></s:Envelope>"""
    )

    private fun kitchen() = household.state.value.groups.first { it.name == "Kitchen" }

    @Test fun `after radio, the queue is made the source before the seek, and then it plays`() = runBlocking<Unit> {
        val kitchen = kitchen()
        fake.clearHistory()
        household.skipToQueueItem(kitchen.id, 3)
        assertEquals(
            listOf("GetMediaInfo", "SetAVTransportURI x-rincon-queue:${kitchen.coordinatorId}#0", "Seek"),
            actions,
        )
        fake.awaitCommand { it.get("command")?.asString == "play" && it.get("groupId")?.asString == kitchen.id }
    }

    /** Re-setting the source while the queue plays would restart the transport for nothing. */
    @Test fun `on the queue already, the source is left alone`() = runBlocking<Unit> {
        source = "x-rincon-queue:${kitchen().coordinatorId}#0"
        household.skipToQueueItem(kitchen().id, 3)
        assertEquals(listOf("GetMediaInfo", "Seek"), actions)
        assertTrue(household.playingFromQueue(kitchen().id))
    }
}
