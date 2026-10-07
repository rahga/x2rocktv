package com.rahga.x2rock.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rahga.x2rock.apple.AppleMusicItem
import com.rahga.x2rock.apple.ITunesSearch
import com.rahga.x2rock.lan.SonosHousehold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * Apple Music, searched by typing or dictating, and played in the room through the household's
 * own Apple Music account. See [ITunesSearch] for why the search is Apple's public one.
 */
@HiltViewModel
class AppleMusicViewModel @Inject constructor(
    private val household: SonosHousehold,
    private val itunes: ITunesSearch,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val groupId: String = checkNotNull(savedStateHandle["groupId"])

    sealed interface Results {
        data object Idle : Results
        data object Searching : Results
        data class Found(val items: List<AppleMusicItem>) : Results
        data class Failed(val message: String) : Results
    }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _kind = MutableStateFlow(AppleMusicItem.Kind.SONG)
    val kind: StateFlow<AppleMusicItem.Kind> = _kind.asStateFlow()

    private val _results = MutableStateFlow<Results>(Results.Idle)
    val results: StateFlow<Results> = _results.asStateFlow()

    /** The item being started, so its row can say so and a second press is turned away. */
    private val _starting = MutableStateFlow<String?>(null)
    val starting: StateFlow<String?> = _starting.asStateFlow()

    private val _notice = TransientNotice(viewModelScope)
    val notice: StateFlow<String?> = _notice.text

    /** The storefront: the TV's own country, which is the listener's far more often than not. */
    private val country = Locale.getDefault().country.ifEmpty { "US" }

    private var searchJob: Job? = null
    private var account: String? = null

    fun setQuery(text: String) { _query.value = text }

    /** Songs or albums; the same words searched again, if there are any. */
    fun setKind(kind: AppleMusicItem.Kind) {
        if (_kind.value == kind) return
        _kind.value = kind
        if (_query.value.isNotBlank()) search()
    }

    fun search() {
        val term = _query.value.trim()
        if (term.isEmpty()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _results.value = Results.Searching
            _results.value = runCatching { itunes.search(term, _kind.value, country) }.fold(
                // Each item once: the list is keyed by it, and a repeat would crash it.
                onSuccess = { Results.Found(it.distinctBy { item -> item.objectId }) },
                onFailure = { Results.Failed("Couldn't reach Apple's search: ${it.message ?: it}") },
            )
        }
    }

    /** Play [item] in place of the queue. Back to the room once it plays; said here if not. */
    fun play(item: AppleMusicItem, onDone: () -> Unit) {
        if (_starting.value != null) return
        viewModelScope.launch {
            val account = account() ?: return@launch _notice.post(NO_ACCOUNT)
            _starting.value = item.objectId
            val started = runCatching { household.playAppleMusic(groupId, item, account) }
            _starting.value = null
            started.onSuccess { onDone() }.onFailure { _notice.failure("play ${item.title}", it) }
        }
    }

    /** Add [item] to the end of the queue, leaving what plays alone. */
    fun queue(item: AppleMusicItem) {
        viewModelScope.launch {
            val account = account() ?: return@launch _notice.post(NO_ACCOUNT)
            runCatching { household.queueAppleMusic(groupId, item, account) }
                .onSuccess { _notice.post("Added \"${item.title}\" to the queue") }
                .onFailure { _notice.failure("add ${item.title}", it) }
        }
    }

    /** Asked once, when first needed: what the household has played names it. */
    private suspend fun account(): String? = account ?: household.appleMusicAccount().also { account = it }
}

internal const val NO_ACCOUNT =
    "This system's Apple Music account isn't known yet: play anything from Apple Music in the Sonos app once, then try again."
