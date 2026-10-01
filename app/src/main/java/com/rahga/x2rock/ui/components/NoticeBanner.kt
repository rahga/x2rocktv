package com.rahga.x2rock.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * One line for a few seconds — a command that did not work, or one that did and has
 * nothing else to show it, like a save. See `TransientNotice`. Never focusable:
 * a message must not move the remote's place, and there is nothing on it to press.
 */
@Composable
fun NoticeBanner(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(bottom = 32.dp)
            .widthIn(max = 720.dp)
            // Inverse rather than the error colours: it carries "Saved as …" as well as
            // "Couldn't …", and either has to stand out from whatever is behind it.
            .background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(12.dp))
            .padding(horizontal = 24.dp, vertical = 14.dp),
    ) {
        Text(text, color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.bodyLarge)
    }
}
