package com.rahga.x2rock.auth

import android.content.Context
import com.rahga.x2rock.model.AppColorTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThemeStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("x2rock_prefs", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(loadTheme())
    val theme: StateFlow<AppColorTheme> = _theme.asStateFlow()

    fun setTheme(theme: AppColorTheme) {
        prefs.edit().putString(KEY_THEME, theme.name).apply()
        _theme.value = theme
    }

    private fun loadTheme(): AppColorTheme = try {
        AppColorTheme.valueOf(prefs.getString(KEY_THEME, null) ?: AppColorTheme.DEFAULT.name)
    } catch (_: Exception) {
        AppColorTheme.DEFAULT
    }

    companion object {
        private const val KEY_THEME = "color_theme"
    }
}
