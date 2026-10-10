package com.rahga.x2rock.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One thing starting at a time, marked by its key while it does.
 *
 * The rows are never disabled — a disabled tv-material3 row keeps focus and loses its highlight —
 * so this is where a second press is turned away, and [starting] is what the pressed row says
 * "Starting…" by. Back to the room only once it worked; on failure the list stays, so the reason
 * is said where the viewer is and another choice is one press away. Every screen that starts
 * music presses through here, so the rule lives once.
 */
class StartGuard(private val scope: CoroutineScope, private val notice: TransientNotice) {
    private val _starting = MutableStateFlow<String?>(null)
    val starting: StateFlow<String?> = _starting.asStateFlow()

    /** Run [block] under [key], then [onDone] with what it answered, or say why [what] failed. */
    fun <T> start(key: String, what: String, onDone: (T) -> Unit, block: suspend () -> T) {
        if (_starting.value != null) return
        scope.launch {
            _starting.value = key
            val outcome = runCatching { block() }
            _starting.value = null
            outcome.onSuccess(onDone).onFailure { notice.failure(what, it) }
        }
    }
}
