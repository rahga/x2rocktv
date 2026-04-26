package com.rahga.x2rock.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.rahga.x2rock.model.AppColorTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun X2RockTheme(colorTheme: AppColorTheme = AppColorTheme.DEFAULT, content: @Composable () -> Unit) {
    val colorScheme = when (colorTheme) {
        AppColorTheme.DEFAULT -> darkColorScheme()
        AppColorTheme.OCEAN -> darkColorScheme(
            primary = Color(0xFF4FC3F7),
            onPrimary = Color(0xFF003048),
            primaryContainer = Color(0xFF00607A),
            onPrimaryContainer = Color(0xFFB3EBFF)
        )
        AppColorTheme.EMBER -> darkColorScheme(
            primary = Color(0xFFFF7043),
            onPrimary = Color(0xFF3B1100),
            primaryContainer = Color(0xFF712600),
            onPrimaryContainer = Color(0xFFFFDBCF)
        )
        AppColorTheme.FOREST -> darkColorScheme(
            primary = Color(0xFF66BB6A),
            onPrimary = Color(0xFF003910),
            primaryContainer = Color(0xFF005320),
            onPrimaryContainer = Color(0xFFA9F4B5)
        )
        AppColorTheme.ORCHID -> darkColorScheme(
            primary = Color(0xFFCE93D8),
            onPrimary = Color(0xFF3E0056),
            primaryContainer = Color(0xFF5B0080),
            onPrimaryContainer = Color(0xFFF2DAFF)
        )
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

fun AppColorTheme.swatchColor(): Color = when (this) {
    AppColorTheme.DEFAULT -> Color(0xFF6650A4)
    AppColorTheme.OCEAN -> Color(0xFF4FC3F7)
    AppColorTheme.EMBER -> Color(0xFFFF7043)
    AppColorTheme.FOREST -> Color(0xFF66BB6A)
    AppColorTheme.ORCHID -> Color(0xFFCE93D8)
}
