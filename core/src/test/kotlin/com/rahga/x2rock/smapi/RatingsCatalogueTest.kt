package com.rahga.x2rock.smapi

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** What [RatingsCatalogue] fetches and what it remembers, counted at the server. */
class RatingsCatalogueTest {

    private class MemoryStore : RatingsStore {
        val held = mutableMapOf<String, List<RatingsMatch>>()
        override fun loadRatings(serviceId: String) = held[serviceId]
        override fun saveRatings(serviceId: String, ratings: List<RatingsMatch>) { held[serviceId] = ratings }
    }

    private lateinit var server: MockWebServer
    private val store = MemoryStore()
    private lateinit var catalogue: RatingsCatalogue

    @Before fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/manifest/ratings" -> MockResponse().setBody("""{"presentationMap":{"uri":"${url("/map/ratings")}"}}""")
                    "/manifest/plain" -> MockResponse().setBody("""{"presentationMap":{"uri":"${url("/map/plain")}"}}""")
                    "/map/ratings" -> MockResponse().setBody(
                        """<Presentation><PresentationMap type="NowPlayingRatings">""" +
                            """<Match propname="unselected" value="0"><Ratings>""" +
                            """<Rating Id="555" StringId="THUMBS_UP_TIP"/><Rating Id="111" StringId="THUMBS_DOWN_TIP"/>""" +
                            """</Ratings></Match></PresentationMap></Presentation>"""
                    )
                    "/map/plain" -> MockResponse().setBody("""<Presentation><PresentationMap type="Search"/></Presentation>""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start()
        }
        catalogue = RatingsCatalogue(SmapiClient(OkHttpClient()), store)
    }

    @After fun tearDown() = server.shutdown()

    private fun service(id: String, manifest: String) =
        Service(id, "S$id", server.url("/smapi").toString(), Auth.ANONYMOUS, server.url(manifest).toString())

    @Test fun `a miss fetches the manifest and the map, and keeps what it found`() = runBlocking {
        val ratings = catalogue.ratingsFor(service("6", "/manifest/ratings"))
        assertEquals(listOf("unselected"), ratings.map { it.propname })
        assertEquals(2, server.requestCount)
        assertEquals(ratings, store.held["6"])
    }

    @Test fun `a hit asks nobody`() = runBlocking {
        val svc = service("6", "/manifest/ratings")
        val first = catalogue.ratingsFor(svc)
        val before = server.requestCount
        assertEquals(first, catalogue.ratingsFor(svc))
        assertEquals(before, server.requestCount)
    }

    /**
     * Most services publish no ratings, so "asked, and there are none" is the answer worth
     * keeping above all — mistaken for a miss, it would cost two fetches on every track.
     */
    @Test fun `a service that publishes none is remembered as none, not asked again`() = runBlocking {
        val svc = service("254", "/manifest/plain")
        assertTrue(catalogue.ratingsFor(svc).isEmpty())
        val before = server.requestCount
        assertTrue(catalogue.ratingsFor(svc).isEmpty())
        assertEquals(before, server.requestCount)
    }
}
