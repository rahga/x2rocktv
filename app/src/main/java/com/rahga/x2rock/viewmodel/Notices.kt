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
class TransientNotice(private val scope: CoroutineScope, private val millis: Long = NOTICE_MILLIS) {
    private val _text = MutableStateFlow<String?>(null)
    val text: StateFlow<String?> = _text.asStateFlow()
    private var clearing: Job? = null

    fun post(message: String) {
        _text.value = message
        // A newer message restarts the clock, so it is never cut short by an older one's.
        clearing?.cancel()
        clearing = scope.launch {
            delay(millis)
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
        // Sonos app — getGroups first — and this app cannot sign in on a TV (punch list 1.6).
        is SonosCommandException ->
            if ("ERROR_NO_PERMISSION" in e.detail) AUTHENTICATION_ON else e.detail
        is ReplyTimeoutException -> "the speaker did not answer"
        else -> e.message ?: e.toString()
    }
    return "Couldn't $what: $why"
}

internal const val AUTHENTICATION_ON =
    "this system requires authentication. Turn it off in the Sonos app: " +
        "Account > Privacy and Security > Connection Security"
