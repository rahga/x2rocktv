package com.rahga.x2rock.lan

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest

/** A well-formed, empty reply, for an action a test expects to succeed and has nothing to say about. */
const val EMPTY_SOAP = "<s:Envelope><s:Body/></s:Envelope>"

/**
 * What a player answers an action it has not got: UPnP's 401, Invalid Action, in the shape of the
 * captured `DestroyAlarm` fault. Every test dispatcher's default, so an action a test did not expect
 * fails, as on a speaker; it used to be [EMPTY_SOAP], and a wrong or extra action succeeded (P-67).
 */
fun unknownAction(): MockResponse = MockResponse().setResponseCode(500).setBody(
    FakePlayer.fixtureText("DestroyAlarm.gone.fault800.xml").replace("<errorCode>800</errorCode>", "<errorCode>401</errorCode>")
)

/** The action a SOAP request names, `"urn:...#Action"` in its header. */
fun soapAction(request: RecordedRequest): String =
    request.getHeader("SOAPAction").orEmpty().substringAfter('#').trim('"')

/** The simple elements of a SOAP body, by name: the arguments every UPnP action is given. */
fun soapFields(body: String): Map<String, String> =
    Regex("<(\\w+)>([^<]*)</\\1>").findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
