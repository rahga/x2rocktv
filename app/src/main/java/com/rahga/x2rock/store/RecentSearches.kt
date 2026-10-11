package com.rahga.x2rock.store

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What this device searched for lately, newest first, so a term typed once on a remote is one
 * press the next time. The Sonos app keeps a search history on its Search tab; this is the same,
 * kept on the device like presets and never sent anywhere.
 */
@Singleton
class RecentSearches @Inject constructor(private val prefs: Preferences) {

    private val gson = Gson()
    private val _terms = MutableStateFlow(load())
    /** Newest first, at most [LIMIT]. */
    val terms: StateFlow<List<String>> = _terms.asStateFlow()

    /** [term] to the top; an earlier copy of it, in any case, gives way. */
    fun add(term: String) {
        val t = term.trim().ifEmpty { return }
        save((listOf(t) + _terms.value.filterNot { it.equals(t, ignoreCase = true) }).take(LIMIT))
    }

    fun remove(term: String) = save(_terms.value - term)

    private fun save(next: List<String>) {
        prefs.putString(KEY, gson.toJson(next))
        _terms.value = next
    }

    /** Anything unreadable is dropped rather than crashing the launch: this is a convenience. */
    private fun load(): List<String> = runCatching {
        val json = prefs.getString(KEY) ?: return emptyList()
        gson.fromJson<List<String>>(json, object : TypeToken<List<String>>() {}.type).orEmpty()
    }.getOrDefault(emptyList())

    companion object {
        const val LIMIT = 8
        private const val KEY = "recent_searches"
    }
}
