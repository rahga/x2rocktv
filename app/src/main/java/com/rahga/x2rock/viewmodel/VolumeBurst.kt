package com.rahga.x2rock.viewmodel

/**
 * How far one press of a volume control moves the level, the way the seek bar's presses climb:
 * a burst of presses steps 2, then 3, then 5 for as long as they keep coming, so a nudge stays a
 * nudge and a long way is still quick. A pause, or a turn the other way, starts the next burst
 * at 2 again (2026-10-17, asked for by the owner).
 *
 * Quiet is where a step is heard, so the low end is finer whatever the burst: at most 2 below 10,
 * and 1 below 4. Judged by where the level is going — down from 10 is a step within the fine
 * range — and never at the top, where 98 and 100 sound the same.
 */
class VolumeBurst(private val clock: MonotonicClock) {
    private var presses = 0
    private var lastAt = Long.MIN_VALUE
    private var lastDirection = 0

    /** The signed step for one press in [direction] (+1 or -1) from [level]. */
    fun step(level: Int, direction: Int): Int {
        val now = clock.now()
        if (direction != lastDirection || lastAt == Long.MIN_VALUE || now - lastAt > BURST_GAP_MILLIS) presses = 0
        lastAt = now
        lastDirection = direction
        val climb = STEPS[presses.coerceAtMost(STEPS.lastIndex)]
        presses++
        val toward = if (direction < 0) level - 1 else level
        val cap = when {
            toward < 4 -> 1
            toward < 10 -> 2
            else -> Int.MAX_VALUE
        }
        return direction * minOf(climb, cap)
    }

    private companion object {
        val STEPS = intArrayOf(2, 3, 5)

        /** As long as the seek bar's running total stays up: past it, the next press is a new burst. */
        const val BURST_GAP_MILLIS = 1_500L
    }
}
