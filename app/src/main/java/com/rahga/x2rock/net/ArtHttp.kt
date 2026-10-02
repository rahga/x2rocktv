package com.rahga.x2rock.net

import com.rahga.x2rock.lan.PlayerNames
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.IOException
import okio.Source
import okio.buffer
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * How cover art is fetched: bounded, and only from where art comes from.
 *
 * Art URLs arrive from the speakers and, through favourites and recently played, from whatever
 * services the household uses, so they are not ours to trust. x2rock's rules (`src/art.rs`):
 *
 * - **Only `https://`, or `http://` to a player on 1400** — a `sonos-<MAC>.local` name or a
 *   private IPv4 address. Checked on every redirect, before it is fetched, and at most
 *   [MAX_REDIRECTS] of them.
 * - **At most [MAX_BYTES] within [TIMEOUT_SECONDS]**, counted as the bytes arrive: a declared
 *   length is not believed, and a real 9 MB image is what made x2rock add the cap.
 * - **No `Content-Encoding`.** OkHttp would otherwise ask for gzip and inflate it after the
 *   count, past the cap. Asking for `identity` turns that off; any encoding that comes back
 *   anyway is refused.
 * - **The body must start like a JPEG, PNG, GIF or WebP**, the formats art comes in.
 *
 * And one this app needs that x2rock did not: **a player's art and a CDN's go by different
 * clients.** The LAN client trusts any certificate chain, because a player's root is not in any
 * store; a CDN's https is ordinary and gets the ordinary checks of [internet]. Cache bounds
 * are Coil's.
 */
object ArtHttp {

    const val MAX_BYTES = 2L * 1024 * 1024
    const val TIMEOUT_SECONDS = 8L
    const val PLAYER_PORT = 1400
    const val MAX_REDIRECTS = 3

    /** Routes each request to the client it belongs on. [playerPort] is 1400 except in tests. */
    fun callFactory(lan: OkHttpClient, internet: OkHttpClient, playerPort: Int = PLAYER_PORT): Call.Factory {
        val toPlayers = bounded(lan) { isPlayer(it, playerPort) }
        val toInternet = bounded(internet) { it.scheme == "https" }
        return Call.Factory { request ->
            (if (isPlayer(request.url, playerPort)) toPlayers else toInternet).newCall(request)
        }
    }

    /**
     * [base], bounded, fetching only URLs [allowed] admits. Redirects are followed here rather
     * than by OkHttp, because OkHttp's are fetched before any interceptor could judge them; and
     * each client keeps to its own side, so a player cannot redirect into the LAN client's
     * lenient trust for an https host, nor a CDN onto a speaker.
     */
    fun bounded(base: OkHttpClient, allowed: (HttpUrl) -> Boolean): OkHttpClient = base.newBuilder()
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(Guard(allowed))
        .build()

    /** A speaker's own art: cleartext, on its port, by its `.local` name or a private address. */
    fun isPlayer(url: HttpUrl, playerPort: Int = PLAYER_PORT): Boolean =
        url.scheme == "http" && url.port == playerPort &&
            (PlayerNames.isLocalName(url.host) || isPrivateIpv4(url.host))

    /** A literal address only: a name is never resolved here, which would be a lookup to judge a lookup. */
    private fun isPrivateIpv4(host: String): Boolean {
        if (!host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) return false
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() as? Inet4Address ?: return false
        return address.isSiteLocalAddress
    }

    private class Guard(private val allowed: (HttpUrl) -> Boolean) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            var request = chain.request().newBuilder().header("Accept-Encoding", "identity").build()
            repeat(MAX_REDIRECTS + 1) {
                if (!allowed(request.url)) throw IOException("art refused: ${request.url} is not where art comes from")
                val response = chain.proceed(request)
                if (!response.isRedirect) return checked(response)
                val next = response.header("Location")?.let { request.url.resolve(it) }
                response.close()
                request = request.newBuilder().url(next ?: throw IOException("art refused: a redirect to nowhere")).build()
            }
            throw IOException("art refused: more than $MAX_REDIRECTS redirects")
        }

        private fun checked(response: Response): Response {
            val encoding = response.header("Content-Encoding")
            if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
                response.close()
                throw IOException("art refused: Content-Encoding $encoding")
            }
            val body = response.body ?: return response
            if (body.contentLength() > MAX_BYTES) {
                response.close()
                throw IOException("art refused: ${body.contentLength()} bytes")
            }
            val source = Capped(body.source(), MAX_BYTES).buffer()
            if (response.isSuccessful) {
                val head = source.peek().use { peek -> Buffer().also { peek.read(it, 12) }.readByteArray() }
                if (!looksLikeAnImage(head)) {
                    response.close()
                    throw IOException("art refused: not an image")
                }
            }
            return response.newBuilder().body(source.asResponseBody(body.contentType(), body.contentLength())).build()
        }
    }

    internal fun looksLikeAnImage(head: ByteArray): Boolean {
        fun starts(vararg bytes: Int) = head.size >= bytes.size && bytes.indices.all { head[it] == bytes[it].toByte() }
        return starts(0xFF, 0xD8, 0xFF) ||                           // JPEG
            starts(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) || // PNG
            starts(0x47, 0x49, 0x46, 0x38) ||                         // GIF8
            (starts(0x52, 0x49, 0x46, 0x46) && head.size >= 12 &&     // RIFF....WEBP
                String(head, 8, 4, Charsets.US_ASCII) == "WEBP")
    }

    /** Fails the read once more than [limit] bytes have arrived, whatever the headers said. */
    private class Capped(delegate: Source, private val limit: Long) : ForwardingSource(delegate) {
        private var total = 0L
        override fun read(sink: Buffer, byteCount: Long): Long {
            val read = super.read(sink, byteCount)
            if (read > 0) {
                total += read
                if (total > limit) throw IOException("art refused: more than $limit bytes")
            }
            return read
        }
    }
}
