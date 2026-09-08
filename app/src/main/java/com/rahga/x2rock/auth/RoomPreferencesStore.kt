package com.rahga.x2rock.auth

import com.rahga.x2rock.store.Preferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomPreferencesStore @Inject constructor(private val prefs: Preferences) {




    /**
     * The soundbar this television is plugged into, remembered as a *player* id.
     *
     * Not a group id: a regroup mints new ones, so a group remembered today may name
     * nothing tomorrow. The speaker itself does not move.
     */
    private val _tvPlayerId = MutableStateFlow(prefs.getString(KEY_TV_PLAYER))
    val tvPlayerId: StateFlow<String?> = _tvPlayerId.asStateFlow()

    fun setTvPlayer(playerId: String?) {
        prefs.putString(KEY_TV_PLAYER, playerId)
        _tvPlayerId.value = playerId
    }


    private companion object {
        const val KEY_TV_PLAYER = "tv_player_id"
    }
}
