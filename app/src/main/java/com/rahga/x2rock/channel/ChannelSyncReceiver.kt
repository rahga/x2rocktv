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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ChannelSyncReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: SonosRepository
    @Inject lateinit var channelSync: RoomsChannelSync

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TvContractCompat.ACTION_INITIALIZE_PROGRAMS) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                val groups = repository.getGroups().getOrNull() ?: return@launch
                val nowPlaying = coroutineScope {
                    groups.map { group ->
                        async {
                            group.id to repository.getPlaybackMetadata(group.id).getOrNull()?.currentItem?.track
                        }
                    }.awaitAll().toMap()
                }
                channelSync.sync(groups, nowPlaying)
            } finally {
                pending.finish()
            }
        }
    }
}
