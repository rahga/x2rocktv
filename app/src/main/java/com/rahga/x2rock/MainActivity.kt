package com.rahga.x2rock

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import com.rahga.x2rock.auth.PendingRoomDeepLink
import com.rahga.x2rock.auth.ThemeStore
import com.rahga.x2rock.lan.SeedStore
import com.rahga.x2rock.ui.X2RockNavGraph
import com.rahga.x2rock.ui.theme.X2RockTheme
import com.rahga.x2rock.ui.theme.rememberArtColors
import com.rahga.x2rock.viewmodel.PlayerViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var themeStore: ThemeStore
    @Inject lateinit var pendingRoomDeepLink: PendingRoomDeepLink
    @Inject lateinit var seeds: SeedStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Debug builds only, before anything connects: see DebugSwitches.
        if (BuildConfig.DEBUG && intent.getBooleanExtra("debugMdnsOnly", false)) {
            DebugSwitches.mdnsOnly = true
            seeds.clear()
        }
        if (BuildConfig.DEBUG) {
            intent.getStringExtra("debugServiceEnvelope")?.let { DebugSwitches.serviceEnvelope = it }
        }
        handleIntent(intent)
        // There is no sign-in step any more: the speakers are on the LAN and answer
        // without an account, so the app opens straight onto the rooms.
        setContent {
            val theme by themeStore.theme.collectAsState()
            // The same instance the nav graph uses: both ask the activity's store. Only the art URL
            // is read — the player's state changes at every position tick and volume step, and
            // reading all of it here recomposed the whole app on each. The colours feed the pane's
            // tint under every theme, and the accent under the Artwork one.
            val player: PlayerViewModel = hiltViewModel()
            val artUrl by remember(player) { player.uiState.map { it.albumArtUrl }.distinctUntilChanged() }
                .collectAsState(initial = null)
            X2RockTheme(colorTheme = theme, art = rememberArtColors(artUrl)) {
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
