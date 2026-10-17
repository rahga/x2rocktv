package com.rahga.x2rock.store

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

    /** How this device's room list is ordered; Sonos's own alphabetical unless chosen otherwise. */
    private val _roomOrder = MutableStateFlow(
        prefs.getString(KEY_ROOM_ORDER)?.let { saved -> RoomOrder.entries.firstOrNull { it.name == saved } } ?: RoomOrder.ALPHABETICAL
    )
    val roomOrder: StateFlow<RoomOrder> = _roomOrder.asStateFlow()

    fun setRoomOrder(order: RoomOrder) {
        prefs.putString(KEY_ROOM_ORDER, order.name)
        _roomOrder.value = order
    }

    private companion object {
        const val KEY_TV_PLAYER = "tv_player_id"
        const val KEY_ROOM_ORDER = "room_order"
    }
}

/**
 * The room list's order, chosen per device from a hold on a room (P-20, 2026-10-17). The Sonos app
 * offers these two among its sorts; Frequently used and Pin were left out by the owner's choice.
 */
enum class RoomOrder(val label: String, val detail: String) {
    ALPHABETICAL("Alphabetical", "As Sonos names them"),
    PLAYING_FIRST("Playing first", "Rooms playing now, then the rest"),
}
