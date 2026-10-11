package com.rahga.x2rock.ui.components

import androidx.compose.ui.text.input.KeyboardType
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * Plain text entry, outlined in the theme's colours: tv-material3 has no text field, and the
 * app sets no material3 theme for one to draw in. Focusing it brings up the system keyboard, with
 * its microphone for dictating from the remote. Shared by every search in the app.
 *
 * The D-pad leaves it as it would any control. While the keyboard is up its keys are the
 * keyboard's; once Back has closed it they reach the field, and a text field keeps every arrow
 * for its cursor — so Right never reached the Search button beside it, nor Down the results
 * (Shield, 2026-10-11). Up and Down always move focus; Right and Left only from the end of the
 * text that way, so a hardware keyboard's arrows still move the cursor within it.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    /** A number rather than words: the keyboard comes up as digits, and its key says Done. */
    numeric: Boolean = false,
    /** A web address: the keyboard comes up with `/` and `.com` to hand, and its key says Go. */
    url: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // The cursor is kept here, for the arrows; the text is still the caller's, which may refuse an edit.
    var edited by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    val field = if (edited.text == value) edited else TextFieldValue(value, TextRange(value.length))
    // The keyboard goes once the search is sent: the results are under it.
    val search = {
        keyboard?.hide()
        onSearch()
    }
    BasicTextField(
        value = field,
        onValueChange = { edited = it; if (it.text != value) onValueChange(it.text) },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = when {
            numeric -> KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)
            url -> KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false)
            else -> KeyboardOptions(imeAction = ImeAction.Search)
        },
        keyboardActions = KeyboardActions(onSearch = { search() }, onDone = { search() }, onGo = { search() }),
        // A real keyboard's Enter, too — the emulator's, a tablet's. Only the on-screen keyboard
        // sends the search action; a key is a key.
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val cursor = field.selection.takeIf { it.collapsed }?.start
                when (event.key) {
                    Key.Enter, Key.NumPadEnter -> { search(); true }
                    Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                    Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                    Key.DirectionRight -> cursor == field.text.length && focusManager.moveFocus(FocusDirection.Right)
                    Key.DirectionLeft -> cursor == 0 && focusManager.moveFocus(FocusDirection.Left)
                    else -> false
                }
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
