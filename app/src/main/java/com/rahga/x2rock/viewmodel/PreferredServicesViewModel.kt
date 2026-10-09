package com.rahga.x2rock.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.store.PreferredServices
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Settings' preferred services: which of the household's services this device leads with, and in
 * what order — see [PreferredServices]. A press on any other service adds it to the foot of the
 * tier; on a preferred one it picks it up, Up and Down move it, and a press puts it down. Menu
 * takes one out of the tier.
 */
@HiltViewModel
class PreferredServicesViewModel @Inject constructor(
    private val household: SonosHousehold,
    private val preferred: PreferredServices,
) : ViewModel() {

    /** One service, whichever accounts the household holds for it. */
    data class Service(val id: String, val name: String, val added: Boolean)

    sealed interface Services {
        data object Loading : Services
        data class Ready(val services: List<Service>) : Services
        data class Failed(val message: String) : Services
    }

    private val _services = MutableStateFlow<Services>(Services.Loading)
    val services: StateFlow<Services> = _services.asStateFlow()

    val order: StateFlow<List<String>> = preferred.order

    /** The preferred service picked up to be moved, if one is. */
    private val _moving = MutableStateFlow<String?>(null)
    val moving: StateFlow<String?> = _moving.asStateFlow()

    init {
        viewModelScope.launch {
            val apple = async { runCatching { household.appleMusicAccount() != null }.getOrDefault(false) }
            _services.value = runCatching { household.searchableServices() }.fold(
                onSuccess = { linked ->
                    val appleMusic = if (apple.await()) listOf(Service(PreferredServices.APPLE_MUSIC, "Apple Music", added = true)) else emptyList()
                    Services.Ready(appleMusic + linked.distinctBy { it.service.id }.map { Service(it.service.id, it.service.name, it.added) })
                },
                onFailure = { Services.Failed("Couldn't read this system's services: ${it.message ?: it}") },
            )
        }
    }

    fun prefer(id: String) = preferred.setPreferred(id, true)

    fun remove(id: String) {
        if (_moving.value == id) _moving.value = null
        preferred.setPreferred(id, false)
    }

    /** Pick [id] up, or put it down if it is the one being moved. */
    fun toggleMoving(id: String) {
        _moving.value = if (_moving.value == id) null else id
    }

    fun stopMoving() {
        _moving.value = null
    }

    /** Move the service picked up [by] places; nothing if none is. */
    fun move(by: Int) {
        _moving.value?.let { preferred.move(it, by) }
    }
}
