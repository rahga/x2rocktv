package com.rahga.x2rock.lan

import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * One attempt owns the session at a time (review, 2026-10-23). A connect whose caller stops
 * waiting carries on; a connect a reconnect replaces stops; and a read begun in a session that
 * has since been replaced says nothing about the new one. Each began as a probe in the review.
 */
class SupersededConnectTest {

    private lateinit var fake: FakePlayer
    private lateinit var scope: CoroutineScope
    private val release = CompletableDeferred<Unit>()

    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After fun tearDown() {
        release.complete(Unit)
        fake.releaseReplies()
        scope.cancel()
        fake.shutdown()
    }

    /**
     * The first connect is still waiting on discovery when the network changes; the reconnect
     * connects, and then the first discovery comes back. Before, that attempt went on to set up
     * over the new session — a second `groups:1` subscription, from a seed that on a real network
     * change belongs to the previous network (P-72).
     */
    @Test(timeout = 25_000) fun `a connect a network change replaced does not set up over the new session`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val sweeps = AtomicInteger()
        val household = fakeHousehold(fake, scope, ssdp = {
            if (sweeps.incrementAndGet() == 1) { entered.complete(Unit); release.await() }
            listOf(fake.seed)
        })
        try {
            val original = async(Dispatchers.Default) { household.connect() }
            withTimeout(5_000) { entered.await() }
            household.onNetworkChanged()
            withTimeout(10_000) { household.state.first { it.connected } }
            fake.awaitCommand(timeoutMillis = 10_000) {
                it.get("command")?.asString == "subscribe" && it.get("namespace")?.asString == "groups:1"
            }
            release.complete(Unit)
            // Returns once its set-up has finished or been stopped: either way, nothing more of
            // it is still to run.
            withTimeout(10_000) { original.await() }
            assertEquals("the replaced connect subscribed the topology again", 1, fake.commandsNamed("subscribe", "groups:1"))
            assertTrue(household.state.value.connected)
        } finally {
            household.disconnect()
        }
    }

    /**
     * The channel receiver's budget runs out while the home screen waits on the same connect.
     * The connect ran in the receiver's coroutine, so the timeout cancelled it, and the screen
     * was left not connected with no error and no Retry (P-73).
     */
    @Test(timeout = 20_000) fun `a caller that stops waiting does not abandon the connect`() = runBlocking {
        val household = fakeHousehold(fake, scope)
        try {
            fake.holdRepliesTo("getGroups")
            val receiver = async(Dispatchers.IO) { household.connect(fake.seed) }
            fake.awaitCommand("getGroups", 5_000)
            val screen = async(Dispatchers.IO) { household.connect(fake.seed) }
            receiver.cancel()
            fake.releaseReplies()
            withTimeout(10_000) { screen.await() }
            val state = withTimeout(10_000) { household.state.first { it.connected } }
            assertNull(state.error)
        } finally {
            household.disconnect()
        }
    }

    /**
     * A sign-ins read is under way when the network changes. It finishes after the new session
     * stood up, finding no Apple Music, and used to mark the new session as read, so its own
     * household was never asked (P-77).
     */
    @Test(timeout = 25_000) fun `a sign-ins read from a replaced session leaves the new one unread`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val reads = AtomicInteger()
        val household = fakeHousehold(fake, scope, ssdp = { listOf(fake.seed) }, accountCapture = {
            if (reads.incrementAndGet() == 1) { entered.complete(Unit); release.await() }
            null
        })
        try {
            household.connectTo(fake)
            val original = async(Dispatchers.Default) { household.storedAppleMusicAccount() }
            withTimeout(5_000) { entered.await() }
            fake.clearHistory()
            household.onNetworkChanged()
            fake.awaitCommand(timeoutMillis = 10_000) {
                it.get("command")?.asString == "subscribe" && it.get("namespace")?.asString == "groups:1"
            }
            withTimeout(10_000) { household.state.first { it.connected && it.groups.isNotEmpty() } }
            release.complete(Unit)
            withTimeout(5_000) { original.await() }
            household.storedAppleMusicAccount()
            assertEquals("the new session took the old session's read as its own", 2, reads.get())
        } finally {
            household.disconnect()
        }
    }
}
