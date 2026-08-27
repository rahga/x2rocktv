package com.rahga.x2rock.auth

/**
 * Where OAuth tokens live between runs. The Android app backs this with the device keystore;
 * a desktop frontend supplies its own. Implementations must be safe to call from any thread.
 */
interface TokenStore {
    var accessToken: String?
    var refreshToken: String?
    var expiresAt: Long

    /**
     * OAuth CSRF state and the redirect URI it was issued for. Persisted rather than held in
     * memory because the browser sign-in round trip can outlive the process.
     */
    var pendingAuthState: String?
    var pendingRedirectUri: String?

    val isAuthenticated: Boolean get() = accessToken != null

    fun clear()
}
