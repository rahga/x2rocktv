package com.rahga.x2rock

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.Text
import com.rahga.x2rock.ui.theme.AppButton
import com.rahga.x2rock.ui.components.SearchField
import com.rahga.x2rock.ui.theme.X2RockTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The D-pad leaves the search field (PUNCH P-19): with the keyboard closed, a text field kept every
 * arrow for its cursor, so the Search button beside it and the results below it could not be
 * reached from the remote. Keys go straight into Compose here, as they reach the field once the
 * keyboard is gone. Run on the emulator, as [FocusChoreographyTest] is.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SearchFieldFocusTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val field = FocusRequester()

    private fun show(initial: String) = compose.setContent {
        X2RockTheme {
            var query by remember { mutableStateOf(initial) }
            Column {
                Row {
                    SearchField(
                        value = query, placeholder = "Search", onValueChange = { query = it }, onSearch = {},
                        modifier = Modifier.weight(1f).focusRequester(field),
                    )
                    AppButton(onClick = {}) { Text("Search button") }
                }
                AppButton(onClick = {}) { Text("First result") }
            }
        }
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun focusField() {
        compose.runOnIdle { field.requestFocus() }
        assertFocusOn("the field", hasSetTextAction())
    }

    private fun assertFocusOn(what: String, matcher: SemanticsMatcher) {
        compose.waitUntil(5_000) {
            compose.onAllNodes(isFocused() and (matcher or hasAnyDescendant(matcher))).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun rightFromTheEndOfTheTextReachesTheSearchButton() {
        show("miles davis")
        focusField()
        press(Key.DirectionRight)
        assertFocusOn("the Search button", hasText("Search button"))
    }

    @Test fun downFromTheFieldReachesTheResults() {
        show("miles davis")
        focusField()
        press(Key.DirectionDown)
        assertFocusOn("the first result", hasText("First result"))
    }

    /** Within the text, Right is still the cursor's: a hardware keyboard edits as before. */
    @Test fun rightWithinTheTextStaysInTheField() {
        show("miles davis")
        focusField()
        press(Key.DirectionLeft)   // off the end
        press(Key.DirectionRight)  // back to it, still in the field
        assertFocusOn("the field", hasSetTextAction())
        org.junit.Assert.assertTrue(
            "focus left the field from inside its text",
            compose.onAllNodes(isFocused() and hasText("Search button")).fetchSemanticsNodes().isEmpty(),
        )
        press(Key.DirectionRight)  // from the end, out
        assertFocusOn("the Search button", hasText("Search button"))
    }
}
