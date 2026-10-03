package com.rahga.x2rock.viewmodel

/**
 * Milliseconds that only ever go up. A TV box steps its wall clock when NTP arrives after a
 * boot, and a sleep timer's end anchored to that read as expired, or jumped. `SystemClock
 * .elapsedRealtime()` on a device; tests supply their own, since Android's is not there.
 */
fun interface MonotonicClock {
    fun now(): Long
}
