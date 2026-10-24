package com.rahga.x2rock.lan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
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
import java.util.concurrent.Executors

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
        household = fakeHousehold(
            fake,
            scope,
            seeds = FakeSeedStore(keyedByNetwork = true, held = fake.seed),
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

    /**
     * A caller leaving no longer cancels a connect (P-73, `SupersededConnectTest`); a disconnect
     * does, and that cancellation is not a failure either.
     */
    @Test(timeout = 20_000) fun `a cancelled connect leaves no error behind`() = runBlocking {
        fake.holdRepliesTo("getGroups")
        val connecting = async(Dispatchers.IO) { household.connect(fake.seed) }
        fake.awaitCommand("getGroups", 5_000)
        household.disconnect()
        fake.releaseReplies()
        withTimeout(5_000) { connecting.await() }
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
        // Past the catch-up the reconnect's groups:1 subscription starts: when that ran after the
        // drop it re-subscribed the group by luck, and when it ran first nothing did (CI, run
        // 38199691088). Waiting makes the bad order the one tested, every time.
        delay(1_500)
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

    /**
     * The same loss, landing while the group's last subscribe reply is still out: a firmware that
     * ignores `sleepTimer:1` keeps it out for the whole reply timeout. The group used to be marked
     * subscribed only after that reply, so the loss found nothing to unmark and the mark that
     * followed stood over the dead socket (P-103).
     */
    @Test(timeout = 30_000) fun `a coordinator lost while its last subscribe reply is out is subscribed again`() = runBlocking {
        // One thread, as a TV's main-bound scope can be: the loss's handler is then dispatched
        // ahead of the waiter it failed, which is the order that left the mark standing.
        val one = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val oneScope = CoroutineScope(SupervisorJob() + one)
        household.disconnect()
        household = fakeHousehold(
            fake,
            oneScope,
            seeds = FakeSeedStore(keyedByNetwork = true, held = fake.seed),
            ssdp = { listOf(fake.seed) },
        )
        try {
            lostWhileTheLastReplyIsOut()
        } finally {
            household.disconnect()
            oneScope.cancel()
            one.close()
        }
    }

    private suspend fun lostWhileTheLastReplyIsOut() {
        val state = household.connectTo(fake)
        withTimeout(5_000) { while (fake.commandsNamed("subscribe", "playerVolume:1") < state.players.size) delay(50) }
        val other = state.groups.first { it.coordinatorId != fake.id }
        fake.clearHistory()
        fake.holdReplyAside { header ->
            header.get("namespace")?.asString == "sleepTimer:1" && header.get("groupId")?.asString == other.id
        }
        household.onNetworkChanged()
        fake.awaitCommand(5_000) { it.get("namespace")?.asString == "sleepTimer:1" && it.get("groupId")?.asString == other.id }
        // The other three are answered at once; the drop has to land after them, before the fourth.
        delay(300)
        val subscribes = {
            fake.commandsMatching {
                it.get("namespace")?.asString == "playback:1" && it.get("command")?.asString == "subscribe" &&
                    it.get("groupId")?.asString == other.id
            }
        }
        val before = subscribes()
        fake.dropConnection(other.coordinatorId)
        fake.releaseReplies()
        withTimeout(10_000) { reconnectJob()?.join() }
        val deadline = System.currentTimeMillis() + 8_000
        while (subscribes() <= before && System.currentTimeMillis() < deadline) delay(100)
        assertTrue("${other.name}'s group was left marked subscribed over its dead socket", subscribes() > before)
    }
}
