package com.rahga.x2rock.smapi

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.rahga.x2rock.lan.Xml
import org.w3c.dom.Element
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * SMAPI: rating a track directly against the music service that carries it — not a Sonos
 * API. Sonos is the client here and the service is the server, so this is the one part of
 * the app that leaves both the LAN and Sonos itself: a service's presentation map and
 * manifest live on Sonos's CDN, and `getExtendedMetadata`/`rateItem` go straight to the
 * service's own endpoint.
 *
 * Ported from x2rock's `sonos/smapi.rs`, verified there against a real household's
 * iHeartRadio account on 2026-09-12, and trimmed to what a rating button needs — no search,
 * no browse. [Auth] and [Token] carry the full shape a device-linked service needs even though
 * nothing mints a token yet; every call here is `token = null`, which only an
 * [Auth.ANONYMOUS] service accepts. **iHeartRadio is not one**: the home household lists it
 * as `DeviceLink` (checked 2026-10-01), and x2rock rated it with a linked token. So until a
 * device link exists here, iHeartRadio tracks show no rating buttons at all.
 */

private const val NS = "http://www.sonos.com/Services/1.1"

/**
 * A `User-Agent` on every request. Not cosmetic: some service endpoints answer a request with
 * none at all with an empty HTTP 200 or 500 — Deezer and Amazon Music both do (x2rock learned
 * this the hard way, `sonos/http.rs`), which reads like a broken endpoint rather than a missing
 * header. Any value satisfies them; this names the client honestly.
 */
private const val USER_AGENT = "x2rock-tv/1.0"

/** Short: a rate call is a UI button press, not a background job. */
private const val TIMEOUT_SECONDS = 8L

/** How a service expects to be authenticated, from its `<Policy Auth=...>`. */
enum class Auth { ANONYMOUS, DEVICE_LINK, APP_LINK }

/** A `loginToken` for the SMAPI credentials header — the shape a linked account will need. */
data class Token(val token: String, val key: String, val household: String? = null)

/** One entry from `ListAvailableServices`, enough to address and authenticate it. */
data class Service(
    val id: String,
    val name: String,
    val uri: String,
    val auth: Auth,
    /** Where the manifest lives — the presentation map, and so the ratings/search rules, hang off it. */
    val manifestUri: String?,
    /**
     * `serviceId * 256 + type`, from `AvailableServiceTypeList` — the number a cdudn is built
     * from: `SA_RINCON<type>_X_#Svc<type>-<selector>-Token`. Absent for a service the type
     * list does not mention; only an enqueue path needs it, and search/browse do not.
     */
    val serviceType: Long? = null,
)

/**
 * One search or browse hit, flattened from `mediaMetadata` (something to play) or
 * `mediaCollection` (something to descend into).
 */
data class Item(
    val id: String,
    val title: String,
    /** `stream`, `track`, `album`, `artist`, `playlist`, … */
    val itemType: String,
    val summary: String?,
    /** Whatever the service offers as a cover; services keep it in different places. */
    val artUrl: String?,
    /**
     * A `mediaCollection` rather than a `mediaMetadata`. **`canPlay` is deliberately not what
     * decides this** — iHeartRadio marks an `artist_radio` collection `canPlay` yet refuses its
     * id to `getMediaURI`, so the reliable question is which element the item arrived in. The
     * one exception is a collection whose own `itemType` names a leaf (`track`/`stream`), which
     * Plex answers a tracks search with and whose id does play.
     */
    val container: Boolean,
)

/** A searchable category, from a service's presentation map. */
data class Category(
    /** What a person picks: `tracks`, `albums`, `stations`. */
    val id: String,
    /** What the service is actually sent: `search:station`, `SONGS`, `STRK`. */
    val mappedId: String,
)

/** A page of hits and the total the service claims it could return. */
data class ItemPage(val items: List<Item>, val total: Int)

/**
 * Where to resume a thing that is listened to in place rather than played from the start — an
 * audiobook, a long podcast. From the `positionInformation` a service puts in `getMetadata`:
 * [id] is the *chapter* to play (not the book, which is not itself playable), and [offsetMillis]
 * is how far into it the listener had reached. Verified against Audible, 2026-10-06.
 */
data class ResumePoint(val id: String, val index: Int, val offsetMillis: Long)

/** A book's chapters and where to resume it, both out of one `getMetadata`. */
data class Chapters(val chapters: ItemPage, val resume: ResumePoint?)

/**
 * What `rateItem` wants back for a given direction. **Not a fixed per-direction constant** —
 * iHeartRadio hands out a different id for "rate this up" depending on whether the track is
 * currently unrated, already up, or already down (verified against a real household,
 * 2026-09-12). Always read from whichever [RatingsMatch] the track's current state selected.
 */
data class Rating(val id: String, val stringId: String)

/**
 * "When the current track's rating state matches this property, offer these ratings" — one
 * row of a service's `NowPlayingRatings` presentation map. A service publishes 1-3 of these:
 * unrated, already-up, already-down, each with its own pair of ids.
 */
data class RatingsMatch(val propname: String, val value: String, val ratings: List<Rating>) {

    /**
     * Which way a track in this state is already rated, read off [propname]. iHeartRadio
     * names its three states `thumbs_up_selected`, `thumbs_down_selected` and `unselected`;
     * a service naming them otherwise reads [Thumb.NONE] — still rateable, just not drawn as
     * already rated, which is the safe way to be wrong.
     */
    val selected: Thumb
        get() = propname.lowercase().let {
            when {
                "down" in it -> Thumb.DOWN
                "up" in it -> Thumb.UP
                else -> Thumb.NONE
            }
        }

    companion object {
        /**
         * The state the track is in now: whichever match one of `getExtendedMetadata`'s
         * dynamic properties names exactly. `null` when none does, which leaves nothing to
         * choose ids from.
         */
        fun current(matches: List<RatingsMatch>, properties: List<Pair<String, String>>): RatingsMatch? =
            properties.firstNotNullOfOrNull { (name, value) ->
                matches.firstOrNull { it.propname == name && it.value == value }
            }

        /**
         * The [Rating] a caller means by "up" or "down", matched on [Rating.stringId] — the
         * only signal a service gives for which is which.
         */
        fun find(matches: List<RatingsMatch>, propname: String, value: String, up: Boolean): Rating? {
            val word = if (up) "UP" else "DOWN"
            return matches.firstOrNull { it.propname == propname && it.value == value }
                ?.ratings?.firstOrNull { it.stringId.uppercase().contains(word) }
        }
    }
}

/** How a track is already rated with its service. */
enum class Thumb { NONE, UP, DOWN }

/** What `rateItem` answers. */
data class RateResult(
    /**
     * Whether the service wants the room to advance immediately — the live, per-call answer
     * to act on. The presentation map's declared `AutoSkip` is only the service's stated
     * policy and is deliberately not carried here; a different service may answer this
     * differently from what it declared.
     */
    val shouldSkip: Boolean?,
    /** Untranslated, and only for logging — no localized-string layer exists here. */
    val messageStringId: String?,
)

/** A service refused the call, or answered nothing readable. */
class SmapiException(message: String) : IOException(message)

/**
 * One SMAPI client for everything a rating button needs, over a plain internet
 * [OkHttpClient] — never the LAN one built for players, which trusts a leaf-only
 * certificate chain that has no business being extended to the rest of the internet.
 */
class SmapiClient(client: OkHttpClient) {

    private val client = client.newBuilder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * A service's rating rules, from its presentation map — empty if it publishes none,
     * which is the ordinary case: ratings are a Pandora/iHeartRadio-shaped radio feature,
     * not a Sonos-wide capability.
     */
    suspend fun ratings(service: Service): List<RatingsMatch> = withContext(Dispatchers.IO) {
        presentationMap(service)?.let { parseRatingsMap(it) } ?: emptyList()
    }

    /**
     * The dynamic, per-track properties `getExtendedMetadata` carries — where a current
     * rating state (`thumbs_up_selected`, `5`, say) is reported. Match it against a
     * [RatingsMatch] to find which ids to offer right now.
     */
    suspend fun extendedMetadata(service: Service, token: Token?, id: String): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            parseDynamicProperties(call(service, token, "getExtendedMetadata", "<id>${Xml.escape(id)}</id>"))
        }

    /**
     * `search`: the hits for [term] under [category] (a [Category.mappedId]), and the total the
     * service claims. The page is cut to [count] — `count` is a request, not a promise: Amazon
     * Music answers a search for 2 with every hit it has, and everything downstream reads the
     * page as the size asked for. [total] is left as the service reported it.
     */
    suspend fun search(
        service: Service, token: Token?, category: String, term: String, index: Int = 0, count: Int = 30,
    ): ItemPage = withContext(Dispatchers.IO) {
        val body = call(
            service, token, "search",
            "<id>${Xml.escape(category)}</id><term>${Xml.escape(term)}</term>" +
                "<index>$index</index><count>$count</count>",
        )
        atMost(parseItems(body), count)
    }

    /**
     * `getMetadata`: what a container holds — the only way to reach the parts of a service a
     * search term cannot name (a personal library, a "For You", a genre tree). `root` is where
     * every service starts.
     */
    suspend fun metadata(
        service: Service, token: Token?, id: String = "root", index: Int = 0, count: Int = 100,
    ): ItemPage = withContext(Dispatchers.IO) {
        val body = call(
            service, token, "getMetadata",
            "<id>${Xml.escape(id)}</id><index>$index</index><count>$count</count>",
        )
        atMost(parseItems(body), count)
    }

    /**
     * A book's chapters and its resume point, from one `getMetadata`. The book id (`reftitle:…`
     * for Audible) is a container of chapters and is **not itself playable** — resuming means
     * playing the chapter [ResumePoint.id] names and seeking to its offset, or, with no resume
     * point, the first chapter from the start. [count] bounds the chapters fetched; the resume
     * point is returned whatever it is.
     */
    suspend fun chapters(service: Service, token: Token?, bookId: String, count: Int = 1): Chapters = withContext(Dispatchers.IO) {
        val body = call(
            service, token, "getMetadata",
            "<id>${Xml.escape(bookId)}</id><index>0</index><count>$count</count>",
        )
        Chapters(atMost(parseItems(body), count), parsePositionInformation(body))
    }

    /** `getMediaURI`: turn a playable hit's id into something a player can be handed. */
    suspend fun mediaUri(service: Service, token: Token?, id: String): String = withContext(Dispatchers.IO) {
        val body = call(service, token, "getMediaURI", "<id>${Xml.escape(id)}</id>")
        Xml.parse(body).firstNamed("getMediaURIResult")?.textContent
            ?: throw SmapiException("${service.name} returned no media URI for $id")
    }

    /**
     * The categories a service will accept in `search`, from its presentation map — empty when
     * it publishes none, which is a fact about the service (it cannot be searched, only
     * browsed) rather than an error.
     */
    suspend fun categories(service: Service): List<Category> = withContext(Dispatchers.IO) {
        presentationMap(service)?.let { parseSearchCategories(it) } ?: emptyList()
    }

    /** Rate the currently playing item. `rating` is a [Rating.id] from the current [RatingsMatch]. */
    suspend fun rateItem(service: Service, token: Token?, id: String, rating: String): RateResult =
        withContext(Dispatchers.IO) {
            parseRateResult(
                call(
                    service, token, "rateItem",
                    "<id>${Xml.escape(id)}</id><rating>${Xml.escape(rating)}</rating>",
                )
            )
        }

    /** The presentation map body, or `null` when the service publishes no manifest for one. */
    private fun presentationMap(service: Service): String? {
        val manifestUri = service.manifestUri ?: return null
        val manifestBody = get(manifestUri) ?: return null
        val mapUri = runCatching { JsonParser.parseString(manifestBody) }.getOrNull()
            ?.asJsonObject?.getAsJsonObject("presentationMap")?.get("uri")?.takeIf { it.isJsonPrimitive }
            ?.asString ?: return null
        return get(mapUri)
    }

    private fun get(url: String): String? {
        client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }

    /** One SMAPI call. A service that needs an account is refused here, not sent and rejected. */
    private fun call(service: Service, token: Token?, action: String, params: String, retried: Boolean = false): String {
        if (service.auth != Auth.ANONYMOUS && token == null) {
            throw SmapiException("${service.name} needs an account linked before it can be used")
        }
        val request = Request.Builder()
            .url(service.uri)
            .header("SOAPACTION", "\"$NS#$action\"")
            .header("User-Agent", USER_AGENT)
            .post(envelope(action, params, token).toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) {
                throw SmapiException("${service.name} answered HTTP ${response.code} with an empty body")
            }
            val root = runCatching { Xml.parse(body) }.getOrNull()
            root?.let { faultMessage(it) }?.let { fault ->
                // The household's stored token can be stale; a `tokenRefreshRequired` fault answers
                // the call with a working replacement in its detail rather than just refusing, so
                // the call retries once with it. Seen on TIDAL and a second Amazon account against a
                // household whose token had aged (2026-10-06). The refresh is not persisted here —
                // the next connection captures whatever the household now holds.
                refreshedToken(root, token)?.takeUnless { retried }?.let { fresh ->
                    return call(service, fresh, action, params, retried = true)
                }
                throw SmapiException("${service.name} refused $action: $fault")
            }
            if (!response.isSuccessful) {
                throw SmapiException("${service.name} $action failed: HTTP ${response.code}")
            }
            return body
        }
    }

    /**
     * A working replacement token out of a `tokenRefreshRequired` fault's `refreshAuthTokenResult`,
     * or `null` when the fault carries none. Only for a call that already had a token: a refresh
     * replaces a credential, it does not mint a first one.
     */
    private fun refreshedToken(root: Element, token: Token?): Token? {
        if (token == null) return null
        val result = root.allNamed("refreshAuthTokenResult").firstOrNull() ?: return null
        val authToken = result.firstNamed("authToken")?.textContent ?: return null
        return token.copy(token = authToken, key = result.firstNamed("privateKey")?.textContent.orEmpty())
    }

    private fun envelope(action: String, params: String, token: Token?): String {
        val login = token?.let {
            val household = it.household?.let { h -> "<householdId>${Xml.escape(h)}</householdId>" }.orEmpty()
            "<loginToken><token>${Xml.escape(it.token)}</token><key>${Xml.escape(it.key)}</key>$household</loginToken>"
        }.orEmpty()
        return "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
            "<s:Header><credentials xmlns=\"$NS\"><deviceProvider>Sonos</deviceProvider>$login</credentials></s:Header>" +
            "<s:Body><$action xmlns=\"$NS\">$params</$action></s:Body></s:Envelope>"
    }
}

// ------------------------------------------------------------------ parsing
//
// Pure, and kept separate from anything that touches the network, so each can be tested
// against a captured payload with no round trip — the same discipline `Upnp.kt` follows.

/**
 * `ListAvailableServices`'s `AvailableServiceDescriptorList`, unescaped XML by the time it gets
 * here. [typeList] is the matching `AvailableServiceTypeList` — a comma-separated list of
 * `serviceId * 256 + type` numbers, which is the only place the service *type* (for a cdudn)
 * is given; pass it empty when only search/browse is wanted, which needs no type.
 */
fun parseServices(descriptorList: String, typeList: String = ""): List<Service> {
    // serviceId -> serviceId * 256 + type. The list gives only the combined number.
    val types = typeList.split(',').mapNotNull { it.trim().toLongOrNull() }.associateBy { it / 256 }
    val root = Xml.parse(descriptorList)
    return root.allNamed("Service").mapNotNull { node ->
        val id = node.getAttribute("Id").ifEmpty { return@mapNotNull null }
        val uri = node.getAttribute("SecureUri").ifEmpty { node.getAttribute("Uri") }
        if (uri.isEmpty()) return@mapNotNull null
        val auth = when (node.firstChildNamed("Policy")?.getAttribute("Auth")) {
            "Anonymous" -> Auth.ANONYMOUS
            "DeviceLink" -> Auth.DEVICE_LINK
            else -> Auth.APP_LINK
        }
        Service(
            id = id,
            name = node.getAttribute("Name").ifEmpty { id },
            uri = uri,
            auth = auth,
            manifestUri = node.firstChildNamed("Manifest")?.getAttribute("Uri")?.ifEmpty { null },
            serviceType = id.toLongOrNull()?.let { types[it] },
        )
    }
}

/**
 * The items in a `search` or `getMetadata` reply, and the total it claims. One parser for
 * both: the payload is the same shape, `mediaCollection` for something to descend into and
 * `mediaMetadata` for something to play, and services mix them freely in either call. Read in
 * document order so a mixed page keeps the service's own ordering.
 */
fun parseItems(body: String): ItemPage {
    val root = Xml.parse(body)
    val total = root.firstNamed("total")?.textContent?.trim()?.toIntOrNull() ?: 0
    val all = root.getElementsByTagName("*")
    val items = (0 until all.length).mapNotNull { all.item(it) as? Element }
        .mapNotNull { node ->
            val tag = node.tagName.substringAfter(':')
            if (tag != "mediaMetadata" && tag != "mediaCollection") return@mapNotNull null
            fun child(name: String) = node.firstChildNamed(name)?.textContent
            fun nested(parent: String, name: String) =
                node.firstChildNamed(parent)?.firstChildNamed(name)?.textContent
            val id = child("id") ?: return@mapNotNull null
            val itemType = child("itemType").orEmpty()
            // The element decides container-ness — except a collection whose declared type is
            // itself a playable leaf (Plex answers a tracks search that way, and the id plays).
            val container = tag == "mediaCollection" && itemType != "track" && itemType != "stream"
            Item(
                id = id,
                title = child("title").orEmpty(),
                itemType = itemType,
                // trackMetadata is where Deezer keeps artist/album/art, leaving the element bare.
                summary = child("summary") ?: child("artist") ?: nested("trackMetadata", "artist")
                    ?: child("genre") ?: child("country"),
                artUrl = child("albumArtURI") ?: nested("trackMetadata", "albumArtURI")
                    ?: nested("streamMetadata", "logo"),
                container = container,
            )
        }
    return ItemPage(items, total)
}

/**
 * The searchable categories of a service's presentation map.
 *
 * **Two element names, not one.** `<Category>` maps a canonical Sonos id to the service's own
 * (`id="artists" mappedId="SART"`), and `<CustomCategory>` is a shelf Sonos never standardised,
 * carrying only a `stringId` naming it and the `mappedId` to send. Scoped to a
 * `PresentationMap type="Search"` where there is one, so a `Category` meant for display is not
 * mistaken for a searchable one. A map may declare the same canonical id twice — a catalogue
 * block and the person's own library; the second takes its `mappedId` as its name, since the
 * canonical word is already spoken for, which is the same rule a custom category follows.
 */
fun parseSearchCategories(body: String): List<Category> {
    val root = Xml.parse(body)
    val within = root.allNamed("PresentationMap").firstOrNull { it.getAttribute("type") == "Search" } ?: root
    val names = mutableListOf<String>()
    val sent = mutableListOf<String>()
    val out = mutableListOf<Category>()
    val all = within.getElementsByTagName("*")
    val categories = (0 until all.length).mapNotNull { all.item(it) as? Element }
        .filter { it.tagName.substringAfter(':').let { t -> t == "Category" || t == "CustomCategory" } }
    for (node in categories) {
        val isCustom = node.tagName.substringAfter(':') == "CustomCategory"
        val id: String
        val mapped: String
        if (isCustom) {
            // Useless without both: no name to ask for, or nothing to send.
            id = node.getAttribute("stringId")
            mapped = node.getAttribute("mappedId")
            if (id.isEmpty() || mapped.isEmpty()) continue
        } else {
            id = node.getAttribute("id")
            if (id.isEmpty()) continue
            // mappedId is optional on a standard category: omitted, the canonical id is sent.
            mapped = node.getAttribute("mappedId").ifEmpty { id }
        }
        if (sent.any { it.equals(mapped, ignoreCase = true) }) continue
        val name = if (names.any { it.equals(id, ignoreCase = true) }) mapped else id
        names += name
        sent += mapped
        out += Category(id = name, mappedId = mapped)
    }
    return out
}

/** The `positionInformation` a `getMetadata` carries for a resumable thing, or `null` for none. */
fun parsePositionInformation(body: String): ResumePoint? {
    val node = Xml.parse(body).allNamed("positionInformation").firstOrNull() ?: return null
    val id = node.firstChildNamed("id")?.textContent ?: return null
    return ResumePoint(
        id = id,
        index = node.firstChildNamed("index")?.textContent?.trim()?.toIntOrNull() ?: 0,
        offsetMillis = node.firstChildNamed("offsetMillis")?.textContent?.trim()?.toLongOrNull() ?: 0,
    )
}

/** A page cut to the [count] asked for; [ItemPage.total] is left as the service reported it. */
private fun atMost(page: ItemPage, count: Int): ItemPage =
    if (page.items.size <= count) page else ItemPage(page.items.take(count), page.total)

/**
 * The `NowPlayingRatings` half of a presentation map. Pandora uses `NowPlayingRatings_v2`
 * rather than the plain name, matched by prefix the way openphonos does.
 */
fun parseRatingsMap(body: String): List<RatingsMatch> {
    val root = Xml.parse(body)
    return root.allNamed("PresentationMap")
        .filter { it.getAttribute("type").startsWith("NowPlayingRatings") }
        .flatMap { it.allNamed("Match") }
        .mapNotNull { match ->
            val propname = match.getAttribute("propname").ifEmpty { return@mapNotNull null }
            val value = match.getAttribute("value").ifEmpty { return@mapNotNull null }
            val ratings = match.allNamed("Rating").mapNotNull { r ->
                val id = r.getAttribute("Id").ifEmpty { return@mapNotNull null }
                Rating(id = id, stringId = r.getAttribute("StringId"))
            }
            RatingsMatch(propname, value, ratings)
        }
}

/** The `dynamic/property` `(name, value)` pairs out of a `getExtendedMetadata` response. */
fun parseDynamicProperties(body: String): List<Pair<String, String>> {
    val root = Xml.parse(body)
    return root.allNamed("property").mapNotNull { p ->
        val name = p.firstChildNamed("name")?.textContent
        val value = p.firstChildNamed("value")?.textContent
        if (name != null && value != null) name to value else null
    }
}

/** A `rateItem` response. Neither field is required — an error-free reply may carry neither. */
fun parseRateResult(body: String): RateResult {
    val root = Xml.parse(body)
    return RateResult(
        shouldSkip = root.firstNamed("shouldSkip")?.textContent?.toBooleanStrictOrNull(),
        messageStringId = root.firstNamed("messageStringId")?.textContent,
    )
}

/**
 * A SOAP fault's message, or `null` when this isn't one. Handles **both** SOAP 1.1
 * (`faultcode`/`faultstring`) and 1.2 (`Code/Value`, `Reason/Text`) — Sonos Radio answers in
 * 1.2, where a fault has neither `faultcode` nor `faultstring` at all.
 */
private fun faultMessage(root: Element): String? {
    if (root.allNamed("Fault").isEmpty()) return null
    val code = root.firstNamed("faultcode")?.textContent
        ?: root.allNamed("Code").flatMap { it.allNamed("Value") }.mapNotNull { it.textContent }
            .joinToString(" ").ifEmpty { null }
    val message = root.firstNamed("faultstring")?.textContent
        ?: root.firstNamed("Reason")?.firstChildNamed("Text")?.textContent
    return listOfNotNull(code, message).joinToString(": ").ifEmpty { "a fault with no message" }
}

/** Every descendant named [tag], ignoring whatever namespace prefix the server used for it. */
private fun Element.allNamed(tag: String): List<Element> {
    val all = getElementsByTagName("*")
    return (0 until all.length).mapNotNull { i -> (all.item(i) as? Element)?.takeIf { it.tagName.substringAfter(':') == tag } }
}

private fun Element.firstNamed(tag: String): Element? = allNamed(tag).firstOrNull()

/** A direct child named [tag] — unlike [allNamed], not every descendant. */
private fun Element.firstChildNamed(tag: String): Element? =
    (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }
        .firstOrNull { it.tagName.substringAfter(':') == tag }
