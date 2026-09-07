package com.rahga.x2rock.lan

import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Where each `sonos-<MAC>.local` name lives on this network.
 *
 * The names come from [PlayerNames]; the addresses come from `getGroups`, whose
 * `websocketUrl` carries each player's IP, or from [Discovery] on a cold start. Nothing
 * here does mDNS — Android's resolver cannot, and it never has to.
 *
 * Addresses are DHCP hints, not facts. Re-register on every reconnect rather than
 * trusting what worked last time.
 */
class PlayerAddressBook : Dns {

    private val byHostname = ConcurrentHashMap<String, InetAddress>()

    fun register(hostname: String, address: InetAddress) {
        byHostname[hostname.lowercase()] = address
    }

    fun register(hostname: String, address: String) {
        register(hostname, InetAddress.getByName(address))
    }

    fun forget(hostname: String) {
        byHostname.remove(hostname.lowercase())
    }

    fun clear() = byHostname.clear()

    override fun lookup(hostname: String): List<InetAddress> {
        byHostname[hostname.lowercase()]?.let { return listOf(it) }
        // A .local name we were never told about will not resolve on Android, and the
        // exception says so plainly rather than surfacing as a confusing connect failure.
        if (PlayerNames.isLocalName(hostname)) {
            throw UnknownHostException("$hostname is not in the address book; register it from getGroups or discovery")
        }
        return Dns.SYSTEM.lookup(hostname)
    }
}

/**
 * The one OkHttp client used to reach players, and the compromises it makes.
 *
 * **Certificates.** A player presents a leaf-only chain: its own certificate, signed by
 * `CN=Sonos Device Authentication Root CA`, with that root *not* included and not in any
 * system trust store. So the chain cannot be validated and a custom trust manager is
 * required. If that root is ever obtained out of band, this becomes a normal
 * `trust-anchors` configuration and the compromise disappears — the leaf is RSA-2048 /
 * SHA-256 over TLS 1.3, so nothing else about it is unusual.
 *
 * **Hostnames are NOT relaxed.** OkHttp's default verifier is left in force, because we
 * connect by the `.local` name the certificate is actually issued for. That is the whole
 * point of [PlayerNames]. Do not add a permissive `hostnameVerifier` here.
 *
 * Keep this client for players only. The app's other traffic must not inherit it.
 */
object LanHttp {

    /** Matches the Rust daemon's keepalive: ping every 30s, treat 90s of silence as dead. */
    val PING_INTERVAL: Long = 30
    val SILENCE_LIMIT_MILLIS: Long = 90_000

    fun client(addressBook: PlayerAddressBook): OkHttpClient {
        val trust = leafOnlyTrustManager()
        val ssl = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trust), SecureRandom())
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trust)
            // No .hostnameVerifier(): see the note above.
            .dns(addressBook)
            .pingInterval(PING_INTERVAL, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)   // a subscription is meant to sit idle
            .build()
    }

    /**
     * Accepts the player's certificate without a chain to validate it against. Scoped to
     * the client above; see the class comment for why it is unavoidable today.
     */
    private fun leafOnlyTrustManager() = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
