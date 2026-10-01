package com.rahga.x2rock.lan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress

/**
 * A change the player did not answer for is looked for before it is called a failure, and a
 * change the player refused is not.
 *
 * Both halves rest on the same hardware fact: a Beam on its TV input stalled past the reply
 * timeout for 14–20s and then applied a regroup (x2rock, "The Beam stall"), and a soundbar
 * taking its TV input as a group *member* hands coordination over before the player asked
 * can answer. [FakePlayer.holdRepliesTo] stands in for the stall; a UPnP fake that drops the
 * connection stands in for the lost handoff reply.
 */
class UnansweredChangeTest {

    private companion object {
        const val SETTLE = 1_500L
    }

    private lateinit var fake: FakePlayer
    private lateinit var upnp: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold
    /**
     * What the UPnP fake does with `SetAVTransportURI`. By default it reads the request and
     * hangs up without answering: the switch was asked for, and the reply was lost. (Not
     * `DISCONNECT_AT_START`, which MockWebServer honours only from a queued response — from a
     * dispatcher it answered 200, and the first version of these tests passed against that.)
     */
    @Volatile private var avTransport: () -> MockResponse = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }

    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        upnp = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = avTransport()
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
            settleMillis = SETTLE,
        )
        runBlocking {
            household.connect(Discovery.DiscoveredPlayer(fake.id, InetAddress.getByName("127.0.0.1"), fake.householdId))
            withTimeout(5_000) { household.state.first { it.connected } }
        }
    }

    @After fun tearDown() {
        fake.releaseReplies()
        household.disconnect()
        scope.cancel()
        fake.shutdown()
        upnp.shutdown()
    }

    private fun group(name: String) = household.state.value.groups.first { it.name == name }


    // ---------------------------------------------------------------- grouping

    @Test fun `an unanswered regroup that lands is a success`() = runBlocking<Unit> {
        fake.holdRepliesTo("modifyGroupMembers")
        val call = scope.async(Dispatchers.IO) {
            household.modifyGroupMembers(group("Kitchen").id, listOf(group("Guest TV").coordinatorId), emptyList())
        }
        fake.awaitCommand { it.get("command")?.asString == "modifyGroupMembers" }
        // Announced on the seed's socket; Kitchen's is the one stalled.
        fake.pushTopology(FakePlayer.groupedTopology(coordinatorRoom = "Kitchen", memberRoom = "Guest TV"))
        withTimeout(10_000) { call.await() }
    }

    @Test fun `an unanswered regroup that never lands fails, after looking`() = runBlocking<Unit> {
        fake.holdRepliesTo("modifyGroupMembers")
        val call = scope.async(Dispatchers.IO) {
            runCatching {
                household.modifyGroupMembers(group("Kitchen").id, listOf(group("Guest TV").coordinatorId), emptyList())
            }
        }
        val failure = withTimeout(15_000) { call.await() }.exceptionOrNull()
        assertTrue("expected a failure, got $failure", failure is IOException && failure !is ReplyTimeoutException)
        assertTrue(failure!!.message!!, "did not answer" in failure.message!!)
    }

    /** The push can be missed — the seed may be the player that stalled — so it asks once more. */
    @Test fun `an unanswered regroup seen only by asking again is a success`() = runBlocking<Unit> {
        fake.holdRepliesTo("modifyGroupMembers")
        val call = scope.async(Dispatchers.IO) {
            household.modifyGroupMembers(group("Kitchen").id, listOf(group("Guest TV").coordinatorId), emptyList())
        }
        fake.awaitCommand { it.get("command")?.asString == "modifyGroupMembers" }
        fake.serveTopology(FakePlayer.groupedTopology(coordinatorRoom = "Kitchen", memberRoom = "Guest TV"))
        withTimeout(15_000) { call.await() }
    }

    /** A refusal is the player's answer. Nothing is waited for, and nothing is asked again. */
    @Test fun `a refused regroup fails at once`() = runBlocking<Unit> {
        fake.refuse("modifyGroupMembers")
        fake.clearHistory()
        val started = System.nanoTime()
        val failure = runCatching {
            household.modifyGroupMembers(group("Kitchen").id, listOf(group("Guest TV").coordinatorId), emptyList())
        }.exceptionOrNull()
        val tookMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue("expected the refusal itself, got $failure", failure is SonosCommandException)
        assertTrue("a refusal waited ${tookMillis}ms", tookMillis < SETTLE)
        assertEquals(0, fake.commandsNamed("getGroups"))
    }

    // ---------------------------------------------------------------- TV input
    //
    // Living Room's Beam joins Kitchen, so the soundbar is a member and the coordinator the
    // switch is sent to is a One SL — the handoff in which the reply is lost.

    private fun beamInKitchen(): String = runBlocking {
        fake.pushTopology(FakePlayer.groupedTopology(coordinatorRoom = "Kitchen", memberRoom = "Living Room"))
        withTimeout(10_000) { household.state.first { s -> s.groups.none { it.name == "Living Room" } } }
        group("Kitchen").id
    }

    @Test fun `a TV switch whose reply is lost is confirmed by the input arriving`() = runBlocking<Unit> {
        val kitchen = beamInKitchen()
        val call = scope.async(Dispatchers.IO) { household.useTvInput(kitchen) }
        // Only once the request has gone and its reply been lost, so the event is what ends it.
        upnp.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
        fake.pushFixture("tvMetadataStatus", kitchen)
        withTimeout(10_000) { call.await() }
    }

    @Test fun `a TV switch whose reply is lost and that never arrives fails`() = runBlocking<Unit> {
        val kitchen = beamInKitchen()
        val failure = runCatching { withTimeout(10_000) { household.useTvInput(kitchen) } }.exceptionOrNull()
        assertTrue("expected a failure, got $failure", failure is IOException)
        assertTrue(failure!!.message!!, "has not switched" in failure.message!!)
    }

    /** An error the player answered with is an answer, and is not waited out. */
    @Test fun `a TV switch the player answers with an error fails at once`() = runBlocking<Unit> {
        val kitchen = beamInKitchen()
        avTransport = { MockResponse().setResponseCode(403) }
        val started = System.nanoTime()
        val failure = runCatching { household.useTvInput(kitchen) }.exceptionOrNull()
        val tookMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue("expected the player's refusal, got $failure", failure is UpnpRefusedException)
        assertTrue("a refusal waited ${tookMillis}ms", tookMillis < SETTLE)
    }
}
