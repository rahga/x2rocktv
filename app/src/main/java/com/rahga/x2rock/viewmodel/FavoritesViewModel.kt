package com.rahga.x2rock.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rahga.x2rock.model.Favorite
import com.rahga.x2rock.model.Playlist
import com.rahga.x2rock.model.HistoryItem
import com.rahga.x2rock.lan.SonosCommandException
import com.rahga.x2rock.lan.SonosHousehold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import com.rahga.x2rock.auth.PendingRoomDeepLink
import com.rahga.x2rock.store.Preset
import com.rahga.x2rock.store.PresetStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import javax.inject.Inject

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val household: SonosHousehold,
    savedStateHandle: SavedStateHandle,
    private val presetStore: PresetStore,
    private val roomLink: PendingRoomDeepLink,
) : ViewModel() {

    /** The room this screen plays into; the Radio screen is opened on the same one. */
    val groupId: String = checkNotNull(savedStateHandle["groupId"])

    sealed interface UiState {
        data object Loading : UiState
        data class Success(
            val items: List<Favorite>,
            val activeId: String?,
            /** The household's Sonos playlists, listed after the favourites as the Sonos app does. */
            val playlists: List<Playlist> = emptyList(),
            /** What the household played lately, newest first. */
            val recent: List<HistoryItem> = emptyList(),
            /** Why there is no recently played list, when the household said: history is off. */
            val recentNote: String? = null,
        ) : UiState
        data class Error(val message: String) : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val loadingFavoriteId = MutableStateFlow<String?>(null)

    private val _notice = TransientNotice(viewModelScope)

    /** Why the last favourite did not load, for a few seconds. */
    val notice: StateFlow<String?> = _notice.text

    /** The services this household can browse here, by [com.rahga.x2rock.smapi.LinkedService.key]. */
    private val _browsable = MutableStateFlow<Set<String>>(emptySet())
    val browsable: StateFlow<Set<String>> = _browsable.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            _browsable.value = runCatching { household.searchableServices() }.getOrDefault(emptyList()).map { it.key() }.toSet()
        }
    }


    /** This device's presets, newest last: see [Preset]. */
    val presets: StateFlow<List<Preset>> = presetStore.presets

    private val _deleteArmed = MutableStateFlow<String?>(null)

    /** The preset a second Menu press would delete, if the first was just made. */
    val deleteArmed: StateFlow<String?> = _deleteArmed.asStateFlow()

    /**
     * Bring [preset] back: set each speaker's level, gather the rooms, then start its favourite.
     * Levels come **first**, before the regroup, so nothing starts loud: a speaker's level is its
     * own and survives a join, and a room joining a group plays the group's music at once — at
     * whatever level it was left on, had that not already been set. Then to the room it now forms,
     * which may not be the one Browse was opened from.
     */
    fun applyPreset(preset: Preset, onDone: () -> Unit) =
        startOne(presetKey(preset.id), "start ${preset.name}", onDone) {
            // All at once, each to its own speaker; one fixed line-out or a refused level must not
            // stop the rest of the preset.
            coroutineScope {
                preset.volumes.map { (player, level) -> async { runCatching { household.setPlayerVolume(player, level) } } }.awaitAll()
            }
            val groupId = household.gatherRooms(preset.playerIds)
            preset.favoriteId?.let { household.loadFavorite(groupId, it) }
            roomLink.set(preset.playerIds.first())
        }

    /**
     * Two presses of Menu, as the queue's Clear is two: the first arms and says so, the second
     * deletes. A preset is only this device's, so deleting one touches nothing on the speakers.
     */
    fun deletePreset(preset: Preset) {
        if (_deleteArmed.value == preset.id) {
            presetStore.remove(preset.id)
            _deleteArmed.value = null
            disarm?.cancel()
            _notice.post("Deleted \"${preset.name}\"")
            return
        }
        _deleteArmed.value = preset.id
        _notice.post("Press Menu again to delete \"${preset.name}\"")
        // One disarm at a time: an older press's timer would otherwise disarm a newer arming early.
        disarm?.cancel()
        disarm = viewModelScope.launch {
            delay(DELETE_ARM_MILLIS)
            _deleteArmed.value = null
        }
    }

    private var disarm: Job? = null

    fun reload() = load()

    fun loadFavorite(favoriteId: String, onDone: () -> Unit) =
        startOne(favoriteId, "play that favourite", onDone) { household.loadFavorite(groupId, favoriteId) }

    /** Play a Sonos playlist in place of the queue, the same way a favourite is played. */
    fun loadPlaylist(playlistId: String, onDone: () -> Unit) =
        startOne(playlistKey(playlistId), "play that playlist", onDone) { household.loadPlaylist(groupId, playlistId) }

    /** Play something from recently played again. See `SonosHousehold.replay`. */
    fun replay(item: HistoryItem, onDone: () -> Unit) =
        startOne(recentKey(item), "play ${item.name}", onDone) { household.replay(groupId, item) }

    /** Add a playlist to the end of the queue, leaving what plays alone. */
    fun appendPlaylist(playlist: Playlist) {
        viewModelScope.launch {
            runCatching { household.appendPlaylist(groupId, playlist.id) }
                .onSuccess { _notice.post("Added \"${playlist.name}\" to the queue") }
                .onFailure { _notice.failure("add that playlist", it) }
        }
    }

    /**
     * Start one thing playing, marked by [key] while it loads. One at a time: the rows are
     * never disabled — a disabled tv-material3 row keeps focus and loses its highlight — so
     * this is where a second press is turned away. Back to the room only once it worked; on
     * failure the list stays, so the reason is said where the viewer is and another choice is
     * one press away.
     */
    private fun startOne(key: String, what: String, onDone: () -> Unit, block: suspend () -> Unit) {
        if (loadingFavoriteId.value != null) return
        viewModelScope.launch {
            loadingFavoriteId.value = key
            val started = runCatching { block() }
            loadingFavoriteId.value = null
            started.onSuccess { onDone() }.onFailure { _notice.failure(what, it) }
        }
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            runCatching {
                // Hidden, not greyed: a favourite whose service was removed cannot be played,
                // and only the Sonos app can delete or re-add it, so a row for it would be one
                // with nothing behind it. Re-add the service and they return by themselves.
                // Three reads of the household, none depending on another, so they go out
                // together: the screen waits for the slowest rather than the sum.
                val (favs, playlists, history) = coroutineScope {
                    val favs = async { household.favorites().let { it.copy(items = it.items.filter { f -> f.playable }) } }
                    // Its own read, and allowed to fail on its own: no playlists must not mean
                    // no favourites.
                    val playlists = async { runCatching { household.playlists().playlists }.getOrDefault(emptyList()) }
                    // Also its own read. With the Sonos app's Personalization off, the household
                    // refuses it with ERROR_DISALLOWED_BY_POLICY — said, rather than an empty list.
                    val history = async { runCatching { household.history() } }
                    Triple(favs.await(), playlists.await(), history.await())
                }
                // What is playing comes from the subscription, so only the list is fetched.
                val containerName = household.groupState(groupId).container?.name
                val activeId = containerName?.let { playingFavorite(favs.items, it)?.id }
                // Not de-duplicated by name: two "The Main Mix" in the office history are two
                // different Radio Paradise streams, with different ids.
                val recent = history.getOrDefault(emptyList()).filter { it.playable }
                val recentNote = (history.exceptionOrNull() as? SonosCommandException)
                    ?.takeIf { it.detail.startsWith("ERROR_DISALLOWED_BY_POLICY") }
                    ?.let { HISTORY_OFF }
                UiState.Success(favs.items, activeId, playlists, recent, recentNote)
            }
                .onSuccess { _uiState.value = it }
                .onFailure { _uiState.value = UiState.Error(it.message ?: "Failed to load favorites") }
        }
    }
}

/**
 * What [FavoritesViewModel.loadingFavoriteId] holds while a playlist loads. Favourite and
 * playlist ids are separate number spaces — both can be "6" — so one is marked.
 */
fun playlistKey(playlistId: String) = "playlist:$playlistId"

/**
 * The favourite a room is playing, found by its container's name — the only link the player gives
 * from what plays back to a favourite. Browse marks it, and a preset saved from the room keeps it.
 */
fun playingFavorite(favorites: List<Favorite>, containerName: String): Favorite? =
    favorites.firstOrNull { it.playable && it.name == containerName }

/** [FavoritesViewModel.loadingFavoriteId] while a recently played item loads. */
fun recentKey(item: HistoryItem) = "recent:${item.id.serviceId}:${item.id.objectId}"

/** [FavoritesViewModel.loadingFavoriteId] while a preset is being brought back. */
fun presetKey(id: String) = "preset:$id"

private const val DELETE_ARM_MILLIS = 4_000L

internal const val HISTORY_OFF =
    "Recently played is off: Personalization is turned off for this system in the Sonos app."
