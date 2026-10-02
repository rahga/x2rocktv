package com.rahga.x2rock.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A room asked for by a channel tile, held until the household can say which group it is.
 *
 * The id is a **player** — the room's coordinator when the tile was published — and is
 * resolved to whichever group holds that player now. A group id, from a tile published
 * before tiles were keyed by player, is accepted too while it still exists.
 */
@Singleton
class PendingRoomDeepLink @Inject constructor() {
    private val _roomId = MutableStateFlow<String?>(null)
    val roomId: StateFlow<String?> = _roomId.asStateFlow()

    fun set(roomId: String) { _roomId.value = roomId }
    fun clear() { _roomId.value = null }
}
