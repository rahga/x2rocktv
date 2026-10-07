package com.rahga.x2rock.lan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * A preset's first step: these speakers, and only these, in one group led by the first of them,
 * answered with the group's id as the topology shows it — a new id, since a regroup mints one.
 *
 * The captured topology has one player per group, so each case regroups with
 * [FakePlayer.groupedTopology], which rearranges the real capture rather than inventing a shape.
 * The fake does not regroup on its own: each test pushes the topology a real player would send
 * once the command it just checked had landed.
 */
class GatherRoomsTest {

    private lateinit var fake: FakePlayer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold

    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        scope = CoroutineScope(SupervisorJob())
        val book = PlayerAddressBook()
        household = SonosHousehold(
            scope = scope,
            addressBook = book,
            multicast = MulticastGate.None,
            client = LanHttp.client(book),
            port = fake.port,
            settleMillis = 5_000,
        )
        runBlocking { household.connectTo(fake) }
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        fake.shutdown()
    }

    private fun group(name: String) = household.state.value.groups.first { it.name == name }

    private fun body(command: String, field: String) =
        fake.lastCommandBody(command)!!.getAsJsonArray(field).map { it.asString }

    @Test fun `the leader takes in the missing room and answers the new group id`() = runBlocking<Unit> {
        val kitchen = group("Kitchen")
        val guest = group("Guest TV")
        fake.clearHistory()

        val gathered = async(Dispatchers.IO) { household.gatherRooms(listOf(kitchen.coordinatorId, guest.coordinatorId)) }
        val command = fake.awaitCommand(timeoutMillis = 5_000) { it.get("command")?.asString == "modifyGroupMembers" }
        assertEquals("asked of the leader's own group", kitchen.id, command.get("groupId").asString)
        assertEquals(guest.playerIds, body("modifyGroupMembers", "playerIdsToAdd"))
        assertEquals(emptyList<String>(), body("modifyGroupMembers", "playerIdsToRemove"))

        fake.pushTopology(FakePlayer.groupedTopology(coordinatorRoom = "Kitchen", memberRoom = "Guest TV"))
        val id = withTimeout(10_000) { gathered.await() }
        val now = household.state.value.groups.first { it.id == id }
        assertEquals(kitchen.coordinatorId, now.coordinatorId)
        assertEquals(setOf(kitchen.coordinatorId, guest.coordinatorId), now.playerIds.toSet())
        assertTrue("a regroup mints a new id", id != kitchen.id)
    }

    @Test fun `a leader grouped under someone else leaves first, then gathers`() = runBlocking<Unit> {
        fake.pushTopology(FakePlayer.groupedTopology(coordinatorRoom = "Kitchen", memberRoom = "Guest TV"))
        val host = withTimeout(10_000) {
            household.state.first { s -> s.groups.any { it.playerIds.size > 1 } }
        }.groups.first { it.playerIds.size > 1 }
        val guestPlayer = host.playerIds.first { it != host.coordinatorId }
        fake.clearHistory()

        val gathered = async(Dispatchers.IO) { household.gatherRooms(listOf(guestPlayer, host.coordinatorId)) }
        val leave = fake.awaitCommand(timeoutMillis = 5_000) { it.get("command")?.asString == "modifyGroupMembers" }
        assertEquals(host.id, leave.get("groupId").asString)
        assertEquals(listOf(guestPlayer), body("modifyGroupMembers", "playerIdsToRemove"))

        // Apart again, as the captured household is; then the leader gathers the other.
        fake.pushTopology(FakePlayer.reachableTopology())
        val join = fake.awaitCommand(timeoutMillis = 5_000) { it.get("command")?.asString == "modifyGroupMembers" }
        val guestGroup = household.state.value.groups.first { it.coordinatorId == guestPlayer }
        assertEquals("then from the leader's own group", guestGroup.id, join.get("groupId").asString)
        assertEquals(listOf(host.coordinatorId), body("modifyGroupMembers", "playerIdsToAdd"))

        fake.pushTopology(FakePlayer.groupedTopology(coordinatorRoom = "Guest TV", memberRoom = "Kitchen"))
        val id = withTimeout(10_000) { gathered.await() }
        assertEquals(guestPlayer, household.state.value.groups.first { it.id == id }.coordinatorId)
    }

    @Test fun `rooms already together are answered without a command`() = runBlocking<Unit> {
        val kitchen = group("Kitchen")
        fake.clearHistory()
        assertEquals(kitchen.id, household.gatherRooms(listOf(kitchen.coordinatorId)))
        assertEquals(0, fake.commandsNamed("modifyGroupMembers"))
    }

    @Test fun `a room not on the network fails the preset rather than gathering the rest`() = runBlocking<Unit> {
        val kitchen = group("Kitchen")
        fake.clearHistory()
        try {
            household.gatherRooms(listOf(kitchen.coordinatorId, "RINCON_000000000000001400"))
            fail("gathered without a room")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!, e.message!!.contains("isn't on the network"))
        }
        assertEquals(0, fake.commandsNamed("modifyGroupMembers"))
    }
}
