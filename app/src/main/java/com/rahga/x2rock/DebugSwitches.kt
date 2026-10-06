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
}
