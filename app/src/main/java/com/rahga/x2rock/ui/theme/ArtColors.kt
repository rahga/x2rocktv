package com.rahga.x2rock.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Two colours taken from a cover: an [accent] bright enough to fill a focused button on a dark
 * screen, and a [shade] dark enough to sit behind white text. Sonos 27 tints Now Playing from the
 * art the same way.
 */
data class ArtColors(val accent: Color, val shade: Color)

/**
 * The [ArtColors] of the art at [url], or `null` while it is read or when there is none. Through
 * the app's own image loader, so the art rules in `ArtHttp` apply and a cover already on screen
 * comes from Coil's cache rather than the network.
 */
@Composable
fun rememberArtColors(url: String?): ArtColors? {
    val context = LocalContext.current
    // Not keyed on the URL: the last cover's colours stand until the new one's are read, so a track
    // change eases from one tint to the next instead of dipping to plain between them.
    var colors by remember { mutableStateOf<ArtColors?>(null) }
    LaunchedEffect(url) {
        if (url == null) {
            colors = null
            return@LaunchedEffect
        }
        val request = ImageRequest.Builder(context).data(url).allowHardware(false).size(SAMPLE_PX).build()
        val bitmap = ((context.imageLoader.execute(request) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
            ?: return@LaunchedEffect
        colors = withContext(Dispatchers.Default) { artColorsOf(Palette.from(bitmap).generate()) }
    }
    return colors
}

private fun artColorsOf(palette: Palette): ArtColors? {
    val vivid = palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: palette.dominantSwatch ?: return null
    val dark = palette.darkMutedSwatch ?: palette.darkVibrantSwatch ?: palette.dominantSwatch ?: vivid
    return ArtColors(accent = readableAccent(Color(vivid.rgb)), shade = deepShade(Color(dark.rgb)))
}

/** Lifted toward white until it reads as a fill on a dark screen — a muddy cover gives a muddy accent otherwise. */
internal fun readableAccent(color: Color): Color {
    var c = color
    repeat(6) { if (c.luminance() < MIN_ACCENT_LUMINANCE) c = Color.White.copy(alpha = 0.25f).compositeOver(c) }
    return c
}

/** Pressed toward black, so white text over it keeps its contrast whatever the cover. */
internal fun deepShade(color: Color): Color = Color.Black.copy(alpha = 0.6f).compositeOver(color)

/** Text on an [ArtColors.accent] fill: dark on a light accent, light on a dark one. */
fun onAccent(accent: Color): Color = if (accent.luminance() > 0.4f) Color(0xFF111111) else Color.White

private const val SAMPLE_PX = 128
private const val MIN_ACCENT_LUMINANCE = 0.3f
