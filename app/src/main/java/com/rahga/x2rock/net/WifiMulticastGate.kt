package com.rahga.x2rock.net

import android.content.Context
import android.net.wifi.WifiManager
import com.rahga.x2rock.lan.MulticastGate
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds a `WifiManager.MulticastLock` across SSDP.
 *
 * Without it, Android drops inbound multicast before the app sees it when the device is on
 * Wi-Fi — and drops it silently, so discovery just returns nothing and the household looks
 * absent. On Ethernet, which is what a TV box usually is, the lock is irrelevant but
 * harmless; the manifest carries `CHANGE_WIFI_MULTICAST_STATE` for the Wi-Fi case.
 *
 * Acquisition failing is not fatal: on Ethernet the query works regardless, so this tries
 * and carries on rather than refusing to discover.
 */
@Singleton
class WifiMulticastGate @Inject constructor(
    @ApplicationContext private val context: Context,
) : MulticastGate {

    override suspend fun <T> around(block: suspend () -> T): T {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: return block()
        val lock = runCatching {
            wifi.createMulticastLock("x2rock-ssdp").apply {
                setReferenceCounted(true)
                acquire()
            }
        }.getOrNull()
        return try {
            block()
        } finally {
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
    }
}
