package com.rahga.x2rock.auth

import com.rahga.x2rock.store.Preferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomPreferencesStore @Inject constructor(private val prefs: Preferences) {

    private val _primaryRoomId = MutableStateFlow(prefs.getString(KEY_PRIMARY_ROOM))
    val primaryRoomId: StateFlow<String?> = _primaryRoomId.asStateFlow()

    private val _favoriteRoomIds = MutableStateFlow(prefs.getStringSet(KEY_FAVORITE_ROOMS))
    val favoriteRoomIds: StateFlow<Set<String>> = _favoriteRoomIds.asStateFlow()

    fun setPrimaryRoom(id: String?) {
        prefs.putString(KEY_PRIMARY_ROOM, id)
        _primaryRoomId.value = id
    }

    fun toggleFavorite(id: String) {
        val updated = if (id in _favoriteRoomIds.value) {
            _favoriteRoomIds.value - id
        } else {
            _favoriteRoomIds.value + id
        }
        prefs.putStringSet(KEY_FAVORITE_ROOMS, updated)
        _favoriteRoomIds.value = updated
    }

    private companion object {
        const val KEY_PRIMARY_ROOM = "primary_room_id"
        const val KEY_FAVORITE_ROOMS = "favorite_room_ids"
    }
}
