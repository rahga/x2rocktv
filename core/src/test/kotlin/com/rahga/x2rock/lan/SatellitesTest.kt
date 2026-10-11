package com.rahga.x2rock.lan

import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * A room's Sub and surrounds: whether it has them, from `zones:1`'s channel maps, and their EQ
 * values over UPnP. The channel maps are what decide, since every Sub EQ type answers on a speaker
 * that can have no Sub (x2rock, 2026-09-05).
 */
class SatellitesTest {

    /** The captured `activeZonesChange`: Living Room's Beam with a Sub and two surrounds, Bedroom's with surrounds. */
    @Test fun `a room has a Sub or surrounds only where its zone names them`() = runBlocking<Unit> {
        val fake = FakePlayer().also { it.start() }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val household = fakeHousehold(fake, scope)
        try {
            household.connectTo(fake)
            fake.pushFixture("activeZonesChange")
            val state = withTimeout(5_000) { household.state.first { it.bondedChannels.isNotEmpty() } }
            fun room(name: String) = state.groups.first { it.name == name }.coordinatorId
            assertTrue(state.hasSub(room("Living Room")))
            assertTrue(state.hasSurrounds(room("Living Room")))
            assertFalse("Bedroom's Beam has surrounds and no Sub", state.hasSub(room("Bedroom")))
            assertTrue(state.hasSurrounds(room("Bedroom")))
            assertFalse("a stereo pair's other half is neither", state.hasSub(room("Dining Room")) || state.hasSurrounds(room("Dining Room")))
            assertFalse(state.hasSub(room("Kitchen")) || state.hasSurrounds(room("Kitchen")))
            // A definitions change carries the same members without their state; it changes nothing.
            fake.pushFixture("zoneDefinitionsChange")
            delay(300)
            assertEquals(state.bondedChannels, household.state.value.bondedChannels)
        } finally {
            household.disconnect()
            scope.cancel()
            fake.shutdown()
        }
    }

    /** `GetEQ` as Kitchen answered it for `SubGain` (captured 2026-10-11). */
    @Test fun `an EQ value is read from its reply`() = runBlocking<Unit> {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setBody(FakePlayer.fixtureText("GetEQ.SubGain.xml").replace("<CurrentValue>0<", "<CurrentValue>-4<")))
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        val book = PlayerAddressBook().apply { register("sonos-kitchen.local", "127.0.0.1") }
        val upnp = Upnp(LanHttp.client(book), server.port)
        assertEquals(-4, upnp.eq("sonos-kitchen.local", "SubGain"))
        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent, "<EQType>SubGain</EQType>" in sent && "<u:GetEQ" in sent)
        server.shutdown()
    }
}
