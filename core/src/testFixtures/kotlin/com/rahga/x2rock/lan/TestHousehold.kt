package com.rahga.x2rock.lan

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** Connect to [fake] as its seed and wait until the household says it is connected. */
suspend fun SonosHousehold.connectTo(fake: FakePlayer, timeoutMillis: Long = 5_000): HouseholdState {
    connect(fake.seed)
    return withTimeout(timeoutMillis) { state.first { it.connected } }
}

/** A seed store over one field, which a test reads back as [held]. */
class FakeSeedStore(
    override val keyedByNetwork: Boolean = false,
    var held: Discovery.DiscoveredPlayer? = null,
) : SeedStore {
    override fun load() = held
    override fun save(player: Discovery.DiscoveredPlayer) { held = player }
    override fun clear() { held = null }
}
