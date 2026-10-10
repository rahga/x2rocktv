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

    private val room = RoomTarget(household, checkNotNull(savedStateHandle["groupId"]))

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

    private val _notice = TransientNotice(viewModelScope)
    private val guard = StartGuard(viewModelScope, _notice)
    /** The station being started, so its row can say so. */
    val starting: StateFlow<String?> = guard.starting
    val notice: StateFlow<String?> = _notice.text

    private var loadJob: Job? = null

    /** Whether the right side is the address field rather than a category's stations. */
    private val _byUrl = MutableStateFlow(false)
    val byUrl: StateFlow<Boolean> = _byUrl.asStateFlow()

    fun openUrl() { _byUrl.value = true }

    init {
        select(categories.first())
    }

    fun select(category: Category) {
        _byUrl.value = false
        _selected.value = category
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _stations.value = Stations.Loading
            _stations.value = runCatching { directory.stations(category.tag, category.countryCode) }
                .fold(
                    // Each stream once: directories list one stream under several entries, and the list is
                    // keyed by its URL, so a repeat would crash it. The first entry is kept.
                    onSuccess = { Stations.Loaded(it.distinctBy { station -> station.url }) },
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
    fun play(station: Station, onDone: () -> Unit) =
        guard.start(station.url, "play ${station.name}", onDone = { started ->
            if (started == StreamStart.SILENT) _notice.post(silentNotice(station)) else onDone()
        }) { household.playStream(room.current(), station.url, station.name) }

    /**
     * Play a stream by its address, named by its host as x2rock names one: a stream's path is
     * usually a bitrate slug, and the host names the station. The same start and the same three
     * outcomes as a directory station; a silent one stays here, to be corrected.
     */
    fun playUrl(input: String, onDone: () -> Unit) {
        val url = streamUrl(input) ?: return _notice.post(NOT_A_STREAM_URL)
        val name = streamName(url)
        guard.start(url, "play $name", onDone = { started ->
            if (started == StreamStart.SILENT) _notice.post("$name didn't play. Check the address.") else onDone()
        }) { household.playStream(room.current(), url, name) }
    }
}

internal const val NOT_A_STREAM_URL = "That isn't an address a speaker can play: it starts http:// or https://."

/**
 * What was typed, as a URL a speaker can fetch, or null. Only http and https, as x2rock's
 * `play-url`: the speaker fetches the stream itself. Typed without a scheme it is taken as http —
 * typing "https://" on a television keyboard is a chore, and most stream servers answer http.
 */
internal fun streamUrl(input: String): String? {
    val typed = input.trim().takeIf { it.isNotEmpty() } ?: return null
    val url = if ("://" in typed) typed else "http://$typed"
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
    return url
}

/** The host, port and all, as x2rock's `stream_display_name` takes it. */
internal fun streamName(url: String): String = url.substringAfter("://").substringBefore('/').ifEmpty { url }

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
