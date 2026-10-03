package com.rahga.x2rock.radio

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The directory's reply is `radiobrowser.stations.jazz.json`, three rows fetched 2026-10-02. */
class RadioDirectoryTest {

    private lateinit var server: MockWebServer
    private lateinit var directory: RadioDirectory
    private val capture = javaClass.getResourceAsStream("/fixtures/radiobrowser.stations.jazz.json")!!.readBytes().decodeToString()

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        directory = RadioDirectory(OkHttpClient(), base = server.url("/").toString().trimEnd('/'))
    }

    @After fun tearDown() = server.shutdown()

    @Test fun `stations are asked for most-voted and working, as the client says who it is`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(capture))
        directory.stations(tag = "jazz")
        val request = server.takeRequest()
        val query = request.requestUrl!!
        assertEquals("/json/stations/search", query.encodedPath)
        assertEquals("jazz", query.queryParameter("tag"))
        assertEquals("true", query.queryParameter("hidebroken"))
        assertEquals("votes", query.queryParameter("order"))
        assertEquals("true", query.queryParameter("reverse"))
        assertNull(query.queryParameter("countrycode"))
        assertEquals(RadioDirectory.USER_AGENT, request.getHeader("User-Agent"))
    }

    @Test fun `a station plays its resolved URL, never its registered one`() = runBlocking<Unit> {
        // The capture's first row has the two equal; make them differ, as a .pls row does.
        val rows = JsonParser.parseString(capture).asJsonArray
        rows[0].asJsonObject.addProperty("url", "https://example.org/station.pls")
        server.enqueue(MockResponse().setBody(rows.toString()))
        val first = directory.stations().first()
        assertEquals(rows[0].asJsonObject.get("url_resolved").asString, first.url)
        assertEquals("Classic Vinyl HD", first.name)
        assertEquals("MP3 320k", first.format)
    }

    /** With nothing to play, a row is no station. */
    @Test fun `a row with no resolved URL is dropped`() = runBlocking<Unit> {
        val rows = JsonParser.parseString(capture).asJsonArray
        rows[1].asJsonObject.addProperty("url_resolved", "")
        server.enqueue(MockResponse().setBody(rows.toString()))
        assertEquals(2, directory.stations().size)
    }
}
