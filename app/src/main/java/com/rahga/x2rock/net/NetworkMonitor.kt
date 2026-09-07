package com.rahga.x2rock.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.rahga.x2rock.lan.SonosHousehold
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells the household when the network underneath it changed.
 *
 * A TV box that moves between networks — or simply wakes — can be left holding a socket
 * that is a zombie: it accepts writes and never reports failure, so waiting for it to
 * notice would mean waiting out the 90-second silence limit at best, and forever at worst.
 * This is the signal that lets the household assume the worst and rebuild immediately.
 *
 * It is the Android counterpart of the Linux daemon's logind `PrepareForSleep` and
 * NetworkManager `StateChanged` watches; the reasons are identical, only the source differs.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val household: SonosHousehold,
) {

    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (callback != null) return
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = household.onNetworkChanged()
            override fun onLost(network: Network) = household.onNetworkChanged()
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        // Registering delivers onAvailable for the current network straight away, which is
        // harmless: the household ignores the signal when nothing is connected yet.
        runCatching { manager.registerNetworkCallback(request, cb) }
            .onSuccess { callback = cb }
    }
}
