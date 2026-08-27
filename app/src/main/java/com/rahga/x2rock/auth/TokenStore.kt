package com.rahga.x2rock.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "sonos_tokens",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()

    var expiresAt: Long
        get() = prefs.getLong(KEY_EXPIRES_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_EXPIRES_AT, value).apply()

    /**
     * OAuth CSRF state and the redirect URI it was issued for. Persisted rather than held in
     * memory because the browser sign-in round trip can outlive this process.
     */
    var pendingAuthState: String?
        get() = prefs.getString(KEY_PENDING_STATE, null)
        set(value) = prefs.edit().putString(KEY_PENDING_STATE, value).apply()

    var pendingRedirectUri: String?
        get() = prefs.getString(KEY_PENDING_REDIRECT_URI, null)
        set(value) = prefs.edit().putString(KEY_PENDING_REDIRECT_URI, value).apply()

    val isAuthenticated: Boolean
        get() = accessToken != null

    fun clear() = prefs.edit().clear().apply()

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_PENDING_STATE = "pending_auth_state"
        private const val KEY_PENDING_REDIRECT_URI = "pending_redirect_uri"
    }
}
