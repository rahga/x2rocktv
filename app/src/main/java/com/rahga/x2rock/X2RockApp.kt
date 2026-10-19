package com.rahga.x2rock

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.rahga.x2rock.di.InternetHttp
import com.rahga.x2rock.net.ArtHttp
import com.rahga.x2rock.net.NetworkMonitor
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class X2RockApp : Application(), SingletonImageLoader.Factory {

    @Inject lateinit var networkMonitor: NetworkMonitor

    /** The one client that can reach players — see [newImageLoader]. */
    @Inject lateinit var lanClient: OkHttpClient

    /** Ordinary certificate checks, for art from a service's CDN. */
    @Inject @field:InternetHttp lateinit var internetClient: OkHttpClient

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
     * the cleartext exemption — for a player's art only. A service's art goes by the internet
     * client, and both are bounded: see [ArtHttp].
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { ArtHttp.callFactory(lanClient, internetClient) })) }
            .build()
}
