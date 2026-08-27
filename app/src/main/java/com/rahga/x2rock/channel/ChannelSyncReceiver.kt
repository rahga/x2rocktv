package com.rahga.x2rock.channel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.tvprovider.media.tv.TvContractCompat
import com.rahga.x2rock.repository.SonosRepository
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

    @Inject lateinit var repository: SonosRepository
    @Inject lateinit var channelSync: RoomsChannelSync

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TvContractCompat.ACTION_INITIALIZE_PROGRAMS) return
        val pending = goAsync()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                withTimeout(BROADCAST_BUDGET_MILLIS) {
                    val groups = repository.getGroups().getOrNull() ?: return@withTimeout
                    channelSync.sync(groups, repository.getNowPlaying(groups))
                }
            } catch (_: TimeoutCancellationException) {
                // Out of budget. The next INITIALIZE_PROGRAMS, or the app itself, will retry.
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }
}
