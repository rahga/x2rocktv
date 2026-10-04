package com.rahga.x2rock.lan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * What a network change does with the remembered player.
 *
 * A memory kept for one player regardless of network is probably wrong after a change, so
 * it is skipped for discovery. A memory kept per network answers for the network the device
 * has just arrived on, so it is the best guess there is, and skipping it would spend a
 * discovery — 3s of SSDP, 4s more of mDNS where SSDP is dropped — on a player already known.
 */
class NetworkChangeTest {

    private lateinit var fake: FakePlayer
    private lateinit var scope: CoroutineScope
    private val sweeps = AtomicInteger()


    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        scope = CoroutineScope(SupervisorJob())
    }

    @After fun tearDown() {
        scope.cancel()
        fake.shutdown()
    }

    private fun seed() = fake.seed

    private fun household(keyed: Boolean) = SonosHousehold(
        scope = scope, addressBook = PlayerAddressBook(), multicast = MulticastGate.None,
        seeds = FakeSeedStore(keyed, seed()), port = fake.port,
        ssdp = { sweeps.incrementAndGet(); listOf(seed()) },
    )

    /** Connect, change network, and wait for the rebuilt session's subscriptions. */
    private fun changeNetwork(household: SonosHousehold) = runBlocking {
        household.connect()
        withTimeout(10_000) { household.state.first { it.connected } }
        assertEquals("the first connect is from memory", 0, sweeps.get())
        fake.clearHistory()
        household.onNetworkChanged()
        fake.awaitCommand(timeoutMillis = 10_000) {
            it.get("command")?.asString == "subscribe" && it.get("namespace")?.asString == "groups:1"
        }
        withTimeout(10_000) { household.state.first { it.connected } }
    }

    @Test fun `a memory kept per network is tried on arriving at a network`() {
        val household = household(keyed = true)
        try {
            changeNetwork(household)
            assertEquals("discovery ran for a player already known on this network", 0, sweeps.get())
        } finally {
            household.disconnect()
        }
    }

    @Test fun `a memory kept for any network is skipped on a network change`() {
        val household = household(keyed = false)
        try {
            changeNetwork(household)
            assertTrue("an unkeyed memory was trusted after a network change", sweeps.get() >= 1)
        } finally {
            household.disconnect()
        }
    }
}
