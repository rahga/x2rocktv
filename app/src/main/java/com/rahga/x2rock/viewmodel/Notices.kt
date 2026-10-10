package com.rahga.x2rock.viewmodel

import com.rahga.x2rock.lan.ReplyTimeoutException
import com.rahga.x2rock.lan.SonosCommandException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One line of "that didn't work", shown for a few seconds and replaced by the next.
 *
 * Commands here are fire-and-forget because their effect comes back as an event; the cost
 * was that a failure came back as nothing at all — a press that did not happen, with no word
 * why. On a remote, at three metres, that reads as the app ignoring you.
 */
class TransientNotice(private val scope: CoroutineScope) {
    private val _text = MutableStateFlow<String?>(null)
    val text: StateFlow<String?> = _text.asStateFlow()
    private var clearing: Job? = null

    /** Says why [what] failed, unless it was only cancelled by a newer press: see [failureNotice]. */
    fun failure(what: String, e: Throwable) {
        failureNotice(what, e)?.let(::post)
    }

    fun post(message: String) {
        _text.value = message
        // A newer message restarts the clock, so it is never cut short by an older one's.
        clearing?.cancel()
        clearing = scope.launch {
            delay(NOTICE_MILLIS)
            _text.value = null
        }
    }

    private companion object {
        const val NOTICE_MILLIS = 4_000L
    }
}

/**
 * What to say when [what] failed with [e] — "Couldn't group the rooms: Kitchen did not answer,
 * and the change has not appeared" — or `null` when there is nothing to say.
 *
 * Cancellation is nothing to say: a debounced volume send is cancelled by every newer press,
 * and `runCatching` catches that too. Reported, holding a volume key would flash an error on
 * every repeat.
 */
internal fun failureNotice(what: String, e: Throwable): String? {
    if (e is CancellationException) return null
    val why = when (e) {
        // Every Control API command is refused this way with Authentication switched on in the
        // Sonos app, and this app cannot sign in on a TV. See `isPermissionRefusal` for the one
        // command that says it otherwise, which this app never sends.
        is SonosCommandException -> if (e.isPermissionRefusal) AUTHENTICATION_ON else plainRefusal(e.detail)
        is ReplyTimeoutException -> "the speaker did not answer"
        else -> e.message ?: e.toString()
    }
    return "Couldn't $what: $why"
}

/**
 * A refusal in words, for the codes seen on hardware; any other stays as the player sent it, code
 * and reason, since a code nobody has met cannot be worded truthfully. [detail] is `CODE` or
 * `CODE: reason`.
 */
internal fun plainRefusal(detail: String): String = when (detail.substringBefore(':').trim()) {
    // A station refuses skip and seek, and an iHeartRadio artist station's track refuses a seek
    // though it carries a duration (CLAUDE.md, the seek bar).
    "ERROR_DISALLOWED_BY_POLICY" -> "this source doesn't allow it"
    // Play with nothing loaded, seen on the Streamer from the remote's play key.
    "ERROR_PLAYBACK_NO_CONTENT" -> "nothing is loaded"
    // An anonymous service's item, or a deleted playlist, replayed (x2rock, 2026-09-26).
    "ERROR_ACCOUNT_INVALID_ID" -> "it isn't available from this system's accounts"
    else -> detail
}

/** A station or program the player accepted and then sat idle on: see `StreamStart.SILENT`. */
internal fun didNotPlay(title: String) = "\"$title\" didn't play. Try another."

/** A level the speaker does not control: a Port or an Amp with a fixed line-out. */
internal const val FIXED_VOLUME = "This speaker's volume is fixed. Change it on the amplifier it feeds."

internal const val AUTHENTICATION_ON =
    "this system requires authentication. Turn it off in the Sonos app: " +
        "Account > Privacy and Security > Connection Security"
