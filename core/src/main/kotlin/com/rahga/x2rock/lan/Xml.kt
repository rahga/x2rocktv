package com.rahga.x2rock.lan

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.IOException
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The XML a speaker or a music service answers with, parsed the one way.
 *
 * These are small replies from a LAN or a service's own server, but a malformed one must not
 * be able to reach out, so a DOCTYPE is refused before any parser sees it. Refused *here*, by
 * hand: Android's `DocumentBuilderFactory` accepts none of Xerces's features, and `setFeature`
 * for the one that forbids DOCTYPEs threw on every call on the Shield (Android 11) — so there
 * no UPnP reply parsed at all, no queue, no sleep timer, no alarm, and every fault read as a
 * bare HTTP 500. Seen 2026-10-03; the JVM, where the feature exists, never could show it. The
 * SMAPI client had its own copy of that parser, with the same fault, until the two became
 * this. The feature is still asked for where it is understood.
 */
internal object Xml {

    private val DOCTYPE = Regex("<!DOCTYPE", RegexOption.IGNORE_CASE)

    fun parse(xml: String): Element {
        if (DOCTYPE.containsMatchIn(xml)) throw IOException("refusing a reply that declares a DOCTYPE")
        return DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }.newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray()))
            .documentElement
    }

    /** Text for an element body or attribute value. */
    fun escape(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")
}

/** The tag without whatever namespace prefix the server chose for it. */
internal val Element.localTag: String get() = tagName.substringAfter(':')

/** Every element below this one, in document order. */
internal fun Element.descendants(): List<Element> {
    val all = getElementsByTagName("*")
    return (0 until all.length).mapNotNull { all.item(it) as? Element }
}

/** Every descendant named [tag], ignoring whatever namespace prefix the server used for it. */
internal fun Element.allNamed(tag: String): List<Element> = descendants().filter { it.localTag == tag }

internal fun Element.firstNamed(tag: String): Element? = allNamed(tag).firstOrNull()

/** A direct child named [tag] — unlike [allNamed], not every descendant. */
internal fun Element.firstChildNamed(tag: String): Element? =
    (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }
        .firstOrNull { it.localTag == tag }
