package com.rahga.x2rock.lan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * A room's analog line-in. **Untested on hardware**: no player in either household has one, so
 * the topology here is the captured one with `LINE_IN` added to one player's capabilities — a
 * real player's every other field, and a capability string taken from Sonos's published list,
 * never seen in a capture. This checks that the command names the right player and goes to its
 * group; whether a real Port answers it as expected is owed (PUNCH P-14).
 */
class LineInTest {

    private lateinit var fake: FakePlayer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold

    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        scope = CoroutineScope(SupervisorJob())
        val book = PlayerAddressBook()
        household = fakeHousehold(fake, scope, addressBook = book)
        runBlocking { household.connectTo(fake, 10_000) }
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        fake.shutdown()
    }

    /** The captured topology, with [playerId] reporting a line-in. */
    private fun withLineIn(playerId: String) = FakePlayer.reachableTopology().also { topology ->
        topology.getAsJsonArray("players").map { it.asJsonObject }
            .first { it.get("id").asString == playerId }
            .getAsJsonArray("capabilities").add(LINE_IN)
    }

    @Test fun `a room with no line-in offers none`() {
        val group = household.state.value.groups.first { it.id == fake.groupId(household) }
        assertNull(household.state.value.lineInPlayer(group))
    }

    @Test fun `line-in is loaded on the room's group, naming the player that has it`() = runBlocking<Unit> {
        val groupId = fake.groupId(household)
        fake.pushTopology(withLineIn(fake.id))
        withTimeout(5_000) {
            household.state.first { s -> s.groups.firstOrNull { it.id == groupId }?.let(s::lineInPlayer) != null }
        }
        household.useLineIn(groupId)
        val header = fake.awaitCommand("loadLineIn", 3_000)
        assertEquals("playback:1", header.get("namespace").asString)
        assertEquals(groupId, header.get("groupId").asString)
        val body = fake.lastCommandBody("loadLineIn")!!
        assertEquals(fake.id, body.get("deviceId").asString)
        assertEquals(true, body.get("playOnCompletion").asBoolean)
    }
}
