package com.rahga.x2rock.repository

import android.net.Uri
import android.util.Base64
import com.rahga.x2rock.BuildConfig
import com.rahga.x2rock.auth.Pkce
import com.rahga.x2rock.auth.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
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
        private const val SCOPE = "playback-control-all"
    }

    @Volatile private var pendingVerifier: String? = null
    @Volatile private var pendingState: String? = null

    val isAuthenticated: Boolean get() = tokenStore.isAuthenticated
    val accessToken: String? get() = tokenStore.accessToken

    fun buildAuthUrl(): String {
        val verifier = Pkce.generateVerifier()
        val challenge = Pkce.generateChallenge(verifier)
        val state = Pkce.generateState()
        pendingVerifier = verifier
        pendingState = state

        return Uri.parse(AUTH_ENDPOINT).buildUpon()
            .appendQueryParameter("client_id", BuildConfig.SONOS_CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("state", state)
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
            .toString()
    }

    suspend fun exchangeCodeForTokens(code: String, returnedState: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val expectedState = pendingState
            if (returnedState != expectedState) {
                return@withContext Result.failure(
                    SecurityException("OAuth state mismatch — possible CSRF attack")
                )
            }
            val verifier = pendingVerifier
                ?: return@withContext Result.failure(
                    IllegalStateException("No PKCE verifier found — call buildAuthUrl() first")
                )

            runCatching {
                val response = okHttpClient.newCall(tokenRequest(code, verifier)).execute()
                val body = response.body?.string()
                    ?: throw IllegalStateException("Empty response from token endpoint")
                if (!response.isSuccessful) {
                    throw IllegalStateException("Token exchange failed (${response.code}): $body")
                }
                storeTokens(JSONObject(body))
                pendingVerifier = null
                pendingState = null
            }
        }

    suspend fun refreshAccessToken(): Result<Unit> = withContext(Dispatchers.IO) {
        val refresh = tokenStore.refreshToken
            ?: return@withContext Result.failure(
                IllegalStateException("No refresh token stored")
            )
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
            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string()
                ?: throw IllegalStateException("Empty response from token endpoint")
            if (!response.isSuccessful) {
                throw IllegalStateException("Token refresh failed (${response.code}): $responseBody")
            }
            storeTokens(JSONObject(responseBody))
        }
    }

    fun clearTokens() = tokenStore.clear()

    private fun tokenRequest(code: String, verifier: String): Request {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("code_verifier", verifier)
            .build()
        return Request.Builder()
            .url(TOKEN_ENDPOINT)
            .addHeader("Authorization", basicAuthHeader())
            .post(body)
            .build()
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
