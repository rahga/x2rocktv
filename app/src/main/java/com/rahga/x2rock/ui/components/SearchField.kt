package com.rahga.x2rock.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * Plain text entry, outlined in the theme's colours: tv-material3 has no text field, and the
 * app sets no material3 theme for one to draw in. Focusing it brings up the system keyboard, with
 * its microphone for dictating from the remote. Shared by every search in the app.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    // The keyboard goes once the search is sent: the results are under it.
    val search = {
        keyboard?.hide()
        onSearch()
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { search() }),
        // A real keyboard's Enter, too — the emulator's, a tablet's. Only the on-screen keyboard
        // sends the search action; a key is a key.
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                    search()
                    true
                } else false
            },
        decorationBox = { inner ->
            Box(
                Modifier
                    .border(2.dp, if (focused) colors.primary else colors.border, RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (value.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
                }
                inner()
            }
        },
    )
}
