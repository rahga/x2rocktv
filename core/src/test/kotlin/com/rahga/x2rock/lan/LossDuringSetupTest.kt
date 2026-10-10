package com.rahga.x2rock.lan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What a set-up does when something happens to it part-way: it is cancelled, it loses the
 * speaker it runs through, or a coordinator goes while a reconnect is still running. Each used
 * to leave something behind — an error written for a cancellation, two set-ups tearing each
 * other down, a group marked subscribed over a dead socket (review, 2026-10-09).
 */
class LossDuringSetupTest {
    private lateinit var fake: FakePlayer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold

    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        scope = CoroutineScope(SupervisorJob())
        household = SonosHousehold(
            scope = scope, addressBook = PlayerAddressBook(), multicast = MulticastGate.None,
            seeds = FakeSeedStore(keyedByNetwork = true, held = fake.seed), port = fake.port,
            ssdp = { listOf(fake.seed) },
        )
    }

    @After fun tearDown() {
        fake.releaseReplies()
        household.disconnect()
        scope.cancel()
        fake.shutdown()
    }

    /** The reconnect loop's job, which the household keeps to itself. */
    private fun reconnectJob(): Job? =
        SonosHousehold::class.java.getDeclaredField("reconnectJob").apply { isAccessible = true }.get(household) as Job?

    @Test(timeout = 20_000) fun `a cancelled connect leaves no error behind`() = runBlocking {
        fake.holdRepliesTo("getGroups")
        val connecting = async(Dispatchers.IO) { household.connect(fake.seed) }
        fake.awaitCommand("getGroups", 5_000)
        connecting.cancel()
        fake.releaseReplies()
        delay(500)
        assertNull("a cancellation was written as an error: ${household.state.value.error}", household.state.value.error)
        assertFalse(household.state.value.connected)
    }

    /**
     * The seed dies during a first connect()'s best-effort tail — not under the reconnect loop,
     * so its loss starts a reconnect of its own. The first attempt must then leave that
     * reconnect's pool alone and say nothing, rather than tear it down and write its error.
     */
    @Test(timeout = 30_000) fun `a seed lost while a first connect sets up is reconnected, with no error left`() = runBlocking {
        fake.holdRepliesTo("getSettingsGroup")
        val connecting = async(Dispatchers.IO) { runCatching { household.connect(fake.seed) } }
        fake.awaitCommand("getSettingsGroup", 5_000)
        fake.dropConnection(fake.id)
        fake.releaseReplies()
        connecting.await()
        withTimeout(10_000) { reconnectJob()?.join() }
        val state = withTimeout(10_000) { household.state.first { it.connected } }
        assertNull("the superseded attempt wrote its failure over the reconnect: ${state.error}", state.error)
        assertFalse(state.reconnecting)
        assertTrue("the reconnect's own groups subscription is missing", fake.commandsNamed("subscribe", "groups:1") >= 2)
    }

    /**
     * A coordinator other than the seed goes while a reconnect is setting up. Its loss is not
     * acted on then — the reconnect is already running — but it must still be forgotten, or the
     * next catch-up skips its group as subscribed and the room sits frozen.
     */
    @Test(timeout = 30_000) fun `a coordinator lost while a reconnect sets up is subscribed again`() = runBlocking {
        val state = household.connectTo(fake)
        // The whole first set-up, not just getGroups: `connected` is set before the tail runs,
        // and the hold below must catch the reconnect's settings read, not the first connect's.
        withTimeout(5_000) { while (fake.commandsNamed("subscribe", "playerVolume:1") < state.players.size) delay(50) }
        fake.clearHistory()
        fake.holdRepliesTo("getSettingsGroup")
        household.onNetworkChanged()
        fake.awaitCommand("getSettingsGroup", 5_000)
        val other = state.groups.first { it.coordinatorId != fake.id }
        val before = fake.commandsNamed("subscribe", "playback:1")
        assertEquals("the reconnect should have subscribed every group before its settings read", state.groups.size, before)
        fake.dropConnection(other.coordinatorId)
        delay(300)
        fake.releaseReplies()
        withTimeout(10_000) { reconnectJob()?.join() }
        val deadline = System.currentTimeMillis() + 8_000
        while (fake.commandsNamed("subscribe", "playback:1") <= before && System.currentTimeMillis() < deadline) delay(100)
        assertTrue(
            "${other.name}'s group was left marked subscribed over its dead socket",
            fake.commandsNamed("subscribe", "playback:1") > before,
        )
        assertEquals(before + 1, fake.commandsNamed("subscribe", "playback:1"))
    }
}
