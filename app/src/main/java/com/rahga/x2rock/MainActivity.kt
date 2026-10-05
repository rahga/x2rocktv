package com.rahga.x2rock

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.rahga.x2rock.auth.PendingRoomDeepLink
import com.rahga.x2rock.auth.ThemeStore
import com.rahga.x2rock.ui.X2RockNavGraph
import com.rahga.x2rock.ui.theme.X2RockTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var themeStore: ThemeStore
    @Inject lateinit var pendingRoomDeepLink: PendingRoomDeepLink

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        // There is no sign-in step any more: the speakers are on the LAN and answer
        // without an account, so the app opens straight onto the rooms.
        setContent {
            val theme by themeStore.theme.collectAsState()
            X2RockTheme(colorTheme = theme) {
                X2RockNavGraph()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * Esc is Back, as it is on ChromeOS: a keyboard has no Back key, and without this a dialog
     * opened from one could not be left at all. Rewritten as a Back event rather than handled
     * here, so everything that answers Back answers Esc too, with no second list to keep.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        super.dispatchKeyEvent(
            if (event.keyCode != KeyEvent.KEYCODE_ESCAPE) event
            else KeyEvent(
                event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_BACK,
                event.repeatCount, event.metaState, event.deviceId, event.scanCode,
                event.flags, event.source,
            )
        )

    private fun handleIntent(intent: Intent) {
        val data = intent.data ?: return
        if (data.scheme == "x2rock" && data.host == "room") {
            pendingRoomDeepLink.set(data.lastPathSegment ?: return)
        }
    }
}
