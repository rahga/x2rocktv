package com.rahga.x2rock.store

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The small slice of key-value storage this app actually uses.
 *
 * Extracted so the stores above it — theme, room preferences — are plain Kotlin holding a
 * `StateFlow`, rather than classes that cannot be constructed without an Android `Context`.
 * That is what makes the view models testable at all: they take those stores, and the
 * stores previously dragged the whole framework in behind them.
 */
interface Preferences {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getStringSet(key: String): Set<String>
    fun putStringSet(key: String, value: Set<String>)
}

@Singleton
class SharedPreferencesStore @Inject constructor(
    @ApplicationContext context: Context,
) : Preferences {

    private val prefs = context.getSharedPreferences("x2rock_prefs", Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String?) {
        prefs.edit().putString(key, value).apply()
    }

    override fun getStringSet(key: String): Set<String> =
        prefs.getStringSet(key, emptySet()) ?: emptySet()

    override fun putStringSet(key: String, value: Set<String>) {
        prefs.edit().putStringSet(key, value).apply()
    }
}
