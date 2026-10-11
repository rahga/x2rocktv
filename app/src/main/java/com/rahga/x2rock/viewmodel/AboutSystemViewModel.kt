package com.rahga.x2rock.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rahga.x2rock.lan.SonosHousehold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * About My System, from Settings: every speaker — its room, what it is, its software — and whether
 * an update is waiting. Read once on opening. **Nothing here applies an update**: the CLI's rule,
 * and the Sonos app's job.
 */
@HiltViewModel
class AboutSystemViewModel @Inject constructor(private val household: SonosHousehold) : ViewModel() {

    sealed interface UiState {
        data object Loading : UiState
        data class Ready(val speakers: List<SonosHousehold.SpeakerAbout>) : UiState {
            val waiting: Int get() = speakers.count { it.updateWaiting }
        }
        data class Error(val message: String) : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.value = runCatching { household.aboutSystem() }.fold(
                onSuccess = { UiState.Ready(it) },
                onFailure = { UiState.Error(failureNotice("read this system", it) ?: "Couldn't read this system") },
            )
        }
    }
}

/** "One SL · 18.8 · serial 00-00-00-00-00-01:E", or what there is of it. */
fun SonosHousehold.SpeakerAbout.detail(): String =
    info?.let { listOfNotNull(it.modelName.removePrefix("Sonos ").ifBlank { null }, it.displayVersion.ifBlank { null }?.let { v -> "version $v" }, it.serial.ifBlank { null }?.let { s -> "serial $s" }).joinToString(" · ") }
        ?: "Didn't answer"
