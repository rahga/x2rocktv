package com.rahga.x2rock

/**
 * Switches for trying paths a test network will not take on its own. Debug builds only: set from
 * the launch intent in [MainActivity], and never set in a release build.
 */
object DebugSwitches {
    /**
     * Skip SSDP, so discovery falls back to mDNS. For a network that answers SSDP — the office
     * one did by 2026-10-06, though it had dropped it — the fallback is otherwise never run:
     *
     *     adb shell am start -S -n com.rahga.x2rock/.MainActivity --ez debugMdnsOnly true
     *
     * The remembered speaker is forgotten too, or there is no discovery at all.
     */
    @Volatile var mdnsOnly = false

    /**
     * A service-token envelope to use instead of capturing one off a player. The GENA capture
     * needs the speaker to open a connection back to the app, which the emulator's NAT drops (as
     * it drops discovery); seeding the envelope is the parallel of seeding the speaker. Capture it
     * on the host and hand it over on the launch intent:
     *
     *     adb shell am start -S -n com.rahga.x2rock/.MainActivity --es debugServiceEnvelope "2:…"
     *
     * Decrypting it still needs the connected household's own short id, so this only works against
     * the household it was read from. Debug builds only; never set in release.
     */
    @Volatile var serviceEnvelope: String? = null
}
