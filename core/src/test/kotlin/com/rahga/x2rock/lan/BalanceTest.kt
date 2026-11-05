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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A stereo pair's balance (issue #2): whether a room is a pair, from `zones:1`'s channel maps, and its
 * two channel levels over UPnP, read from Media Room's captured `GetVolume` replies.
 */
class BalanceTest {

    @Test fun `a balance is the channel turned down, signed by the side it leans to`() {
        assertEquals(0, Balance.of(100, 100))
        assertEquals(-40, Balance.of(100, 60))
        assertEquals(40, Balance.of(60, 100))
        assertEquals(100 to 100, Balance.channels(0))
        assertEquals(100 to 60, Balance.channels(-40))
        assertEquals(60 to 100, Balance.channels(40))
        assertEquals("all the way is one speaker alone", 0 to 100, Balance.channels(100))
        Balance.RANGE.forEach { b -> Balance.channels(b).let { (l, r) -> assertEquals(b, Balance.of(l, r)) } }
    }

    /** `GetVolume` for `LF`, as Media Room's One SL answered it (97.1, 2026-11-05). */
    @Test fun `a channel's level is read from its reply`() = runBlocking<Unit> {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setBody(FakePlayer.fixtureText("GetVolume.LF.xml")))
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        val book = PlayerAddressBook().apply { register("sonos-media.local", "127.0.0.1") }
        val upnp = Upnp(LanHttp.client(book), server.port)
        assertEquals(100, upnp.channelVolume("sonos-media.local", "LF"))
        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent, "<u:GetVolume" in sent && "<Channel>LF</Channel>" in sent)
        server.shutdown()
    }

    /**
     * Dining Room is the captured stereo pair; Living Room's Sub and surrounds and Bedroom's surrounds
     * make neither a pair. The pair's balance is read and written on its own player; anything else
     * has none, and nothing is asked of it.
     */
    @Test fun `only a stereo pair has a balance, read and written on its own player`() = runBlocking<Unit> {
        val actions = CopyOnWriteArrayList<String>()
        val writes = CopyOnWriteArrayList<String>()
        val upnp = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val action = soapAction(request)
                    actions += action
                    val text = request.body.readUtf8()
                    val channel = Regex("<Channel>(\\w+)</Channel>").find(text)?.groupValues?.get(1)
                    return when (action) {
                        // Left at full, the right turned down to 60: a pair leaning left by 40.
                        "GetVolume" -> MockResponse().setBody(
                            FakePlayer.fixtureText("GetVolume.$channel.xml").let { if (channel == "RF") it.replace(">100<", ">60<") else it },
                        )
                        "SetVolume" -> {
                            writes += "$channel=" + Regex("<DesiredVolume>(\\d+)<").find(text)!!.groupValues[1]
                            MockResponse().setBody("")
                        }
                        else -> unknownAction()
                    }
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        val fake = FakePlayer().also { it.start() }
        val scope = CoroutineScope(SupervisorJob())
        val household = fakeHousehold(fake, scope, addressBook = PlayerAddressBook(), upnpPort = upnp.port)
        try {
            household.connect(fake.seed)
            withTimeout(10_000) { household.state.first { it.groups.isNotEmpty() } }
            fake.pushFixture("activeZonesChange")
            val state = withTimeout(5_000) { household.state.first { it.bondedChannels.isNotEmpty() } }
            fun room(name: String) = state.groups.first { it.name == name }.coordinatorId
            assertTrue(state.isStereoPair(room("Dining Room")))
            listOf("Living Room", "Bedroom", "Kitchen").forEach { assertFalse(it, state.isStereoPair(room(it))) }

            assertNull(household.balance(room("Kitchen")))
            assertEquals("nothing asked of a room that is no pair", emptyList<String>(), actions.toList())

            assertEquals(-40, household.balance(room("Dining Room")))
            household.setBalance(room("Dining Room"), 30)
            assertEquals(listOf("LF=70", "RF=100"), writes.toList())
        } finally {
            household.disconnect()
            scope.cancel()
            fake.shutdown()
            upnp.shutdown()
        }
    }
}
