package com.rahga.x2rock.store

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A scene for the remote: these rooms together, at these levels, playing this favourite — one
 * press. Sonos 27 calls the same idea a preset.
 *
 * Rooms are **players**, never a group id: a regroup mints new group ids, and a preset outlives
 * every one of them. The first player leads the group. Content is a Sonos favourite, the one kind
 * of content the household names by a stable id over the LAN; a preset saved while nothing that
 * is a favourite plays keeps the rooms and levels, and leaves the music as it is.
 */
data class Preset(
    val id: String,
    val name: String,
    val playerIds: List<String>,
    val volumes: Map<String, Int>,
    val favoriteId: String? = null,
    val favoriteName: String? = null,
)

/** The household's presets, on this device. Kept here rather than on the speakers: Sonos has no place for them. */
@Singleton
class PresetStore @Inject constructor(private val prefs: Preferences) {

    private val gson = Gson()
    private val _presets = MutableStateFlow(load())
    val presets: StateFlow<List<Preset>> = _presets.asStateFlow()

    fun add(preset: Preset) = save(_presets.value.filter { it.id != preset.id } + preset)

    fun remove(id: String) = save(_presets.value.filter { it.id != id })

    private fun save(presets: List<Preset>) {
        prefs.putString(KEY, gson.toJson(presets))
        _presets.value = presets
    }

    /** Anything unreadable is dropped rather than crashing the launch: a preset is a convenience. */
    private fun load(): List<Preset> = runCatching {
        val json = prefs.getString(KEY) ?: return emptyList()
        gson.fromJson<List<Preset>>(json, object : TypeToken<List<Preset>>() {}.type)
            .orEmpty()
            .filter { it.playerIds.isNotEmpty() }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY = "presets"
    }
}
