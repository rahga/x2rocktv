package com.rahga.x2rock.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PendingRoomDeepLink @Inject constructor() {
    private val _groupId = MutableStateFlow<String?>(null)
    val groupId: StateFlow<String?> = _groupId.asStateFlow()

    fun set(groupId: String) { _groupId.value = groupId }
    fun clear() { _groupId.value = null }
}
