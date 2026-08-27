package com.rahga.x2rock.cli

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Hands the OAuth redirect from the browser-spawned `x2rock oauth-callback <url>` process to
 * the `x2rock login` process that is waiting for it. A file in the runtime dir is the whole
 * protocol: the handler writes it, login polls for it and deletes it. No sockets, nothing to
 * leak, trivially inspectable when it goes wrong.
 */
object OAuthCallback {
    private val file: Path get() = Xdg.runtimeDir.resolve("callback")

    data class Code(val code: String, val state: String)

    fun deliver(url: String) {
        Xdg.writePrivate(file, url.trim())
    }

    /** Parses `x2rock://callback?code=…&state=…` (or the https hop before it). */
    fun parse(url: String): Code? {
        // x2rock:// is not an http scheme, so borrow one to reuse HttpUrl's query parsing.
        val http = url.trim().replaceFirst(Regex("^x2rock://"), "https://x2rock.invalid/")
        val parsed = http.toHttpUrlOrNull() ?: return null
        val code = parsed.queryParameter("code") ?: return null
        val state = parsed.queryParameter("state") ?: return null
        return Code(code, state)
    }

    fun clearStale() {
        Files.deleteIfExists(file)
    }

    fun await(timeout: Duration, poll: Duration = 300.milliseconds): String? {
        val start = TimeSource.Monotonic.markNow()
        while (start.elapsedNow() < timeout) {
            if (Files.exists(file)) {
                val url = Files.readString(file)
                Files.deleteIfExists(file)
                return url
            }
            Thread.sleep(poll.inWholeMilliseconds)
        }
        return null
    }
}
