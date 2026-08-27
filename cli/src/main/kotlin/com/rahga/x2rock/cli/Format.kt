package com.rahga.x2rock.cli

import com.rahga.x2rock.model.Track

fun Long.toClock(): String {
    val total = (this / 1_000).coerceAtLeast(0)
    val h = total / 3_600
    val m = (total % 3_600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** "Title — Artist" with whichever halves exist; null when there is no track at all. */
fun Track?.oneLine(): String? {
    val title = this?.name?.takeIf { it.isNotBlank() } ?: return null
    val artist = artist?.name?.takeIf { it.isNotBlank() }
    return if (artist != null) "$title — $artist" else title
}

/** Parses "40", "+5" or "-5" against [current]; null when it is none of those. */
fun parseVolume(arg: String, current: Int): Int? {
    val trimmed = arg.trim()
    val n = trimmed.removePrefix("+").toIntOrNull() ?: return null
    val target = when {
        trimmed.startsWith("+") || trimmed.startsWith("-") -> current + n
        else -> n
    }
    return target.coerceIn(0, 100)
}
