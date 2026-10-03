package com.rahga.x2rock.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.lan.StreamStart
import com.rahga.x2rock.radio.RadioDirectory
import com.rahga.x2rock.radio.Station
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * The radio directory, browsed with a remote: no typing, so by category — the most-voted
 * stations, this country's, then a chosen list of genres — and a station plays on a press.
 */
@HiltViewModel
class RadioViewModel @Inject constructor(
    private val household: SonosHousehold,
    private val directory: RadioDirectory,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val groupId: String = checkNotNull(savedStateHandle["groupId"])

    /** One way into the directory. At most one of [tag] and [countryCode]; neither is "Popular". */
    data class Category(val label: String, val tag: String? = null, val countryCode: String? = null)

    sealed interface Stations {
        data object Loading : Stations
        data class Loaded(val stations: List<Station>) : Stations
        data class Failed(val message: String) : Stations
    }

    val categories: List<Category> = categoriesFor(Locale.getDefault())

    private val _selected = MutableStateFlow(categories.first())
    val selected: StateFlow<Category> = _selected.asStateFlow()

    private val _stations = MutableStateFlow<Stations>(Stations.Loading)
    val stations: StateFlow<Stations> = _stations.asStateFlow()

    /** The station being started, so its row can say so and a second press is turned away. */
    private val _starting = MutableStateFlow<String?>(null)
    val starting: StateFlow<String?> = _starting.asStateFlow()

    private val _notice = TransientNotice(viewModelScope)
    val notice: StateFlow<String?> = _notice.text

    private var loadJob: Job? = null

    init {
        select(categories.first())
    }

    fun select(category: Category) {
        _selected.value = category
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _stations.value = Stations.Loading
            _stations.value = runCatching { directory.stations(category.tag, category.countryCode) }
                .fold(
                    onSuccess = { Stations.Loaded(it) },
                    onFailure = { Stations.Failed("Couldn't reach the radio directory: ${it.message ?: it}") },
                )
        }
    }

    fun retry() = select(_selected.value)

    /**
     * Play [station] in the room. Back to the room once it plays, or is still buffering — a slow
     * stream is not a broken one. A station the room would not play stays on this list, said,
     * with another one press away: the directory's liveness check is days old.
     */
    fun play(station: Station, onDone: () -> Unit) {
        if (_starting.value != null) return
        viewModelScope.launch {
            _starting.value = station.url
            val outcome = runCatching { household.playStream(groupId, station.url, station.name) }
            _starting.value = null
            outcome
                .onSuccess { started ->
                    if (started == StreamStart.SILENT) _notice.post(silentNotice(station))
                    else onDone()
                }
                .onFailure { e -> failureNotice("play ${station.name}", e)?.let(_notice::post) }
        }
    }
}

internal fun silentNotice(station: Station) = "${station.name} didn't play. Try another station."

/** Popular first, then this country's when there is one to name, then the genres. */
internal fun categoriesFor(locale: Locale): List<RadioViewModel.Category> = buildList {
    add(RadioViewModel.Category("Popular"))
    locale.country.takeIf { it.length == 2 }?.let { code ->
        add(RadioViewModel.Category(locale.displayCountry.ifBlank { code }, countryCode = code))
    }
    RadioDirectory.GENRES.forEach { tag ->
        add(RadioViewModel.Category(tag.replaceFirstChar { it.titlecase(locale) }, tag = tag))
    }
}
