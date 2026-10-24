package com.rahga.x2rock.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.rahga.x2rock.lan.FakePlayer
import com.rahga.x2rock.lan.PlayerAddressBook
import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.lan.connectTo
import com.rahga.x2rock.lan.fakeHousehold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A screen's room, followed by its speakers across a regroup, including when the screen was built
 * before the household connected, as a screen Android restores is (P-44).
 */
class RoomTargetTest {
    private val fake = FakePlayer().also { it.start() }
    private val scope = CoroutineScope(SupervisorJob())
    private val households = mutableListOf<SonosHousehold>()

    private fun household() = fakeHousehold(fake, scope, addressBook = PlayerAddressBook()).also { households += it }

    @After fun tearDown() {
        households.forEach { it.disconnect() }
        scope.cancel()
        fake.shutdown()
    }

    private val kitchen = FakePlayer.reachableTopology().getAsJsonArray("groups").map { it.asJsonObject }
        .first { it.get("name").asString == "Kitchen" }

    /** Regroup Kitchen into Guest TV, so Kitchen's group id is gone; the group now holding it. */
    private suspend fun regroup(household: SonosHousehold): String {
        fake.pushTopology(FakePlayer.groupedTopology(coordinatorRoom = "Guest TV", memberRoom = "Kitchen"))
        val coordinator = kitchen.get("coordinatorId").asString
        return withTimeout(5_000) {
            household.state.first { s -> s.groups.none { it.id == kitchen.get("id").asString } }
        }.groups.first { coordinator in it.playerIds }.id
    }

    @Test fun `a target built before the household connected still follows a later regroup`() = runBlocking<Unit> {
        val household = household()
        val target = RoomTarget(household, SavedStateHandle(mapOf("groupId" to kitchen.get("id").asString)))
        household.connectTo(fake)
        assertEquals("the opened group, once it is there", kitchen.get("id").asString, target.currentOrNull())

        val now = regroup(household)

        assertEquals(now, target.currentOrNull())
    }

    /**
     * Opened while its group id was already gone — the topology empty through a reconnect, then new
     * ids — a screen handed only the id could never learn its room. The route carries the speakers
     * now, and the room is found by them (P-88).
     */
    @Test fun `a target opened on a gone id finds its room by the speakers handed on`() = runBlocking<Unit> {
        val household = household()
        household.connectTo(fake)
        val now = regroup(household)
        val route = RoomRoute(kitchen.get("id").asString, kitchen.get("coordinatorId").asString, listOf(kitchen.get("coordinatorId").asString))
        val target = RoomTarget(household, SavedStateHandle(mapOf("groupId" to route.groupId, RoomRoute.SPEAKERS to route.speakers)))

        assertEquals(now, target.currentOrNull())
    }

    /**
     * Opened on Guest TV's party, which Kitchen — the room chosen — only joined: when the party splits
     * the host keeps its id, and the screen goes with Kitchen, as the home screen and the player do (P-71).
     */
    @Test fun `a target opened on a party its room only joined leaves it with the room`() = runBlocking<Unit> {
        val household = household()
        household.connectTo(fake)
        val party = regroup(household)
        val coordinator = kitchen.get("coordinatorId").asString
        val target = RoomTarget(
            household,
            SavedStateHandle(mapOf("groupId" to party, RoomRoute.SPEAKERS to RoomRoute(party, coordinator, listOf(coordinator)).speakers)),
        )
        assertEquals("the party, while Kitchen is in it", party, target.currentOrNull())

        fake.pushTopology(splitKeepingHost(party, hostRoom = "Guest TV", memberRoom = "Kitchen"))
        val alone = withTimeout(5_000) {
            household.state.first { s -> s.groups.size == 5 && s.groups.none { it.id == kitchen.get("id").asString } }
        }.groups.first { coordinator in it.playerIds }

        assertEquals(alone.id, target.currentOrNull())
        assertEquals("handed on with Kitchen's speakers", coordinator, target.forNavigation().coordinatorId)
    }

    /**
     * Android reclaimed the app while the room was regrouped elsewhere: the restored screen has only
     * its saved state, and its group id no longer exists. The speakers saved there still find it.
     */
    @Test fun `a restored target follows its saved speakers to a group formed while it was gone`() = runBlocking<Unit> {
        val saved = SavedStateHandle(mapOf("groupId" to kitchen.get("id").asString))
        val before = household()
        before.connectTo(fake)
        RoomTarget(before, saved).currentOrNull()
        before.disconnect()

        val after = household()
        after.connectTo(fake)
        val now = regroup(after)

        assertEquals(now, RoomTarget(after, saved).currentOrNull())
    }

    /** The captured topology without [room], its speaker gone, and Dining Room's group under a new id. */
    private fun without(room: String) = FakePlayer.reachableTopology().also { topology ->
        val groups = topology.getAsJsonArray("groups")
        val gone = groups.map { it.asJsonObject }.first { it.get("name").asString == room }
        val player = gone.get("coordinatorId").asString
        groups.remove(gone)
        topology.getAsJsonArray("players").let { players -> players.remove(players.first { it.asJsonObject.get("id").asString == player }) }
        groups.map { it.asJsonObject }.first { it.get("name").asString == "Dining Room" }.let { dining ->
            dining.addProperty("id", dining.get("id").asString.substringBeforeLast(":") + ":" + (System.nanoTime() % 1_000_000_000))
        }
    }

    /**
     * Opened on Kitchen alone; Dining Room joins it, then Kitchen drops off the network and Dining Room
     * is left. Home and the pane follow the room to Dining Room; the screen, frozen at what it opened
     * on, said "Kitchen is no longer in this system" for every press (P-106).
     */
    @Test fun `a target follows its room as it grows, and then to the speaker left`() = runBlocking<Unit> {
        val household = household()
        household.connectTo(fake)
        val target = RoomTarget(household, SavedStateHandle(mapOf("groupId" to kitchen.get("id").asString)))
        val coordinator = kitchen.get("coordinatorId").asString
        fake.pushTopology(FakePlayer.groupedTopology(coordinatorRoom = "Kitchen", memberRoom = "Dining Room"))
        val grown = withTimeout(5_000) {
            household.state.first { s -> s.groups.any { coordinator in it.playerIds && it.playerIds.size == 2 } }
        }.groups.first { coordinator in it.playerIds }
        assertEquals(grown.id, target.currentOrNull())

        fake.pushTopology(without("Kitchen"))
        val left = withTimeout(5_000) { household.state.first { s -> s.players.none { it.id == coordinator } } }
            .groups.first { it.name == "Dining Room" }
        assertEquals(left.id, target.currentOrNull())
    }

    /**
     * Opened on Guest TV's party, which Kitchen only joined: the party's name is the host's, so none
     * was kept, and once Kitchen left the household every press said "That room is no longer…" (P-106).
     */
    @Test fun `a target opened on a party names its own room once it is gone`() = runBlocking<Unit> {
        val household = household()
        household.connectTo(fake)
        val party = regroup(household)
        val coordinator = kitchen.get("coordinatorId").asString
        val target = RoomTarget(
            household,
            SavedStateHandle(mapOf("groupId" to party, RoomRoute.SPEAKERS to RoomRoute(party, coordinator, listOf(coordinator)).speakers)),
        )
        target.currentOrNull()
        fake.pushTopology(without("Kitchen"))
        withTimeout(5_000) { household.state.first { s -> s.players.none { it.id == coordinator } } }
        val failure = runCatching { target.current() }.exceptionOrNull()
        assertEquals("Kitchen is no longer in this system", failure?.message)
    }
}
