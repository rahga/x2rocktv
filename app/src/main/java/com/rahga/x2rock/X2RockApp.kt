package com.rahga.x2rock

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.rahga.x2rock.net.NetworkMonitor
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class X2RockApp : Application(), ImageLoaderFactory {

    @Inject lateinit var networkMonitor: NetworkMonitor

    /** The one client that can reach players — see [newImageLoader]. */
    @Inject lateinit var lanClient: OkHttpClient

    override fun onCreate() {
        super.onCreate()
        // Watching from the Application, not an Activity: a network change while the TV is
        // on another input still has to reach the household, or coming back would show a
        // dead socket's last known state.
        networkMonitor.start()
    }

    /**
     * Album art is served by the speakers themselves, at cleartext `sonos-<MAC>.local`
     * URLs. Coil's default loader builds its own `OkHttpClient` with `Dns.SYSTEM`, which on
     * Android cannot resolve an mDNS name at all — so every image would fail with
     * `UnknownHostException`. Handing it the LAN client gives it both the address book and
     * the cleartext exemption.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { lanClient }
            .build()
}
