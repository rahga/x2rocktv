package com.rahga.x2rock.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PendingAuthState @Inject constructor() {
    private val _pending = MutableStateFlow<Pair<String, String>?>(null)
    val pending: StateFlow<Pair<String, String>?> = _pending.asStateFlow()

    fun set(code: String, state: String) { _pending.value = Pair(code, state) }
    fun clear() { _pending.value = null }
}
