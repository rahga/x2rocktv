package com.rahga.x2rock.repository

import com.rahga.x2rock.auth.SonosClientConfig
import com.rahga.x2rock.auth.TokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SonosAuthRepositoryTest {

    private class InMemoryTokenStore : TokenStore {
        override var accessToken: String? = null
        override var refreshToken: String? = null
        override var expiresAt: Long = 0L
        override var pendingAuthState: String? = null
        override var pendingRedirectUri: String? = null
        override fun clear() {
            accessToken = null; refreshToken = null; expiresAt = 0L
            pendingAuthState = null; pendingRedirectUri = null
        }
    }

    private val store = InMemoryTokenStore()
    private val repo = SonosAuthRepository(
        tokenStore = store,
        config = SonosClientConfig(clientId = "client id", clientSecret = "s3cret"),
        okHttpClient = OkHttpClient()
    )

    @Test
    fun `auth url carries every parameter and encodes the redirect`() {
        val url = repo.buildAuthUrl().toHttpUrl()

        assertEquals("api.sonos.com", url.host)
        assertEquals("/login/v3/oauth", url.encodedPath)
        assertEquals("client id", url.queryParameter("client_id"))
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals("playback-control-all", url.queryParameter("scope"))
        assertEquals(SonosAuthRepository.REDIRECT_URI, url.queryParameter("redirect_uri"))
        // The raw string must be percent-encoded: Sonos compares redirect URIs byte-for-byte.
        assertTrue(repo.buildAuthUrl().contains("redirect_uri=https%3A%2F%2F"))
    }

    @Test
    fun `state is persisted alongside the redirect it was issued for`() {
        val url = repo.buildBrowserAuthUrl().toHttpUrl()
        val state = url.queryParameter("state")

        assertNotNull(state)
        assertEquals(state, store.pendingAuthState)
        assertEquals(SonosAuthRepository.BROWSER_REDIRECT_URI, store.pendingRedirectUri)
        // URL-safe, unpadded base64 of 16 bytes — the browser round trip must not mangle it.
        assertEquals(22, state!!.length)
        assertTrue(state.matches(Regex("[A-Za-z0-9_-]+")))
    }

    @Test
    fun `each auth url gets a fresh state`() {
        val first = repo.buildAuthUrl().toHttpUrl().queryParameter("state")
        val second = repo.buildAuthUrl().toHttpUrl().queryParameter("state")
        assertNotEquals(first, second)
    }

    @Test
    fun `a mismatched state is rejected before any network call`() = runBlocking {
        repo.buildAuthUrl()
        val result = repo.exchangeCodeForTokens(code = "abc", returnedState = "forged")
        assertTrue(result.exceptionOrNull() is SecurityException)
    }

    @Test
    fun `a token well inside its lifetime is not refreshed`() = runBlocking {
        store.accessToken = "tok"
        store.expiresAt = System.currentTimeMillis() + 10 * 60_000L
        // No refresh token stored: a refresh attempt would fail, so success proves it was skipped.
        assertTrue(repo.ensureValidToken().isSuccess)
    }

    @Test
    fun `an unknown expiry is treated as valid rather than refreshed blindly`() = runBlocking {
        store.accessToken = "tok"
        store.expiresAt = 0L
        assertTrue(repo.ensureValidToken().isSuccess)
    }
}
