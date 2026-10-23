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
}
