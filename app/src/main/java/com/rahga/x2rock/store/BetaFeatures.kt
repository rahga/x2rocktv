package com.rahga.x2rock.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings → Beta features: whether this device shows what has never run against a real speaker
 * (T-21). Off by default, so nobody meets an unverified feature without asking to; a tester on
 * hardware neither household has — a Port, a portable — turns it on, and reports what happened.
 *
 * Kept per device, like the TV room. Also holds the last refusal a beta feature met, in memory
 * only, for the report: it is what a tester would otherwise have to copy off the screen.
 */
@Singleton
class BetaFeatures @Inject constructor(private val prefs: Preferences) {

    private val _enabled = MutableStateFlow(prefs.getString(KEY) == "on")
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(on: Boolean) {
        prefs.putString(KEY, if (on) "on" else null)
        _enabled.value = on
    }

    private val _lastRefusal = MutableStateFlow<String?>(null)
    val lastRefusal: StateFlow<String?> = _lastRefusal.asStateFlow()

    fun noteRefusal(what: String, error: Throwable) {
        _lastRefusal.value = "$what: ${error.message ?: error::class.simpleName}"
    }

    private companion object {
        const val KEY = "beta_features"
    }
}
