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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

/**
 * Two Sonos systems on one network — an office running two, say — and nothing remembered.
 * The first player to answer SSDP is not a choice, so the app asks, and remembers the answer.
 *
 * Only one of the two is a working fake; the other exists only as an SSDP reply and a
 * `/status/zp` document. Each document is `status.zp.xml` with its room renamed.
 */
class TwoHouseholdsTest {

    private val otherId = "RINCON_FFEEDDCCBBAA01400"
    private val otherHousehold = "Sonos_OtherHousehold.OtherToken"

    private lateinit var fake: FakePlayer
    private lateinit var status: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold
    private val seeds = FakeSeedStore()

    private val loopback = InetAddress.getByName("127.0.0.1")

    private fun bothHouseholds() = listOf(
        Discovery.DiscoveredPlayer(otherId, loopback, otherHousehold),
        Discovery.DiscoveredPlayer(fake.id, loopback, fake.householdId),
    )

    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        val zp = FakePlayer.fixtureText("status.zp.xml")
        val otherHost = PlayerNames.localHostname(otherId)!!
        status = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path != "/status/zp") return MockResponse().setResponseCode(404)
                    val room = if (request.getHeader("Host").orEmpty().startsWith(otherHost, ignoreCase = true)) "Office" else "Kitchen"
                    return MockResponse().setBody(zp.replace("<ZoneName>Dining Room</ZoneName>", "<ZoneName>$room</ZoneName>"))
                }
            }
            start(loopback, 0)
        }
        scope = CoroutineScope(SupervisorJob())
        val book = PlayerAddressBook()
        household = SonosHousehold(
            scope = scope,
            addressBook = book,
            multicast = MulticastGate.None,
            client = LanHttp.client(book),
            seeds = seeds,
            port = fake.port,
            upnpPort = status.port,
            ssdp = { bothHouseholds() },
        )
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        fake.shutdown()
        status.shutdown()
    }

    @Test fun `two households answering is a question, not a guess`() = runBlocking<Unit> {
        runCatching { household.connect() }
        val state = household.state.value
        assertFalse("must not join whichever answered first", state.connected)
        assertEquals(listOf("Kitchen", "Office"), state.householdChoices.map { it.label })
        assertEquals(setOf(fake.householdId, otherHousehold), state.householdChoices.map { it.householdId }.toSet())
        assertEquals(null, seeds.held)
    }

    @Test fun `the chosen household is connected and remembered`() = runBlocking<Unit> {
        runCatching { household.connect() }
        val choice = household.state.value.householdChoices.first { it.label == "Kitchen" }
        household.chooseHousehold(choice)
        val state = withTimeout(10_000) { household.state.first { it.connected } }
        assertEquals(fake.householdId, state.householdId)
        assertTrue(state.householdChoices.isEmpty())
        assertEquals(fake.id, seeds.held?.id)
    }

    /** The office case, SSDP dropped, with two systems on it: mDNS must not guess either. */
    @Test fun `two households found by mDNS are a question too`() = runBlocking<Unit> {
        val book = PlayerAddressBook()
        val mdnsOnly = SonosHousehold(
            scope = scope, addressBook = book, multicast = MulticastGate.None,
            mdns = { bothHouseholds() }, client = LanHttp.client(book),
            port = fake.port, upnpPort = status.port, ssdp = { emptyList() },
        )
        try {
            runCatching { mdnsOnly.connect() }
            val state = mdnsOnly.state.value
            assertFalse(state.connected)
            assertEquals(listOf("Kitchen", "Office"), state.householdChoices.map { it.label })
        } finally {
            mdnsOnly.disconnect()
        }
    }

    /** One household with several players is the ordinary case, and asks nothing. */
    @Test fun `several players of one household need no choice`() = runBlocking<Unit> {
        val single = SonosHousehold(
            scope = scope, addressBook = PlayerAddressBook(), multicast = MulticastGate.None,
            port = fake.port, upnpPort = status.port,
            ssdp = {
                listOf(
                    Discovery.DiscoveredPlayer(fake.id, loopback, fake.householdId),
                    Discovery.DiscoveredPlayer(fake.id, loopback, fake.householdId),
                )
            },
        )
        try {
            single.connect()
            withTimeout(10_000) { single.state.first { it.connected } }
        } finally {
            single.disconnect()
        }
    }
}
