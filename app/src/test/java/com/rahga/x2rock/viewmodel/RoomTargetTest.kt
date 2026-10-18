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
