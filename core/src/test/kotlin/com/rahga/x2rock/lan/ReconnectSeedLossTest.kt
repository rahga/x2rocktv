package com.rahga.x2rock.lan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seed dying while a reconnect is still setting up. Its loss is not acted on while that
 * reconnect runs, and the setup's later steps are best-effort, so the reconnect used to finish
 * "connected" with no `groups:1` subscription at all (outside review, 2026-10-08). The seed is
 * closed during the setup's tail, after the topology subscription and before the settings read.
 */
class ReconnectSeedLossTest {
    @Test(timeout = 20_000) fun `a seed lost while a reconnect sets up is reconnected again, not left deaf`() = runBlocking {
        val fake = FakePlayer().also { it.start() }
        val scope = CoroutineScope(SupervisorJob())
        val household = SonosHousehold(
            scope = scope, addressBook = PlayerAddressBook(), multicast = MulticastGate.None,
            seeds = FakeSeedStore(keyedByNetwork = true, held = fake.seed), port = fake.port,
            ssdp = { listOf(fake.seed) },
        )
        try {
            household.connectTo(fake)
            fake.clearHistory()
            fake.holdRepliesTo("getSettingsGroup")
            household.onNetworkChanged()
            fake.awaitCommand("getSettingsGroup", 5_000)
            // A real close during the reconnect's best-effort tail, after getGroups and subscribe.
            fake.dropConnection(fake.id)
            fake.releaseReplies()
            val field = SonosHousehold::class.java.getDeclaredField("reconnectJob").apply { isAccessible = true }
            withTimeout(8_000) { (field.get(household) as Job).join() }
            delay(300)
            assertTrue("reconnect completed with a lost seed and no replacement groups subscription; " +
                "connected=${household.state.value.connected}, open=${household.openSockets()}, " +
                "groups subscriptions=${fake.commandsNamed("subscribe", "groups:1")}",
                fake.commandsNamed("subscribe", "groups:1") >= 2)
        } finally {
            fake.releaseReplies()
            household.disconnect()
            scope.cancel()
            fake.shutdown()
        }
    }
}
