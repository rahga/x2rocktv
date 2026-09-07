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
 * rare case.
 *
 * Two things bound the cost of a wrong memory, because "it self-corrects" is only true if
 * it corrects *promptly*:
 *
 * - A **network change** skips the memory entirely and goes straight to discovery. The
 *   address is not merely unverified at that point, it is probably wrong, and trying it
 *   first would spend the connect timeout before discovery ever ran.
 * - Otherwise the memory is probed under a timeout of its own, so a host that accepts a
 *   TCP connect and then says nothing cannot stall startup indefinitely — the client has
 *   no call timeout and a zero read timeout by design, because a subscription is meant to
 *   sit idle for hours.
 *
 * A memory is dropped when the *session* fails, not merely when the socket does. A player
 * that answers on the remembered address but no longer belongs to the remembered household
 * — after a factory reset and re-setup — would otherwise be retried on every launch forever.
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
