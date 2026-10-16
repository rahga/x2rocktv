package com.rahga.x2rock.viewmodel

import androidx.lifecycle.ViewModel
import com.rahga.x2rock.store.AfterPlaying
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** What starting something does next: see [AfterPlaying]. Held by the nav graph and Browse's header. */
@HiltViewModel
class AfterPlayingViewModel @Inject constructor(private val afterPlaying: AfterPlaying) : ViewModel() {
    val stay: StateFlow<Boolean> = afterPlaying.stay
    fun toggle() = afterPlaying.setStay(!afterPlaying.stay.value)
}
