package com.rahga.x2rock.store

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The services this device leads with, in order: Search lists their sections first and starts on
 * the top one, and Music Services lists them first. The Sonos app has one "Preferred Service"; this
 * keeps a tier of them, ordered, since there is no reason a household that uses two should rank
 * them by accident. Every other service follows in the order it always had — nothing is hidden.
 *
 * Kept by service id (`"2"`, `"204"`), so every account of a service shares its place; which
 * account leads within it is [PrimaryAccounts]'s. Apple Music, searched through Apple rather than
 * over SMAPI, is [APPLE_MUSIC].
 */
@Singleton
class PreferredServices @Inject constructor(private val prefs: Preferences) {

    private val gson = Gson()
    private val _order = MutableStateFlow(load())
    /** Preferred service ids, first first. */
    val order: StateFlow<List<String>> = _order.asStateFlow()

    /** Add [serviceId] at the foot of the tier, or take it out. */
    fun setPreferred(serviceId: String, preferred: Boolean) {
        val now = _order.value
        save(if (preferred) (now - serviceId) + serviceId else now - serviceId)
    }

    /** Move [serviceId] [by] places within the tier, stopping at either end. */
    fun move(serviceId: String, by: Int) {
        val now = _order.value.toMutableList()
        val from = now.indexOf(serviceId).takeIf { it >= 0 } ?: return
        val to = (from + by).coerceIn(0, now.lastIndex)
        if (to == from) return
        now.add(to, now.removeAt(from))
        save(now)
    }

    private fun save(next: List<String>) {
        prefs.putString(KEY, gson.toJson(next))
        _order.value = next
    }

    /** Anything unreadable is dropped rather than crashing the launch: this is an ordering. */
    private fun load(): List<String> = runCatching {
        val json = prefs.getString(KEY) ?: return emptyList()
        gson.fromJson<List<String>>(json, object : TypeToken<List<String>>() {}.type).orEmpty()
    }.getOrDefault(emptyList())

    companion object {
        /** Apple Music's place in the order: it has no SMAPI service id of its own here. */
        const val APPLE_MUSIC = "apple"
        private const val KEY = "preferred_services"
    }
}

/** Where [serviceId] stands in [order]: its place in the tier, or after every preferred one. */
fun preferredRank(serviceId: String, order: List<String>): Int =
    order.indexOf(serviceId).takeIf { it >= 0 } ?: Int.MAX_VALUE
