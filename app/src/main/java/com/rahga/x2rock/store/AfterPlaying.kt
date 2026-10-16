package com.rahga.x2rock.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Browse → Stay here: whether starting something from Browse, Radio, a service, Search or Apple Music
 * leaves the viewer where they chose it, or takes them back to the room (asked for 2026-10-15). Off by
 * default: back to the room, where what started is shown. Kept per device, like the TV room.
 */
@Singleton
class AfterPlaying @Inject constructor(private val prefs: Preferences) {

    private val _stay = MutableStateFlow(prefs.getString(KEY) == "stay")
    val stay: StateFlow<Boolean> = _stay.asStateFlow()

    fun setStay(stay: Boolean) {
        prefs.putString(KEY, if (stay) "stay" else null)
        _stay.value = stay
    }

    private companion object {
        const val KEY = "after_playing"
    }
}
