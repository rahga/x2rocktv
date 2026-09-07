package com.rahga.x2rock.channel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.tvprovider.media.tv.TvContractCompat
import com.rahga.x2rock.lan.SonosHousehold
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/** A broadcast receiver gets roughly ten seconds before the system reclaims it. */
private const val BROADCAST_BUDGET_MILLIS = 8_000L

@AndroidEntryPoint
class ChannelSyncReceiver : BroadcastReceiver() {

    @Inject lateinit var household: SonosHousehold
    @Inject lateinit var channelSync: RoomsChannelSync

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TvContractCompat.ACTION_INITIALIZE_PROGRAMS) return
        val pending = goAsync()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                withTimeout(BROADCAST_BUDGET_MILLIS) {
                    // The app may not be running, so this connects rather than assuming a
                    // live household. connect() is a no-op if one is already up, and the
                    // subscriptions it opens seed their own state, so the snapshot below is
                    // read straight afterwards rather than fetched separately.
                    household.connect()
                    val state = household.state.value
                    val nowPlaying = state.groups.associate { it.id to household.groupState(it.id).track }
                    channelSync.sync(state.groups, nowPlaying)
                }
            } catch (_: TimeoutCancellationException) {
                // Out of budget. The next INITIALIZE_PROGRAMS, or the app itself, will retry.
            } catch (_: Exception) {
                // No household reachable from here — nothing to show, and no way to say so.
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }
}
