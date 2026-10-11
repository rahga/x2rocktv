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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * About My System: each speaker's own description and the update it is offered. Both replies are
 * Kitchen's One SL, 2026-10-10 (serial, MAC and id redacted), up to date — its offer is the build it
 * has. "An update waiting" is the same reply with only the offered version moved on.
 */
class AboutSystemTest {

    private lateinit var fake: FakePlayer
    private lateinit var upnp: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold
    @Volatile private var offer = FakePlayer.fixtureText("CheckForUpdate.cached.xml")
    private val actions = CopyOnWriteArrayList<String>()

    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        upnp = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/xml/device_description.xml" -> MockResponse().setBody(FakePlayer.fixtureText("device_description.kitchen.xml"))
                    soapAction(request) == "CheckForUpdate" -> MockResponse().setBody(offer).also { actions += soapFields(request.body.readUtf8()).toString() }
                    else -> MockResponse().setBody(EMPTY_SOAP)
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
        runBlocking { household.connectTo(fake, 10_000) }
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        fake.shutdown()
        upnp.shutdown()
    }

    @Test fun `each speaker says what it is, and an up-to-date one waits for nothing`() = runBlocking<Unit> {
        val speakers = household.aboutSystem()
        assertEquals(household.state.value.groups.flatMap { it.playerIds }.distinct().size, speakers.size)
        val one = speakers.first()
        assertEquals("Sonos One SL", one.info?.modelName)
        assertEquals("18.8", one.info?.displayVersion)
        assertEquals("97.1-80312", one.offer?.version)
        assertFalse(one.updateWaiting)
        // Asked from the player's own memory, never from Sonos.
        assertTrue(actions.first(), "CachedOnly=1" in actions.first())
    }

    /**
     * Dining Room's SYMFONISK pair, captured: it runs 86.10 and is offered the household's 97.1 with
     * nothing to download. A version comparison called that an update; the CLI and the size do not.
     */
    @Test fun `another build offered with nothing to download is no update`() = runBlocking<Unit> {
        offer = FakePlayer.fixtureText("CheckForUpdate.cached.symfonisk.xml")
        val one = household.aboutSystem().first()
        assertEquals("97.1-80312", one.offer?.version)
        assertFalse(one.updateWaiting)
    }

    /** Something to download is an update waiting: Kitchen's reply with only its size moved off 0. */
    @Test fun `something to download is an update waiting`() = runBlocking<Unit> {
        offer = offer.replace("DownloadSize=&quot;0&quot;", "DownloadSize=&quot;123456789&quot;")
        assertTrue(household.aboutSystem().first().updateWaiting)
    }
}
