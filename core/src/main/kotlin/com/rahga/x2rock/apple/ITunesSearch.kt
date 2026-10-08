package com.rahga.x2rock.apple

import com.rahga.x2rock.lan.await
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Apple Music's catalogue through Apple's public iTunes Search API: no key, no account.
 *
 * Apple Music cannot be searched the way a service normally is — its SMAPI endpoint refuses the
 * household's credential, which carries an empty private key (x2rock, `src/itunes.rs`) — but its
 * catalogue is public and its ids are the player's. So this stands in for a service search and
 * nothing else; playing is the household's own Apple Music.
 *
 * Talks to a third party, so it goes by the internet client with a short timeout of its own,
 * never over the household's sockets. Nothing is cached: an answer is a query, not a fact.
 */
class ITunesSearch(
    client: OkHttpClient,
    private val base: String = "https://itunes.apple.com",
) {
    private val client = client.newBuilder().callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
    private val gson = Gson()

    /**
     * Songs or albums for [term], in Apple's own order. [country] is the storefront — the
     * catalogue differs by one, and a listener's account plays its own country's.
     */
    suspend fun search(term: String, kind: AppleMusicItem.Kind, country: String, limit: Int = 25): List<AppleMusicItem> =
        withContext(Dispatchers.IO) {
            val url = "$base/search".toHttpUrl().newBuilder()
                .addQueryParameter("term", term)
                .addQueryParameter("media", "music")
                .addQueryParameter("entity", if (kind == AppleMusicItem.Kind.SONG) "song" else "album")
                .addQueryParameter("country", country)
                .addQueryParameter("limit", limit.toString())
                .build()
            val body = client.newCall(Request.Builder().url(url).build()).await().use { response ->
                if (!response.isSuccessful) throw IOException("Apple's search answered HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
            (gson.fromJson(body, Reply::class.java)?.results ?: emptyList()).mapNotNull { it.toItem(kind) }
        }

    /** The API's reply, as much of it as is used. */
    private data class Reply(val results: List<Row>? = null)

    private data class Row(
        val wrapperType: String? = null,
        val kind: String? = null,
        val trackId: Long? = null,
        val collectionId: Long? = null,
        val trackName: String? = null,
        val collectionName: String? = null,
        val artistName: String? = null,
        val artworkUrl100: String? = null,
    ) {
        /** Only what was asked for: a song search can carry music videos, an album one other kinds. */
        fun toItem(want: AppleMusicItem.Kind): AppleMusicItem? = when (want) {
            AppleMusicItem.Kind.SONG -> if (kind != "song") null else trackId?.let { id ->
                AppleMusicItem(want, id, trackName ?: return null, artistName, art())
            }
            AppleMusicItem.Kind.ALBUM -> if (wrapperType != "collection") null else collectionId?.let { id ->
                AppleMusicItem(want, id, collectionName ?: return null, artistName, art())
            }
        }

        /** The 100px art the API names, asked for at 300px: the same path serves any size. */
        private fun art() = artworkUrl100?.replace("100x100bb", "300x300bb")
    }

    companion object {
        const val TIMEOUT_SECONDS = 10L
    }
}
