package com.rahga.x2rock.lan

import com.rahga.x2rock.model.QueueItem
import com.rahga.x2rock.model.QueueResponse
import com.rahga.x2rock.model.Track
import com.rahga.x2rock.model.TrackAlbum
import com.rahga.x2rock.model.TrackArtist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The queue, which the Control API does not have.
 *
 * `queue:1`, `playbackQueue:1` and `cloudQueue:1` all answer
 * `ERROR_UNSUPPORTED_NAMESPACE` — cloud or LAN, the Control API has no view of the queue
 * the official apps populate. UPnP does, over plain HTTP on port 1400.
 *
 * **This is the one cleartext path in the app.** Android blocks cleartext by default since
 * API 28, and the exemption is scoped in `network_security_config.xml` to `.local` names
 * only — never to raw IPs, never to the internet — which works because a player's name is
 * derivable from its id. So every call here goes to a `sonos-<MAC>.local` hostname, and
 * passing a bare address will (correctly) be refused by the platform.
 *
 * UPnP *eventing* (GENA) is deliberately not used: it needs the player to open a
 * connection back to us. This is request/response only, so the queue is the one part of
 * the UI that still has to be asked rather than pushed.
 */
class Upnp(client: OkHttpClient) {

    /**
     * The shared client with a read timeout put back.
     *
     * It is built for the WebSocket, where `readTimeout(0)` is right because a subscription
     * is meant to sit idle. Request/response is the opposite case: a player that goes quiet
     * mid-answer would hang the caller forever. That is not hypothetical here — switching a
     * soundbar's group to its HDMI input stalls AVTransport for about twelve seconds, and
     * the queue calls share the same fate whenever a player is busy. Derived rather than
     * separate, so the connection pool and dispatcher stay shared.
     */
    private val client = client.newBuilder()
        .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
        .build()

    suspend fun browseQueue(hostname: String, start: Int = 0, count: Int = MAX_ITEMS): QueueResponse =
        withContext(Dispatchers.IO) {
            val response = soap(
                hostname, Service.CONTENT_DIRECTORY, "Browse",
                listOf(
                    "ObjectID" to "Q:0",
                    "BrowseFlag" to "BrowseDirectChildren",
                    "Filter" to "*",
                    "StartingIndex" to start.toString(),
                    "RequestedCount" to count.toString(),
                    "SortCriteria" to "",
                ),
            )
            val envelope = parse(response)
            val total = envelope.text("TotalMatches")?.toIntOrNull() ?: 0
            // The DIDL document arrives as escaped text inside <Result>, so one layer of
            // unescaping has already happened and what is left is XML to parse again.
            val didl = envelope.text("Result").orEmpty()
            QueueResponse(items = parseDidl(didl, hostname, start), totalItems = total)
        }

    /** `Q:0/<n>` is one-based, matching the track numbers the UI shows. */
    suspend fun removeFromQueue(hostname: String, trackNumber: Int): Unit = withContext(Dispatchers.IO) {
        val updateId = currentUpdateId(hostname)
        soap(
            hostname, Service.AV_TRANSPORT, "RemoveTrackFromQueue",
            listOf("InstanceID" to "0", "ObjectID" to "Q:0/$trackNumber", "UpdateID" to updateId),
        )
    }

    suspend fun skipToQueueItem(hostname: String, trackNumber: Int): Unit = withContext(Dispatchers.IO) {
        soap(
            hostname, Service.AV_TRANSPORT, "Seek",
            listOf("InstanceID" to "0", "Unit" to "TRACK_NR", "Target" to trackNumber.toString()),
        )
    }

    /**
     * Switch a soundbar's group to its HDMI input.
     *
     * The URI names the *soundbar*, but the call goes to the group's **coordinator**, as
     * every AVTransport call does. The two differ whenever a soundbar has joined someone
     * else's group, and that case does not answer: taking the TV hands coordination to the
     * soundbar, and the old coordinator stops coordinating before it replies. Measured on
     * the sibling project, both players go silent for about twelve seconds and the switch
     * lands at roughly fourteen.
     *
     * Addressing the soundbar directly answers at once but means something else entirely —
     * the soundbar leaves the group and takes the TV alone, rather than bringing the room
     * with it. So the request stands as it is, and the caller decides what a lost answer
     * means. [SonosHousehold.useTvInput] does: it waits for the pushed `htInputFormat`
     * instead of the reply, which the sibling project could not do.
     */
    suspend fun useTvInput(hostname: String, soundbarId: String): Unit = withContext(Dispatchers.IO) {
        soap(
            hostname, Service.AV_TRANSPORT, "SetAVTransportURI",
            listOf(
                "InstanceID" to "0",
                "CurrentURI" to tvStreamUri(soundbarId),
                "CurrentURIMetaData" to "",
            ),
        )
    }

    /**
     * One of the extended-EQ toggles — `NightMode` and `DialogLevel`, which the Sonos app
     * calls Night Sound and Speech Enhancement.
     *
     * The Control API *reads* these, in `settings:1 getPlayerSettings`, but refuses to write
     * them: `ERROR_NO_PERMISSION`. So this is the only door to them and it is UPnP, which
     * makes these the second thing here reached over UPnP, alongside the queue — and, like
     * the queue, asked for rather than pushed. Verified on a Beam: `settings:1` accepts a
     * subscription and then sends nothing at all for a `SetEQ` write, while a re-read sees
     * it at once. So the caller re-reads; there is no event coming.
     *
     * Boolean on the hardware, despite `DialogLevel` naming a level: the level is readable
     * but only 0 and 1 are writable here. Addressed to the *soundbar itself*, never a
     * coordinator, because it is a property of that one speaker. A player with no HDMI
     * socket answers UPnP 402, so callers gate on the TV-input capability first.
     */
    suspend fun setEq(hostname: String, eqType: String, on: Boolean): Unit = withContext(Dispatchers.IO) {
        soap(
            hostname, Service.RENDERING_CONTROL, "SetEQ",
            listOf(
                "InstanceID" to "0",
                "EQType" to eqType,
                "DesiredValue" to if (on) "1" else "0",
            ),
        )
    }

    /**
     * A removal is rejected with UPnP 1028 if the queue moved since the version we quote,
     * which is exactly what should happen when someone else is editing it — so the id is
     * read immediately before use rather than cached.
     */
    private suspend fun currentUpdateId(hostname: String): String {
        val response = soap(
            hostname, Service.CONTENT_DIRECTORY, "Browse",
            listOf(
                "ObjectID" to "Q:0", "BrowseFlag" to "BrowseDirectChildren", "Filter" to "*",
                "StartingIndex" to "0", "RequestedCount" to "1", "SortCriteria" to "",
            ),
        )
        return parse(response).text("UpdateID") ?: "0"
    }

    // ---------------------------------------------------------------- SOAP

    private enum class Service(val path: String, val urn: String) {
        AV_TRANSPORT("/MediaRenderer/AVTransport/Control", "urn:schemas-upnp-org:service:AVTransport:1"),
        CONTENT_DIRECTORY("/MediaServer/ContentDirectory/Control", "urn:schemas-upnp-org:service:ContentDirectory:1"),
        RENDERING_CONTROL("/MediaRenderer/RenderingControl/Control", "urn:schemas-upnp-org:service:RenderingControl:1"),
    }

    private fun soap(hostname: String, service: Service, action: String, args: List<Pair<String, String>>): String {
        require(PlayerNames.isLocalName(hostname)) {
            "UPnP must be addressed by a .local name: cleartext is only permitted for those"
        }
        val params = args.joinToString("") { (name, value) -> "<$name>${escape(value)}</$name>" }
        val envelope = """<?xml version="1.0"?>""" +
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"""" +
            """ s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""" +
            """<u:$action xmlns:u="${service.urn}">$params</u:$action>""" +
            """</s:Body></s:Envelope>"""

        val request = Request.Builder()
            .url("http://$hostname:$PORT${service.path}")
            .header("SOAPAction", "\"${service.urn}#$action\"")
            .post(envelope.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.isSuccessful) return body
            if (response.code == 403) {
                throw IOException(
                    "$hostname refused UPnP (403). Enable it in the Sonos app: " +
                        "Settings > Privacy & Security > UPnP"
                )
            }
            throw IOException("$action failed: HTTP ${response.code} ${describe(body)}")
        }
    }

    /** UPnP's own error code, when the fault body carries one. */
    private fun describe(body: String): String =
        runCatching { parse(body).text("errorCode") }.getOrNull()
            ?.let { code -> "(UPnP $code${UPNP_ERRORS[code]?.let { ": $it" } ?: ""})" }
            ?: ""

    // ---------------------------------------------------------------- XML

    private fun parse(xml: String): Element =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // These are LAN responses, but a malformed one should not be able to reach out.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isExpandEntityReferences = false
        }.newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray()))
            .documentElement

    /** First element with this tag name, anywhere; the envelopes here are small and flat. */
    private fun Element.text(tag: String): String? =
        getElementsByTagName(tag).takeIf { it.length > 0 }?.item(0)?.textContent

    private fun parseDidl(didl: String, hostname: String, start: Int): List<QueueItem> {
        if (didl.isBlank()) return emptyList()
        val root = runCatching { parse(didl) }.getOrNull() ?: return emptyList()
        val items = root.getElementsByTagName("item")
        return (0 until items.length).map { i ->
            val item = items.item(i) as Element
            QueueItem(
                // Sonos gives each item an id of the form Q:0/<n>; fall back to position.
                id = item.getAttribute("id").ifBlank { "Q:0/${start + i + 1}" },
                track = Track(
                    name = item.child("dc:title"),
                    artist = item.child("dc:creator")?.let { TrackArtist(it) },
                    album = item.child("upnp:album")?.let { TrackAlbum(it) },
                    imageUrl = item.child("upnp:albumArtURI")?.let { absolute(it, hostname) },
                    durationMillis = 0,
                ),
            )
        }
    }

    private fun Element.child(tag: String): String? =
        (0 until childNodes.length)
            .map { childNodes.item(it) }
            .firstOrNull { it.nodeType == Node.ELEMENT_NODE && it.nodeName == tag }
            ?.textContent
            ?.takeIf { it.isNotBlank() }

    /**
     * Art URLs come back as paths on the player that answered, so they are resolved
     * against its `.local` name rather than its address — for the same reason every other
     * call here is.
     *
     * **This makes them cleartext `.local` URLs, which most HTTP clients cannot fetch.**
     * Whatever loads these images (Coil, by default, builds its own `OkHttpClient`) has to
     * be given a client from [LanHttp], or it will have neither the address book to
     * resolve the name nor the platform's blessing to fetch it in the clear.
     */
    private fun absolute(uri: String, hostname: String): String =
        if (uri.startsWith("http")) uri else "http://$hostname:$PORT$uri"

    private fun escape(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")

    companion object {
        /**
         * A soundbar's own HDMI stream, which is a URI rather than a mode: `spdif` is what
         * Sonos calls the input on every model, optical and ARC alike.
         */
        fun tvStreamUri(soundbarId: String) = "x-sonos-htastream:$soundbarId:spdif"

        /** Whether a transport URI is some soundbar's TV input. */
        fun isTvStream(uri: String) = uri.startsWith("x-sonos-htastream:")

        private const val READ_TIMEOUT = 10L

        const val PORT = 1400

        /** Queues run to tens of thousands of tracks; listing stops here and says so. */
        const val MAX_ITEMS = 1000

        private val UPNP_ERRORS = mapOf(
            "701" to "no media loaded, or not available in this state",
            "711" to "no such track in the queue",
            "402" to "invalid arguments",
            "1028" to "the queue changed while this was in flight; try again",
        )
    }
}
