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
    /** Where the manifest lives — the presentation map, and so the ratings rules, hang off it. */
    val manifestUri: String?,
)

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
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }

    /** One SMAPI call. A service that needs an account is refused here, not sent and rejected. */
    private fun call(service: Service, token: Token?, action: String, params: String): String {
        if (service.auth != Auth.ANONYMOUS && token == null) {
            throw SmapiException("${service.name} needs an account linked before it can be used")
        }
        val request = Request.Builder()
            .url(service.uri)
            .header("SOAPACTION", "\"$NS#$action\"")
            .post(envelope(action, params, token).toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) {
                throw SmapiException("${service.name} answered HTTP ${response.code} with an empty body")
            }
            val root = runCatching { Xml.parse(body) }.getOrNull()
            root?.let { faultMessage(it) }?.let { fault ->
                throw SmapiException("${service.name} refused $action: $fault")
            }
            if (!response.isSuccessful) {
                throw SmapiException("${service.name} $action failed: HTTP ${response.code}")
            }
            return body
        }
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

/** `ListAvailableServices`'s `AvailableServiceDescriptorList`, unescaped XML by the time it gets here. */
fun parseServices(descriptorList: String): List<Service> {
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
        )
    }
}

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
