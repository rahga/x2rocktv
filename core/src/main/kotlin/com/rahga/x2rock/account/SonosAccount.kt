package com.rahga.x2rock.account

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rahga.x2rock.lan.awaitReply
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.Inet4Address
import java.net.URLEncoder
import java.security.SecureRandom

/**
 * A developer's own registered Sonos Control integration (T-27): its key, its secret, and the
 * redirect URL registered with it. **Never shipped**: a build carries one only when its owner asks
 * (`-Px2rock.sonosIntegration`, see `app/build.gradle.kts`), and a build without one has no account
 * features at all. The default path — the LAN, no account — is untouched either way.
 */
data class Integration(val key: String, val secret: String, val redirect: String)

/** What the token endpoint answered, and when; seconds since the epoch. */
data class AccountToken(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
    val obtainedAt: Long,
) {
    /** Usable for a while yet: refreshed [REFRESH_MARGIN_SECONDS] early, so none expires on its way. */
    fun freshAt(now: Long): Boolean = now + SonosAccount.REFRESH_MARGIN_SECONDS < obtainedAt + expiresIn
}

/** Where the token is kept between launches: the app's private preferences. */
interface TokenStore {
    fun load(): AccountToken?
    fun save(token: AccountToken?)
}

/** Sonos refused the sign-in, the code, or the refresh: said in its own words. */
class SignInException(message: String) : IOException(message)

/**
 * Signing in to a Sonos account with a developer's integration, and keeping the token fresh — the
 * OAuth authorization-code flow Sonos documents for Control integrations, as x2rock's `login.rs`
 * does it. The token says who is asking: a household with **Authentication** on refuses every
 * command from a client that cannot (x2rock, 2026-09-27). Control stays on the LAN.
 *
 * Nothing here runs unless the person signed in, and nothing is sent anywhere until they do.
 */
class SonosAccount(
    client: OkHttpClient,
    val integration: Integration,
    private val store: TokenStore,
    private val base: String = "https://api.sonos.com",
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val client = client.newBuilder().build()
    private val lock = Mutex()

    private val _signedIn = MutableStateFlow(store.load() != null)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    /** The consent page to send the person to; it comes back to the redirect with [state]. */
    fun authorizeUrl(state: String): String =
        "$base/login/v3/oauth?client_id=${encode(integration.key)}&response_type=code" +
            "&state=${encode(state)}&scope=$SCOPE&redirect_uri=${encode(integration.redirect)}"

    /** Redeem the code the redirect carried, and keep the token. */
    suspend fun finish(code: String) {
        val token = post("grant_type=authorization_code&code=${encode(code)}&redirect_uri=${encode(integration.redirect)}")
        lock.withLock { store.save(token) }
        _signedIn.value = true
    }

    /**
     * The access token to send, refreshed first if it is about to run out; `null` when not signed in.
     * A refresh Sonos refuses (the grant revoked in the account) signs out; one that cannot reach Sonos
     * keeps the held token while it lasts, and the next call tries again.
     */
    suspend fun bearer(): String? = lock.withLock {
        val held = store.load() ?: return@withLock null
        if (held.freshAt(now())) return@withLock held.accessToken
        try {
            post("grant_type=refresh_token&refresh_token=${encode(held.refreshToken)}")
                // Sonos may answer a refresh without a new refresh token; the old one stands then.
                .let { if (it.refreshToken.isEmpty()) it.copy(refreshToken = held.refreshToken) else it }
                .also(store::save).accessToken
        } catch (e: SignInException) {
            store.save(null)
            _signedIn.value = false
            null
        } catch (e: IOException) {
            // Sonos out of reach: the held token serves until it actually expires.
            held.accessToken.takeIf { now() < held.obtainedAt + held.expiresIn }
        }
    }

    /** Forget the token here. Revoking the grant itself is done in the Sonos account. */
    suspend fun signOut() {
        lock.withLock { store.save(null) }
        _signedIn.value = false
    }

    private suspend fun post(form: String): AccountToken = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$base/login/v3/oauth/access")
            .header("Authorization", Credentials.basic(integration.key, integration.secret))
            .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        val reply = client.newCall(request).awaitReply()
        val json = runCatching { JsonParser.parseString(reply.body).asJsonObject }.getOrNull()
        if (reply.code !in 200..299 || json == null) {
            val said = json?.text("error_description") ?: json?.text("error")
            // A 400 or 401 is Sonos's answer about the grant; anything else — a 429 or 408 included —
            // is the way there, and must not sign out over a refresh token that still works.
            if (reply.code == 400 || reply.code == 401) throw SignInException("Sonos refused it: ${said ?: "HTTP ${reply.code}"}")
            throw IOException("Sonos's sign-in answered HTTP ${reply.code}")
        }
        AccountToken(
            accessToken = json.text("access_token") ?: throw SignInException("Sonos sent no access token"),
            refreshToken = json.text("refresh_token").orEmpty(),
            expiresIn = json.get("expires_in")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0,
            obtainedAt = now(),
        )
    }

    /** A string field, or `null` where it is absent or JSON `null` (whose `asString` throws). */
    private fun JsonObject.text(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString

    companion object {
        const val SCOPE = "playback-control-all"
        const val REFRESH_MARGIN_SECONDS = 600L
    }
}

/**
 * The `state` a sign-in on a TV sends: the TV's LAN address and the port it listens on, then a
 * random part, as `192-168-86-250-8765.<hex>`. Sonos redirects only to a public HTTPS page and echoes
 * the state untouched, so that page reads the address back out of it and sends the phone on to the
 * TV — x2rock's `<port>.<random>` with a host in front, which the page tells apart by its shape.
 * Only a private address is accepted: the page will forward nowhere else.
 */
fun signInState(address: Inet4Address, port: Int, random: SecureRandom = SecureRandom()): String {
    require(address.isSiteLocalAddress) { "$address is not a private LAN address" }
    require(port in 1..65535)
    val bytes = ByteArray(12).also(random::nextBytes)
    val host = address.hostAddress.replace('.', '-')
    return "$host-$port." + bytes.joinToString("") { "%02x".format(it) }
}

private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
