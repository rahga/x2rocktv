package com.rahga.x2rock.cli

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.rahga.x2rock.auth.TokenStore
import java.nio.file.Files
import java.nio.file.Path

/**
 * [TokenStore] as a 0600 JSON file. Weaker than the Android keystore — anything running as this
 * user can read the refresh token — but it is the same trust model as ~/.ssh and every other CLI
 * credential on the box. Every write goes straight to disk so a concurrent `x2rocktv` invocation
 * (a keybind firing mid-refresh) sees the rotated refresh token.
 */
class FileTokenStore(private val file: Path = Xdg.configDir.resolve("tokens.json")) : TokenStore {

    private data class Tokens(
        var accessToken: String? = null,
        var refreshToken: String? = null,
        var expiresAt: Long = 0L,
        var pendingAuthState: String? = null,
        var pendingRedirectUri: String? = null
    )

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val lock = Any()
    private var tokens: Tokens = load()

    private fun load(): Tokens =
        if (Files.exists(file)) {
            runCatching { gson.fromJson(Files.readString(file), Tokens::class.java) }.getOrNull() ?: Tokens()
        } else Tokens()

    private fun update(block: Tokens.() -> Unit) = synchronized(lock) {
        tokens.block()
        Xdg.writePrivate(file, gson.toJson(tokens))
    }

    override var accessToken: String?
        get() = synchronized(lock) { tokens.accessToken }
        set(value) = update { accessToken = value }

    override var refreshToken: String?
        get() = synchronized(lock) { tokens.refreshToken }
        set(value) = update { refreshToken = value }

    override var expiresAt: Long
        get() = synchronized(lock) { tokens.expiresAt }
        set(value) = update { expiresAt = value }

    override var pendingAuthState: String?
        get() = synchronized(lock) { tokens.pendingAuthState }
        set(value) = update { pendingAuthState = value }

    override var pendingRedirectUri: String?
        get() = synchronized(lock) { tokens.pendingRedirectUri }
        set(value) = update { pendingRedirectUri = value }

    override fun clear(): Unit = synchronized(lock) {
        tokens = Tokens()
        Files.deleteIfExists(file)
    }
}
