package com.rahga.x2rock.repository

import android.net.Uri
import android.util.Base64
import com.rahga.x2rock.BuildConfig
import com.rahga.x2rock.auth.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SonosAuthRepository @Inject constructor(
    private val tokenStore: TokenStore,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val AUTH_ENDPOINT = "https://api.sonos.com/login/v3/oauth"
        private const val TOKEN_ENDPOINT = "https://api.sonos.com/login/v3/oauth/access"
        const val REDIRECT_URI = "https://rahga.github.io/x2rock/callback.html"
        const val BROWSER_REDIRECT_URI = "x2rock://callback"
        private const val SCOPE = "playback-control-all"
        private const val EXPIRY_MARGIN_MILLIS = 60_000L
    }

    private val refreshMutex = Mutex()

    /** Set when the refresh token is rejected — the UI routes back to login. */
    private val _sessionExpired = MutableStateFlow(false)
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    val isAuthenticated: Boolean get() = tokenStore.isAuthenticated

    fun buildAuthUrl(): String = buildAuthUrlWithRedirect(REDIRECT_URI)

    fun buildBrowserAuthUrl(): String = buildAuthUrlWithRedirect(BROWSER_REDIRECT_URI)

    private fun buildAuthUrlWithRedirect(redirectUri: String): String {
        val state = generateState()
        // Persisted, not held in memory: the browser round trip can outlive this process.
        tokenStore.pendingAuthState = state
        tokenStore.pendingRedirectUri = redirectUri
        return Uri.parse(AUTH_ENDPOINT).buildUpon()
            .appendQueryParameter("client_id", BuildConfig.SONOS_CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("state", state)
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("redirect_uri", redirectUri)
            .build()
            .toString()
    }

    suspend fun exchangeCodeForTokens(code: String, returnedState: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val expectedState = tokenStore.pendingAuthState
            if (expectedState == null || returnedState != expectedState) {
                return@withContext Result.failure(
                    SecurityException("OAuth state mismatch — possible CSRF attack")
                )
            }

            runCatching {
                val request = tokenRequest(code)
                okHttpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Token exchange failed (${response.code}): $body")
                    }
                    storeTokens(JSONObject(body))
                }
                tokenStore.pendingAuthState = null
                tokenStore.pendingRedirectUri = null
                _sessionExpired.value = false
            }
        }

    /**
     * Refreshes the access token, deduplicating concurrent callers. Sonos rotates the refresh
     * token on every use, so parallel refreshes would invalidate each other — callers that queue
     * behind an in-flight refresh return success instead of issuing their own.
     */
    suspend fun refreshAccessToken(): Result<Unit> = withContext(Dispatchers.IO) {
        val tokenBeforeLock = tokenStore.accessToken
        refreshMutex.withLock {
            if (tokenStore.accessToken != tokenBeforeLock) return@withLock Result.success(Unit)

            val refresh = tokenStore.refreshToken
            if (refresh == null) {
                _sessionExpired.value = true
                return@withLock Result.failure(IllegalStateException("No refresh token stored"))
            }

            runCatching {
                val body = FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refresh)
                    .build()
                val request = Request.Builder()
                    .url(TOKEN_ENDPOINT)
                    .addHeader("Authorization", basicAuthHeader())
                    .post(body)
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        // 400 invalid_grant / 401 — the refresh token is dead, not a transient failure.
                        if (response.code == 400 || response.code == 401) {
                            _sessionExpired.value = true
                        }
                        throw IllegalStateException("Token refresh failed (${response.code})")
                    }
                    storeTokens(JSONObject(responseBody))
                }
            }
        }
    }

    fun clearTokens() {
        tokenStore.clear()
        _sessionExpired.value = false
    }

    /** Refreshes proactively when the token is within [EXPIRY_MARGIN_MILLIS] of expiring. */
    suspend fun ensureValidToken(): Result<Unit> {
        val expiresAt = tokenStore.expiresAt
        if (expiresAt <= 0L || System.currentTimeMillis() <= expiresAt - EXPIRY_MARGIN_MILLIS) {
            return Result.success(Unit)
        }
        return refreshAccessToken()
    }

    private fun tokenRequest(code: String): Request {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", tokenStore.pendingRedirectUri ?: REDIRECT_URI)
            .build()
        return Request.Builder()
            .url(TOKEN_ENDPOINT)
            .addHeader("Authorization", basicAuthHeader())
            .post(body)
            .build()
    }

    private fun generateState(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun basicAuthHeader(): String {
        val credentials = "${BuildConfig.SONOS_CLIENT_ID}:${BuildConfig.SONOS_CLIENT_SECRET}"
        val encoded = Base64.encodeToString(
            credentials.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        return "Basic $encoded"
    }

    private fun storeTokens(json: JSONObject) {
        tokenStore.accessToken = json.getString("access_token")
        json.optString("refresh_token").takeIf { it.isNotEmpty() }?.let {
            tokenStore.refreshToken = it
        }
        val expiresIn = json.optLong("expires_in", 3600L)
        tokenStore.expiresAt = System.currentTimeMillis() + expiresIn * 1000L
    }
}
