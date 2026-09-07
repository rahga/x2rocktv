package com.rahga.x2rock.lan

/**
 * Whatever the platform must hold for multicast to reach the app.
 *
 * On Android with Wi-Fi that is a `WifiManager.MulticastLock`, without which SSDP replies
 * are dropped before the app sees them — and dropped *silently*, which is the failure mode
 * worth guarding against: discovery simply returns nothing and the household looks absent.
 * On Ethernet, and on a desktop JVM, there is nothing to hold.
 *
 * `:core` has no Android dependency and cannot take that lock itself, so the platform
 * supplies one of these.
 */
interface MulticastGate {

    suspend fun <T> around(block: suspend () -> T): T

    /** For Ethernet, desktops, and tests: nothing to acquire. */
    object None : MulticastGate {
        override suspend fun <T> around(block: suspend () -> T): T = block()
    }
}
