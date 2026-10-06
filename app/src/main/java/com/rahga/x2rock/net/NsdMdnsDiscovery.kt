package com.rahga.x2rock.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.rahga.x2rock.BuildConfig
import com.rahga.x2rock.lan.Discovery
import com.rahga.x2rock.lan.MdnsDiscovery
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * [MdnsDiscovery] over Android's `NsdManager`: browse `_sonos._tcp`, resolve what turns up,
 * and read the player out of its TXT record with [Discovery.fromSonosTxt].
 *
 * The system's own resolver does the multicast, so no `MulticastLock` is needed for this, unlike
 * SSDP. Services are resolved one at a time — before API 34 a second resolve while one is in
 * flight fails with `FAILURE_ALREADY_ACTIVE` — for the whole window, because one player is all
 * a connect needs but only all of them show whether there are two households.
 */
@Singleton
class NsdMdnsDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
) : MdnsDiscovery {

    override suspend fun find(timeoutMillis: Long): List<Discovery.DiscoveredPlayer> {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return emptyList()
        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) { found.trySend(service) }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { found.close() }
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        if (runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }.isFailure) {
            return emptyList()
        }
        val players = LinkedHashMap<String, Discovery.DiscoveredPlayer>()
        try {
            withTimeoutOrNull(timeoutMillis) {
                for (service in found) {
                    val resolved = resolve(nsd, service) ?: continue
                    val txt = resolved.attributes.mapValues { (_, value) -> value?.decodeToString().orEmpty() }
                    @Suppress("DEPRECATION") // host is deprecated from API 34 in favour of hostAddresses.
                    Discovery.fromSonosTxt(txt, resolved.host)?.let { players.putIfAbsent(it.id, it) }
                }
            }
            // What mDNS found, in a debug build: the evidence the fallback actually ran.
            if (BuildConfig.DEBUG) Log.d("x2rock.mdns", "found ${players.size}: ${players.values.map { "${it.id} at ${it.address}" }}")
            return players.values.toList()
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    /**
     * One resolve at a time, and never abandoned: before API 34 a second resolve while one is
     * in flight fails `FAILURE_ALREADY_ACTIVE`, and the system's cannot be cancelled. A window
     * that runs out mid-resolve therefore waits for that resolve to finish, a few seconds at
     * most, rather than leaving it to fail the next find's first player.
     */
    @Suppress("DEPRECATION") // resolveService is deprecated from API 34; this app runs from 23.
    private suspend fun resolve(nsd: NsdManager, service: NsdServiceInfo): NsdServiceInfo? =
        resolving.withLock {
            withContext(NonCancellable) {
                suspendCancellableCoroutine { cont ->
                    nsd.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(info: NsdServiceInfo) { if (cont.isActive) cont.resume(info) }
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) { if (cont.isActive) cont.resume(null) }
                    })
                }
            }
        }

    private val resolving = Mutex()

    private companion object {
        const val SERVICE_TYPE = "_sonos._tcp"
    }
}
