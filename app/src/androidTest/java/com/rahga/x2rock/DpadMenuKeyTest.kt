package com.rahga.x2rock

import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rahga.x2rock.ui.components.MediaRow
import com.rahga.x2rock.ui.theme.X2RockTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** P-69: real Android repeat events through Compose, using local rows and no household. */
@RunWith(AndroidJUnit4::class)
class DpadMenuKeyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun key(action: Int, code: Int = AndroidKeyEvent.KEYCODE_MENU, repeat: Int = 0) {
        compose.runOnIdle {
            val now = SystemClock.uptimeMillis()
            compose.activity.dispatchKeyEvent(AndroidKeyEvent(now, now, action, code, repeat))
        }
        compose.waitForIdle()
    }

    @Test fun heldMenuArmsOnceAndAnotherPressConfirms() {
        val focus = FocusRequester()
        var armed by mutableStateOf(false)
        var removed = 0
        var primary = 0
        var escapedMenuDowns = 0
        compose.setContent {
            X2RockTheme {
                Column(Modifier.onKeyEvent {
                    if (it.key == Key.Menu && it.type == KeyEventType.KeyDown) {
                        escapedMenuDowns++
                        true
                    } else false
                }) {
                    MediaRow(
                        title = "Local preset", subtitle = if (armed) "Remove?" else null, artUrl = null,
                        modifier = Modifier.focusRequester(focus),
                        onClick = { primary++ },
                        onLongClick = { if (armed) removed++ else armed = true },
                    )
                }
            }
        }
        compose.runOnIdle { focus.requestFocus() }
        key(AndroidKeyEvent.ACTION_DOWN)
        compose.onNodeWithText("Remove?").assertExists()
        for (repeat in 1..8) key(AndroidKeyEvent.ACTION_DOWN, repeat = repeat)
        key(AndroidKeyEvent.ACTION_UP)
        compose.runOnIdle {
            assertEquals("one hold only arms; it cannot confirm removal", 0, removed)
            assertEquals("repeat downs stay consumed by the row", 0, escapedMenuDowns)
            assertEquals("Menu release does not click the row", 0, primary)
        }

        key(AndroidKeyEvent.ACTION_DOWN)
        key(AndroidKeyEvent.ACTION_UP)
        compose.runOnIdle { assertEquals("a new press confirms", 1, removed) }

        key(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        key(AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle { assertEquals("Select keeps the row's primary action", 1, primary) }
    }

    @Test fun repeatsCannotActOnTheRowReceivingFocus() {
        val first = FocusRequester()
        val next = FocusRequester()
        var firstActions = 0
        var nextActions = 0
        compose.setContent {
            X2RockTheme {
                Column {
                    MediaRow(
                        title = "First row", subtitle = null, artUrl = null,
                        modifier = Modifier.focusRequester(first), onClick = {},
                        onLongClick = { firstActions++; next.requestFocus() },
                    )
                    MediaRow(
                        title = "Next row", subtitle = null, artUrl = null,
                        modifier = Modifier.focusRequester(next), onClick = {},
                        onLongClick = { nextActions++ },
                    )
                }
            }
        }
        compose.runOnIdle { first.requestFocus() }
        key(AndroidKeyEvent.ACTION_DOWN)
        compose.onNodeWithText("Next row").assertIsFocused()
        for (repeat in 1..8) key(AndroidKeyEvent.ACTION_DOWN, repeat = repeat)
        key(AndroidKeyEvent.ACTION_UP)
        compose.runOnIdle {
            assertEquals(1, firstActions)
            assertEquals("focus hand-off does not give the next row a new press", 0, nextActions)
        }
        key(AndroidKeyEvent.ACTION_DOWN)
        key(AndroidKeyEvent.ACTION_UP)
        compose.runOnIdle { assertEquals("the next deliberate press still works", 1, nextActions) }
    }
}
