package com.rahga.x2rock.store

import com.rahga.x2rock.lan.SonosHousehold
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether Apple Music is offered, in Music Services, Search and Preferred services (owner's rule,
 * 2026-10-18). It is listed when the household's speakers store an Apple Music sign-in, or an app-link
 * key exists (not built yet). Otherwise only when Settings → Advanced → Apple Music is on: a switch
 * shown only in that case, for a household that plays Apple Music without a stored sign-in being
 * readable. Off by default; kept per device.
 */
@Singleton
class AppleMusicSetting @Inject constructor(private val prefs: Preferences, private val household: SonosHousehold) {

    private val _forced = MutableStateFlow(prefs.getString(KEY) == "on")
    /** The Advanced switch, for a household with no stored Apple Music sign-in. */
    val forced: StateFlow<Boolean> = _forced.asStateFlow()

    fun setForced(on: Boolean) {
        prefs.putString(KEY, if (on) "on" else null)
        _forced.value = on
    }

    /** Whether the speakers store an Apple Music sign-in, which lists it by itself. */
    suspend fun stored(): Boolean = household.storedAppleMusicAccount() != null

    /** Whether to list Apple Music now. An app-link key, when there is one, joins [stored] here. */
    suspend fun shown(): Boolean = stored() || _forced.value

    private companion object {
        const val KEY = "apple_music_forced"
    }
}
