package com.rahga.x2rock.lan

/**
 * Remembers one player, so the next start does not have to go looking.
 *
 * Discovery costs a multicast sweep and the wait for replies; reaching a single remembered
 * player instead makes a warm start immediate, and `getGroups` still reports where all the
 * others are. This is the "steady state" the sibling research recommends: cached address
 * plus household id, straight to the socket, no scanning.
 *
 * **A remembered address is a hint, not a fact.** DHCP moves, and a device that changed
 * network will have remembered something irrelevant. Nothing here tries to detect that —
 * [SonosHousehold] falls back to discovery when the remembered player does not answer.
 *
 * That is deliberate rather than lazy. A TV box and the speakers around it are stationary:
 * the same soundbar, the same room, the same network, almost always. The sibling Rust
 * project fingerprints the gateway MAC before trusting a cached address because it runs on
 * a laptop that wakes somewhere new; here the same guard would be complexity spent on a
 * rare case that is already cheap. Measured: a stale seed costs about 400ms over a cold
 * start before discovery takes over, and moves are handled by that plus
 * [SonosHousehold.onNetworkChanged].
 */
interface SeedStore {

    fun load(): Discovery.DiscoveredPlayer?
    fun save(player: Discovery.DiscoveredPlayer)
    fun clear()

    /** Remembers nothing; every start discovers. */
    object None : SeedStore {
        override fun load(): Discovery.DiscoveredPlayer? = null
        override fun save(player: Discovery.DiscoveredPlayer) = Unit
        override fun clear() = Unit
    }
}
