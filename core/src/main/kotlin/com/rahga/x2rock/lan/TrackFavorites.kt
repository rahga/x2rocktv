package com.rahga.x2rock.lan

import com.rahga.x2rock.model.MusicObjectId
import com.rahga.x2rock.model.Track
import com.rahga.x2rock.smapi.ServiceContent
import java.io.IOException
import java.io.StringWriter
import java.net.URLDecoder
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** A save's result names the track captured when the listener pressed, even if playback moves on. */
data class SavedTrackFavorite(val name: String, val alreadySaved: Boolean)

/** The playback URI and metadata come from GetPositionInfo, never from a guessed stream URL. */
internal data class TrackFavoriteSource(
    val uri: String,
    val metadata: String,
    val protocolInfo: String,
    val title: String,
    val artwork: String?,
)

internal fun trackFavoriteSource(position: String, expected: Track): TrackFavoriteSource {
    val reply = Xml.parse(position)
    val uri = reply.firstNamed("TrackURI")?.textContent.orEmpty()
    val metadata = reply.firstNamed("TrackMetaData")?.textContent.orEmpty()
    if (uri.isBlank() || metadata.isBlank() || metadata == "NOT_IMPLEMENTED") {
        throw IOException("this source has no track to save")
    }
    val item = Xml.parse(metadata).firstNamed("item") ?: throw IOException("this source has no track to save")
    if (item.firstChildNamed("class")?.textContent != "object.item.audioItem.musicTrack") {
        throw IOException("this source is not an individual song")
    }
    val title = item.firstChildNamed("title")?.textContent.orEmpty()
    val artist = item.firstChildNamed("creator")?.textContent
    if (title.isBlank() || title != expected.name ||
        (!artist.isNullOrBlank() && !expected.artist?.name.isNullOrBlank() && artist != expected.artist?.name)) {
        throw IOException("the track changed; try saving the song now shown")
    }
    return TrackFavoriteSource(
        uri, metadata, item.firstChildNamed("res")?.getAttribute("protocolInfo").orEmpty(), title,
        expected.imageUrl ?: expected.images.firstOrNull()?.url ?: item.firstChildNamed("albumArtURI")?.textContent,
    )
}

/**
 * loadContent can leave the current metadata with id=-1 and no cdudn. CreateObject accepts that,
 * but the phone hides the favorite. Supply the known catalogue id and advertised service type;
 * preserve every other field, including the resource and any existing account descriptor.
 * Verified with a TIDAL track on 2026-10-16, including visibility in the Sonos phone app.
 */
internal fun TrackFavoriteSource.withIdentity(id: MusicObjectId, serviceType: Long?): TrackFavoriteSource {
    val root = Xml.parse(metadata)
    val item = root.firstNamed("item") ?: throw IOException("this source has no track to save")
    var changed = false
    if (item.getAttribute("id").let { it.isBlank() || it == "-1" }) {
        if (!id.isReal) throw IOException("this track has no catalogue identity")
        item.setAttribute("id", "00032020${ServiceContent.encodeObjectId(id.objectId)}")
        changed = true
    }
    if (item.allNamed("desc").none { it.getAttribute("id") == "cdudn" }) {
        val descriptor = ServiceContent.cdudn(serviceType, "0")
            ?: throw IOException("the speaker did not identify this track's music service")
        item.appendChild(root.ownerDocument.createElement("desc").apply {
            setAttribute("id", "cdudn")
            setAttribute("nameSpace", "urn:schemas-rinconnetworks-com:metadata-1-0/")
            textContent = descriptor
        })
        changed = true
    }
    if (!changed) return this
    val out = StringWriter()
    TransformerFactory.newInstance().newTransformer().apply {
        setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
    }.transform(DOMSource(root), StreamResult(out))
    return copy(metadata = out.toString())
}

/** Existing instant-play entries and modern shortcuts are the same song when service and id match. */
internal fun containsTrackFavorite(didl: String, source: TrackFavoriteSource, id: MusicObjectId): Boolean {
    if (didl.isBlank()) return false
    return Xml.parse(didl).allNamed("item").any { item ->
        if (item.firstChildNamed("res")?.textContent == source.uri) return@any true
        val meta = item.firstChildNamed("resMD")?.textContent ?: return@any false
        val target = runCatching { Xml.parse(meta).firstNamed("item") }.getOrNull() ?: return@any false
        val descriptor = target.allNamed("desc").firstOrNull { it.getAttribute("id") == "cdudn" }?.textContent.orEmpty()
        val service = Regex("^SA_RINCON(\\d+)_").find(descriptor)?.groupValues?.get(1)?.toLongOrNull()?.div(256)?.toString()
        val objectId = target.getAttribute("id").replace(Regex("^[0-9a-fA-F]{8}"), "")
            .replace("__UNENCODED_SLASH__", "/")
        val decoded = runCatching { URLDecoder.decode(objectId.replace("+", "%2B"), "UTF-8") }.getOrDefault(objectId)
        service == id.serviceId && decoded == id.objectId
    }
}

internal fun TrackFavoriteSource.elements(serviceName: String?): String =
    """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/">""" +
        """<item id="-1" parentID="FV:2" restricted="true">""" +
        "<dc:title>${Xml.escape(title)}</dc:title><upnp:class>object.item.sonos-favorite</upnp:class>" +
        "<res protocolInfo=\"${Xml.escape(protocolInfo)}\">${Xml.escape(uri)}</res>" +
        artwork?.takeIf { it.isNotBlank() }?.let { "<upnp:albumArtURI>${Xml.escape(it)}</upnp:albumArtURI>" }.orEmpty() +
        "<r:type>instantPlay</r:type>" +
        serviceName?.let { "<r:description>${Xml.escape(it)}</r:description>" }.orEmpty() +
        "<r:resMD>${Xml.escape(metadata)}</r:resMD></item></DIDL-Lite>"
