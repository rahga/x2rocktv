package com.rahga.x2rock.ui

/**
 * The logo for the service a player calls [name], sized for the 56dp art slot — or `null`, which
 * leaves the slot empty as for any row without art.
 *
 * Sonos's list and its players name services differently: "Tidal" against "TIDAL", "TuneIn"
 * against "TuneIn (New)", "iChill Music" against "iChill Music Service". So names are compared as
 * letters and digits only, and failing an exact match, the longest listed name the player's begins
 * with wins. That covered 99 of the office household's 108 (2026-10-08); the rest — "80s80s -
 * REAL 80s Radio" against "80s80s Radio", say — go without rather than guess.
 */
fun serviceLogo(name: String): String? {
    val wanted = letters(name)
    val url = BY_LETTERS[wanted]
        ?: BY_LETTERS.entries.filter { (listed, _) -> listed.length >= MIN_PREFIX && wanted.startsWith(listed) }
            .maxByOrNull { (listed, _) -> listed.length }?.value
        ?: return null
    // 112px is the slot at 2x. `fm=png` because a few are SVG, which nothing here decodes.
    return "$url?w=112&fm=png"
}

/** [SERVICE_LOGOS] by the letters of each name, worked out once rather than on every row drawn. */
private val BY_LETTERS: Map<String, String> = SERVICE_LOGOS.mapKeys { (name, _) -> letters(name) }

private fun letters(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

/** Shorter listed names than this are too likely to begin someone else's. */
private const val MIN_PREFIX = 5
