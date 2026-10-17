package com.rahga.x2rock.lan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * A group is spoken for by its coordinator alone. Splitting a party on the Shield, Guest TV — a
 * member on its way out — answered a subscription with a snapshot whose header still named the
 * group it was leaving, Living Room's, and no track. Applied by header alone, that blanked Living
 * Room's row and pane to "Playing" for good: a host keeps its group id, so nothing ever subscribed
 * it afresh (2026-10-17).
 */
class MemberEventTest {

    private lateinit var fake: FakePlayer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold

    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        scope = CoroutineScope(SupervisorJob())
        household = fakeHousehold(fake, scope, addressBook = PlayerAddressBook())
        runBlocking { household.connectTo(fake) }
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        fake.shutdown()
    }

    @Test fun `a member's empty snapshot naming the group does not blank the coordinator's track`() = runBlocking<Unit> {
        fake.pushTopology(FakePlayer.groupedTopology(coordinatorRoom = "Kitchen", memberRoom = "Guest TV"))
        val group = withTimeout(10_000) {
            household.state.first { s -> s.groups.any { it.playerIds.size == 2 } }
        }.groups.first { it.playerIds.size == 2 }
        val member = group.playerIds.first { it != group.coordinatorId }
        fake.pushFixture("metadataStatus", group.id)
        val playing = withTimeout(5_000) { household.groupStates.first { it[group.id]?.track != null } }[group.id]!!.track!!.name
        // On the member's own socket, open once its level has been subscribed on it.
        withTimeout(5_000) {
            while (runCatching { fake.pushGroupEventFromMember(member, group.id, "tvMetadataStatus") }.isFailure) delay(50)
        }
        delay(1_000)

        assertEquals(playing, household.groupStates.value[group.id]?.track?.name)
    }

    /** The coordinator's own word still counts, empty or not: a TV input really has no track. */
    @Test fun `the coordinator's empty snapshot still clears the track`() = runBlocking<Unit> {
        val group = household.state.value.groups.first()
        fake.pushFixture("metadataStatus", group.id)
        withTimeout(5_000) { household.groupStates.first { it[group.id]?.track != null } }
        fake.pushFixture("tvMetadataStatus", group.id)
        withTimeout(5_000) { household.groupStates.first { it[group.id]?.track == null } }
    }
}
