package com.rahga.x2rock.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.rahga.x2rock.lan.Discovery
import com.rahga.x2rock.lan.MdnsDiscovery
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
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
 * flight fails with `FAILURE_ALREADY_ACTIVE` — and the first that parses is the answer: one
 * player is all a connect needs, since `getGroups` reports the rest.
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
        try {
            return withTimeoutOrNull(timeoutMillis) {
                for (service in found) {
                    val resolved = resolve(nsd, service) ?: continue
                    val txt = resolved.attributes.mapValues { (_, value) -> value?.decodeToString().orEmpty() }
                    Discovery.fromSonosTxt(txt)?.let { return@withTimeoutOrNull listOf(it) }
                }
                emptyList()
            } ?: emptyList()
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    @Suppress("DEPRECATION") // resolveService is deprecated from API 34; this app runs from 23.
    private suspend fun resolve(nsd: NsdManager, service: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { cont ->
            nsd.resolveService(service, object : NsdManager.ResolveListener {
                override fun onServiceResolved(info: NsdServiceInfo) { if (cont.isActive) cont.resume(info) }
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) { if (cont.isActive) cont.resume(null) }
            })
        }

    private companion object {
        const val SERVICE_TYPE = "_sonos._tcp"
    }
}
