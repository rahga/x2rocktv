package com.rahga.x2rock.smapi

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rahga.x2rock.store.Preferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [RatingsStore] over the same key-value [Preferences] seam `RoomPreferencesStore` and
 * `ThemeStore` use — one small JSON blob per service, not a file of its own. This is a
 * capability cache, not a secret: unlike a linked account's token, there's nothing here that
 * needs a stronger store than that.
 */
@Singleton
class PrefsRatingsStore @Inject constructor(private val prefs: Preferences) : RatingsStore {

    private val gson = Gson()
    private val ratingsType = object : TypeToken<List<RatingsMatch>>() {}.type

    override fun loadRatings(serviceId: String): List<RatingsMatch>? =
        prefs.getString(key(serviceId))?.let { json ->
            runCatching { gson.fromJson<List<RatingsMatch>>(json, ratingsType) }.getOrNull()
        }

    override fun saveRatings(serviceId: String, ratings: List<RatingsMatch>) {
        prefs.putString(key(serviceId), gson.toJson(ratings))
    }

    private fun key(serviceId: String) = "$KEY_PREFIX$serviceId"

    private companion object {
        const val KEY_PREFIX = "ratings_"
    }
}
