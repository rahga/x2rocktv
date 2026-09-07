package com.rahga.x2rock.auth

import com.rahga.x2rock.model.AppColorTheme
import com.rahga.x2rock.store.Preferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThemeStore @Inject constructor(private val prefs: Preferences) {

    private val _theme = MutableStateFlow(loadTheme())
    val theme: StateFlow<AppColorTheme> = _theme.asStateFlow()

    fun setTheme(theme: AppColorTheme) {
        prefs.putString(KEY_THEME, theme.name)
        _theme.value = theme
    }

    /** An unrecognised stored value falls back rather than crashing the app on launch. */
    private fun loadTheme(): AppColorTheme = try {
        AppColorTheme.valueOf(prefs.getString(KEY_THEME) ?: AppColorTheme.DEFAULT.name)
    } catch (_: Exception) {
        AppColorTheme.DEFAULT
    }

    private companion object {
        const val KEY_THEME = "color_theme"
    }
}
