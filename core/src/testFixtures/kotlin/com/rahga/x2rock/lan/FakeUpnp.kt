package com.rahga.x2rock.lan

import okhttp3.mockwebserver.RecordedRequest

/** What a SOAP fake answers when the test has nothing to say: a well-formed, empty reply. */
const val EMPTY_SOAP = "<s:Envelope><s:Body/></s:Envelope>"

/** The action a SOAP request names, `"urn:...#Action"` in its header. */
fun soapAction(request: RecordedRequest): String =
    request.getHeader("SOAPAction").orEmpty().substringAfter('#').trim('"')

/** The simple elements of a SOAP body, by name: the arguments every UPnP action is given. */
fun soapFields(body: String): Map<String, String> =
    Regex("<(\\w+)>([^<]*)</\\1>").findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
