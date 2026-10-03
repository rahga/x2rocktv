package com.rahga.x2rock.radio

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A station from the directory, with the one URL worth having.
 *
 * [url] is the directory's `url_resolved`, never its `url`: the registered `url` is often a
 * `.pls` or `.m3u` *playlist*, which a player will not take, and gets the silent IDLE failure
 * for a third of the catalogue (x2rock). Nothing else is carried out of here under that name.
 */
data class Station(
    val name: String,
    val url: String,
    val favicon: String?,
    val codec: String?,
    val bitrate: Int,
    val countryCode: String?,
) {
    /** "MP3 320k", or as much of it as the directory knows. */
    val format: String?
        get() = listOfNotNull(codec?.takeIf { it.isNotBlank() }, bitrate.takeIf { it > 0 }?.let { "${it}k" })
            .joinToString(" ").ifEmpty { null }
}

/**
 * [Radio Browser](https://www.radio-browser.info): a community directory, no key and no account,
 * which is what fills the gap a stream player leaves — knowing a URL. Read the way x2rock reads
 * it: most-voted first, only stations its last check found working, a `User-Agent` because the
 * operators ask clients to identify themselves. `all.api` round-robins its mirrors, so the SRV
 * lookup the operators describe is not done here, as it is not in x2rock.
 *
 * Talks to a third party, so it goes by the internet client with a short timeout of its own,
 * never over the household's sockets. Nothing is cached: an answer is a query, not a fact.
 */
class RadioDirectory(
    client: OkHttpClient,
    private val base: String = "https://all.api.radio-browser.info",
) {
    private val client = client.newBuilder().callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
    private val gson = Gson()

    /** The most-voted working stations, narrowed to a [tag] or a [countryCode] if given. */
    suspend fun stations(tag: String? = null, countryCode: String? = null, limit: Int = 60): List<Station> =
        withContext(Dispatchers.IO) {
            val url = "$base/json/stations/search".toHttpUrl().newBuilder()
                .addQueryParameter("limit", limit.toString())
                .addQueryParameter("hidebroken", "true")
                .addQueryParameter("order", "votes")
                .addQueryParameter("reverse", "true")
                .apply { tag?.let { addQueryParameter("tag", it) } }
                .apply { countryCode?.let { addQueryParameter("countrycode", it) } }
                .build()
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            val body = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("the radio directory answered HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
            val rows: List<Row> = gson.fromJson(body, object : TypeToken<List<Row>>() {}.type) ?: emptyList()
            rows.mapNotNull { it.toStation() }
        }

    /** The directory's row, as much of it as is used. Every field optional: it is community data. */
    private data class Row(
        val name: String? = null,
        @SerializedName("url_resolved") val urlResolved: String? = null,
        val favicon: String? = null,
        val codec: String? = null,
        val bitrate: Int? = null,
        @SerializedName("countrycode") val countryCode: String? = null,
    ) {
        fun toStation(): Station? {
            val url = urlResolved?.takeIf { it.isNotBlank() } ?: return null
            return Station(
                name = name?.trim()?.takeIf { it.isNotEmpty() } ?: url,
                url = url,
                favicon = favicon?.takeIf { it.isNotBlank() },
                codec = codec,
                bitrate = bitrate ?: 0,
                countryCode = countryCode?.takeIf { it.isNotBlank() },
            )
        }
    }

    companion object {
        const val TIMEOUT_SECONDS = 10L
        const val USER_AGENT = "x2rock-tv"

        /**
         * What a remote browses by. The directory's own most-used tags include `music` and
         * `radio`, which are not genres, so the list is chosen rather than fetched.
         */
        val GENRES = listOf(
            "pop", "rock", "jazz", "classical", "news", "talk", "country", "electronic",
            "ambient", "chillout", "hip hop", "oldies", "blues", "soul", "reggae", "latin", "metal",
        )
    }
}
