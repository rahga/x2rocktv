package com.rahga.x2rock.ui.theme

import android.os.Build
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import androidx.tv.material3.Button
import androidx.tv.material3.CardBorder
import androidx.tv.material3.CardColors
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Typography
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.rahga.x2rock.model.AppColorTheme
import com.rahga.x2rock.ui.components.tapToClick

/**
 * The fixed themes' schemes, built once. Inside the theme they were built again whenever it
 * recomposed — on every change of cover colours — and a new scheme is a changed one to everything
 * reading the theme's colours, though not one colour had moved (outside review, 2026-10-08).
 */
private val OceanScheme = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color(0xFF003048),
    primaryContainer = Color(0xFF00607A),
    onPrimaryContainer = Color(0xFFB3EBFF),
    background = Color(0xFF0A1929),
    onBackground = Color(0xFFE3F2FD),
    surface = Color(0xFF0D2137),
    onSurface = Color(0xFFE3F2FD),
    surfaceVariant = Color(0xFF1A3A50),
    onSurfaceVariant = Color(0xFFB3EBFF),
)

private val EmberScheme = darkColorScheme(
    primary = Color(0xFFFF7043),
    onPrimary = Color(0xFF3B1100),
    primaryContainer = Color(0xFF712600),
    onPrimaryContainer = Color(0xFFFFDBCF),
    background = Color(0xFF1A0A00),
    onBackground = Color(0xFFFFF3E0),
    surface = Color(0xFF2D1000),
    onSurface = Color(0xFFFFF3E0),
    surfaceVariant = Color(0xFF4A1E00),
    onSurfaceVariant = Color(0xFFFFDBCF),
)

private val ForestScheme = darkColorScheme(
    primary = Color(0xFF66BB6A),
    onPrimary = Color(0xFF003910),
    primaryContainer = Color(0xFF005320),
    onPrimaryContainer = Color(0xFFA9F4B5),
    background = Color(0xFF061209),
    onBackground = Color(0xFFE8F5E9),
    surface = Color(0xFF0D2110),
    onSurface = Color(0xFFE8F5E9),
    surfaceVariant = Color(0xFF1B3A1E),
    onSurfaceVariant = Color(0xFFA9F4B5),
)

private val OrchidScheme = darkColorScheme(
    primary = Color(0xFFCE93D8),
    onPrimary = Color(0xFF3E0056),
    primaryContainer = Color(0xFF5B0080),
    onPrimaryContainer = Color(0xFFF2DAFF),
    background = Color(0xFF120D16),
    onBackground = Color(0xFFF3E5F5),
    surface = Color(0xFF1E1525),
    onSurface = Color(0xFFF3E5F5),
    surfaceVariant = Color(0xFF36204A),
    onSurfaceVariant = Color(0xFFF2DAFF),
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun X2RockTheme(
    colorTheme: AppColorTheme = AppColorTheme.DEFAULT,
    /** The selected room's cover colours, which [AppColorTheme.ARTWORK] takes its accent from. */
    art: ArtColors? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // Built once: the dynamic scheme is dozens of resource lookups, and this runs again whenever
    // the cover colours do.
    val default = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicDarkColorScheme(context).toTvColorScheme()
        else darkColorScheme()
    }
    val colorScheme = when (colorTheme) {
        AppColorTheme.DEFAULT -> default
        // Until a cover has been read, and for a room with none, the default stands in.
        AppColorTheme.ARTWORK -> art?.let { default.copy(primary = it.accent, onPrimary = onAccent(it.accent)) } ?: default
        AppColorTheme.OCEAN -> OceanScheme
        AppColorTheme.EMBER -> EmberScheme
        AppColorTheme.FOREST -> ForestScheme
        AppColorTheme.ORCHID -> OrchidScheme
    }
    MaterialTheme(colorScheme = colorScheme, typography = TenFootTypography) {
        // The content colour, provided once at the root. tv-material derives a transparent
        // container's content colour from LocalContentColor, whose default is black, and the home
        // screen has no Surface at its root to set it — so the pane, the room panel and the room
        // rows each drew black text on the dark theme until named one by one. The cover colours
        // go down from here too, read once for the theme and the pane's tint alike.
        CompositionLocalProvider(
            LocalContentColor provides colorScheme.onSurface,
            LocalArtColors provides art,
            content = content,
        )
    }
}

/** The selected room's cover colours, or `null`; see [rememberArtColors]. */
val LocalArtColors = staticCompositionLocalOf<ArtColors?> { null }

/**
 * tv-material3's scale, one notch larger at the small end. Its defaults put most secondary text —
 * artist, station, a level, a section label — at 12sp, Google's absolute floor for television, and
 * at three metres that was the text people actually read. The floor here is 14sp, and each style
 * keeps its rank against the others, so a call site's choice still says what it means.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
private val TenFootTypography: Typography = Typography().let { t ->
    t.copy(
        titleMedium = t.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp),
        titleSmall = t.titleSmall.copy(fontSize = 16.sp, lineHeight = 22.sp),
        bodyLarge = t.bodyLarge.copy(fontSize = 18.sp, lineHeight = 24.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 16.sp, lineHeight = 22.sp),
        bodySmall = t.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelLarge = t.labelLarge.copy(fontSize = 16.sp, lineHeight = 22.sp),
        labelMedium = t.labelMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelSmall = t.labelSmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
private fun androidx.compose.material3.ColorScheme.toTvColorScheme() = darkColorScheme(
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    inversePrimary = inversePrimary,
    secondary = secondary,
    onSecondary = onSecondary,
    secondaryContainer = secondaryContainer,
    onSecondaryContainer = onSecondaryContainer,
    tertiary = tertiary,
    onTertiary = onTertiary,
    tertiaryContainer = tertiaryContainer,
    onTertiaryContainer = onTertiaryContainer,
    background = background,
    onBackground = onBackground,
    surface = surface,
    onSurface = onSurface,
    surfaceVariant = surfaceVariant,
    onSurfaceVariant = onSurfaceVariant,
    surfaceTint = surfaceTint,
    inverseSurface = inverseSurface,
    inverseOnSurface = inverseOnSurface,
    error = error,
    onError = onError,
    errorContainer = errorContainer,
    onErrorContainer = onErrorContainer,
    border = outline,
    borderVariant = outlineVariant,
    scrim = scrim,
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** A hold of the select key, or of a finger. */
    onLongClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.tapToClick(onClick, onLongClick),
        colors = ButtonDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.primary,
            focusedContentColor = MaterialTheme.colorScheme.onPrimary,
            pressedContainerColor = MaterialTheme.colorScheme.primary,
            pressedContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        content = content
    )
}

/**
 * [AppButton] holding only an icon: tighter padding, so a row of them — the transport — fits the
 * pane at 1080p without its gaps being squeezed.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun IconAppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.tapToClick(onClick),
        contentPadding = PaddingValues(12.dp),
        colors = ButtonDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.primary,
            focusedContentColor = MaterialTheme.colorScheme.onPrimary,
            pressedContainerColor = MaterialTheme.colorScheme.primary,
            pressedContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        content = content
    )
}

/**
 * A tv-material3 Card that answers a tap as well as the select key, as [AppButton] does: a row of
 * a list, with an optional second action on a hold. Card on its own ignores touch entirely. Every
 * list row in the app is one, so they share one focus border and one way of taking a tap.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    /** What a tap does, where it differs from the select key — the room list's first tap selects. */
    onTap: () -> Unit = onClick,
    colors: CardColors = CardDefaults.colors(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.tapToClick(onTap, onLongClick),
        border = appCardBorder(),
        colors = colors,
        content = content,
    )
}

/**
 * A focused card wears the same colour a focused button is filled with. tv-material3's own focus
 * for a card is a slight scale and a marginally lighter grey, which beside a button's bright fill
 * read as two focus languages — and across a room, a list's focused row was hard to find at all.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun appCardBorder(): CardBorder = CardDefaults.border(
    focusedBorder = Border(
        border = BorderStroke(3.dp, MaterialTheme.colorScheme.primary),
        shape = CardShape,
    ),
)

/** tv-material3's own card corner, named so a border or an outline drawn on a card can match it. */
val CardShape = RoundedCornerShape(8.dp)

/**
 * Focus is requested from effects that can run a frame before the target node attaches — on TV
 * that is routine. Only that specific failure is ignored; anything else should still surface.
 */
fun FocusRequester.requestFocusSafely(): Boolean =
    try {
        requestFocus()
        true
    } catch (_: IllegalStateException) {
        // FocusRequester is not initialized: the node isn't attached yet.
        false
    }

/**
 * [requestFocusSafely], retried a frame at a time while the target is not yet attached — which on
 * TV is routine for anything inside a list that has just received its items. True once it took.
 */
suspend fun FocusRequester.requestFocusRetrying(attempts: Int = 10): Boolean {
    repeat(attempts) {
        if (requestFocusSafely()) return true
        withFrameNanos { }
    }
    return false
}

/**
 * A button that names a place to go: an icon, then its label. Every such button in the app is
 * this one, so they are all the same size and spacing.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun IconLabelButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AppButton(onClick = onClick, modifier = modifier) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
fun rememberAutoFocusRequester(): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocusSafely() }
    return requester
}

fun AppColorTheme.swatchColor(): Color = when (this) {
    AppColorTheme.DEFAULT -> Color(0xFF6650A4)
    AppColorTheme.OCEAN -> Color(0xFF4FC3F7)
    AppColorTheme.EMBER -> Color(0xFFFF7043)
    AppColorTheme.FOREST -> Color(0xFF66BB6A)
    AppColorTheme.ORCHID -> Color(0xFFCE93D8)
    AppColorTheme.ARTWORK -> Color(0xFFE6A15A)
}
