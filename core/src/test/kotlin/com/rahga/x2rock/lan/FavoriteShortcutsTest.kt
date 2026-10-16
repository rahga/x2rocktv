package com.rahga.x2rock.lan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress

/**
 * The favourites today's Sonos app saves as shortcuts (P-36). `Browse.FV2.shortcuts.xml` is the
 * home household's `Browse FV:2` after an album, an artist and a song from TIDAL, and a Sonos Radio
 * station, were saved from the Sonos app on a phone (2026-10-16): the three are shortcuts, the station
 * `instantPlay`. `getFavorites` listed the station and none of the three, and refused `loadFavorite`
 * of the song's id with `ERROR_INVALID_OBJECT_ID`.
 */
class FavoriteShortcutsTest {

    private lateinit var fake: FakePlayer
    private lateinit var upnp: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold

    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        upnp = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (soapAction(request) == "Browse") MockResponse().setBody(FakePlayer.fixtureText("Browse.FV2.shortcuts.xml"))
                    else MockResponse().setBody(EMPTY_SOAP)
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        scope = CoroutineScope(SupervisorJob())
        household = fakeHousehold(fake, scope, addressBook = PlayerAddressBook(), upnpPort = upnp.port)
        runBlocking { household.connectTo(fake, 10_000) }
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        upnp.shutdown()
        fake.shutdown()
    }

    private fun didl() = Regex("<Result>(.*)</Result>", RegexOption.DOT_MATCHES_ALL)
        .find(FakePlayer.fixtureText("Browse.FV2.shortcuts.xml"))!!.groupValues[1]
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    @Test fun `only the shortcuts are read, each as the service item it points at`() {
        val shortcuts = parseFavoriteShortcuts(didl())
        assertEquals(listOf("90", "92", "93"), shortcuts.map { it.id })
        val (album, artist, song) = shortcuts
        assertEquals("Kind Of Blue (Legacy Edition)", album.name)
        assertEquals("ALBUM", album.resource?.type)
        assertEquals("album/2178485", album.resource?.id?.objectId)
        assertEquals("ARTIST", artist.resource?.type)
        assertEquals("static/artist/55", artist.resource?.id?.objectId)
        assertEquals("TRACK", song.resource?.type)
        assertEquals("track/2178486", song.resource?.id?.objectId)
        shortcuts.forEach {
            assertEquals("174", it.resource?.id?.serviceId)
            assertEquals("TIDAL", it.service?.name)
            assertTrue(it.shortcut && it.playable)
            assertTrue(it.imageUrl!!.startsWith("https://resources.tidal.com/"))
        }
    }

    @Test fun `the household lists the shortcuts after what getFavorites lists`() = runBlocking<Unit> {
        val items = household.favorites().items
        val listed = items.filterNot { it.shortcut }
        assertTrue(listed.isNotEmpty())
        assertEquals(listOf("90", "92", "93"), items.filter { it.shortcut }.map { it.id })
        assertEquals(items.size, listed.size + 3)
    }

    @Test fun `a shortcut the Control API refuses plays as what it points at`() = runBlocking<Unit> {
        fake.refuse("loadFavorite", "ERROR_INVALID_OBJECT_ID")
        val groupId = fake.groupId(household)
        val call = scope.async(Dispatchers.IO) { runCatching { household.loadFavorite(groupId, "93") } }
        fake.awaitCommand("loadContent", 5_000)
        val body = fake.lastCommandBody("loadContent")!!
        assertEquals("track", body.get("type").asString)
        assertEquals("track/2178486", body.getAsJsonObject("id").get("objectId").asString)
        assertEquals("174", body.getAsJsonObject("id").get("serviceId").asString)
        call.cancel()
    }

    @Test fun `an artist shortcut is not played`() = runBlocking<Unit> {
        fake.refuse("loadFavorite", "ERROR_INVALID_OBJECT_ID")
        try {
            household.loadFavorite(fake.groupId(household), "92")
            fail("an artist was played")
        } catch (e: IOException) {
            assertTrue(e.message!!, "artist" in e.message!!)
        }
        assertNull(fake.lastCommandBody("loadContent"))
    }

    @Test fun `any other refusal stands`() = runBlocking<Unit> {
        fake.refuse("loadFavorite", "ERROR_INVALID_OBJECT_ID")
        try {
            household.loadFavorite(fake.groupId(household), "84")
            fail("a refusal was swallowed")
        } catch (e: SonosCommandException) {
            assertTrue(e.detail.startsWith("ERROR_INVALID_OBJECT_ID"))
        }
        assertNull(fake.lastCommandBody("loadContent"))
    }

    @Test fun `a shortcut refused for another reason is not played around`() = runBlocking<Unit> {
        fake.refuse("loadFavorite", "ERROR_COMMAND_FAILED")
        try {
            household.loadFavorite(fake.groupId(household), "93")
            fail("a refusal was swallowed")
        } catch (e: SonosCommandException) {
            assertTrue(e.detail.startsWith("ERROR_COMMAND_FAILED"))
        }
        assertNull(fake.lastCommandBody("loadContent"))
    }
}
