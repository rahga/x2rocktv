package com.rahga.x2rock.auth

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomPreferencesStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("x2rock_prefs", Context.MODE_PRIVATE)

    private val _primaryRoomId = MutableStateFlow(loadPrimaryRoomId())
    val primaryRoomId: StateFlow<String?> = _primaryRoomId.asStateFlow()

    private val _favoriteRoomIds = MutableStateFlow(loadFavoriteRoomIds())
    val favoriteRoomIds: StateFlow<Set<String>> = _favoriteRoomIds.asStateFlow()

    fun setPrimaryRoom(id: String?) {
        if (id == null) prefs.edit().remove(KEY_PRIMARY_ROOM).apply()
        else prefs.edit().putString(KEY_PRIMARY_ROOM, id).apply()
        _primaryRoomId.value = id
    }

    fun toggleFavorite(id: String) {
        val updated = if (id in _favoriteRoomIds.value)
            _favoriteRoomIds.value - id
        else
            _favoriteRoomIds.value + id
        prefs.edit().putStringSet(KEY_FAVORITE_ROOMS, updated).apply()
        _favoriteRoomIds.value = updated
    }

    private fun loadPrimaryRoomId(): String? = prefs.getString(KEY_PRIMARY_ROOM, null)

    private fun loadFavoriteRoomIds(): Set<String> =
        prefs.getStringSet(KEY_FAVORITE_ROOMS, emptySet())?.toSet() ?: emptySet()

    companion object {
        private const val KEY_PRIMARY_ROOM = "primary_room_id"
        private const val KEY_FAVORITE_ROOMS = "favorite_room_ids"
    }
}
