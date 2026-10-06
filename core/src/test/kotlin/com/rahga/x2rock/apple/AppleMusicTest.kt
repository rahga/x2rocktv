package com.rahga.x2rock.apple

import com.rahga.x2rock.lan.FakePlayer
import com.rahga.x2rock.lan.LanHttp
import com.rahga.x2rock.lan.MulticastGate
import com.rahga.x2rock.lan.PlayerAddressBook
import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.lan.connectTo
import com.rahga.x2rock.lan.soapAction
import com.rahga.x2rock.lan.soapFields
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.net.InetAddress

/**
 * Apple Music, searched through iTunes and played by the household. The search replies are
 * Apple's own, captured 2026-10-06 ("khruangbin", US storefront); the URIs are the ones the home
 * household's player wrote and accepted on Dining Room that night.
 */
class AppleMusicTest {

    private lateinit var itunes: MockWebServer
    private lateinit var fake: FakePlayer
    private lateinit var upnp: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold
    /** Each SOAP call to the player, by action, with its fields. */
    private val soap = java.util.concurrent.CopyOnWriteArrayList<Pair<String?, Map<String, String>>>()

    @Before fun setUp() {
        itunes = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val entity = request.requestUrl?.queryParameter("entity")
                    return MockResponse().setBody(FakePlayer.fixtureText("itunes.search.${entity}s.json"))
                }
            }
            start()
        }
        fake = FakePlayer().also { it.start() }
        upnp = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    // Unescaped as the player reads them: in the envelope a URI's & is &amp;.
                    soap += soapAction(request) to soapFields(request.body.readUtf8()).mapValues { (_, v) ->
                        v.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
                    }
                    return MockResponse().setBody("<s:Envelope><s:Body><u:AddURIToQueueResponse/></s:Body></s:Envelope>")
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        scope = CoroutineScope(SupervisorJob())
        val book = PlayerAddressBook()
        household = SonosHousehold(
            scope = scope, addressBook = book, multicast = MulticastGate.None,
            client = LanHttp.client(book), port = fake.port, upnpPort = upnp.port, settleMillis = 2_000,
        )
        runBlocking { household.connectTo(fake) }
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        fake.shutdown()
        upnp.shutdown()
        itunes.shutdown()
    }

    private fun search() = ITunesSearch(OkHttpClient(), itunes.url("/").toString().trimEnd('/'))

    private val song = AppleMusicItem(AppleMusicItem.Kind.SONG, 1299241643, "Cómo Me Quieres", "Khruangbin", null)
    private val album = AppleMusicItem(AppleMusicItem.Kind.ALBUM, 1485581308, "Texas Sun - EP", "Khruangbin & Leon Bridges", null)

    @Test fun `songs are read from Apple's reply, with their catalogue ids`() = runBlocking<Unit> {
        val songs = search().search("khruangbin", AppleMusicItem.Kind.SONG, country = "US")
        assertEquals(3, songs.size)
        assertEquals(AppleMusicItem.Kind.SONG, songs.first().kind)
        assertEquals(1485581309L, songs.first().catalogId)
        assertEquals("Texas Sun", songs.first().title)
        assertEquals("Khruangbin & Leon Bridges", songs.first().artist)
        assertTrue("art not asked for at 300px: ${songs.first().artworkUrl}", songs.first().artworkUrl!!.contains("300x300bb"))
        val asked = itunes.takeRequest().requestUrl!!
        assertEquals("song", asked.queryParameter("entity"))
        assertEquals("US", asked.queryParameter("country"))
        assertEquals("khruangbin", asked.queryParameter("term"))
    }

    @Test fun `albums are read by their collection id`() = runBlocking<Unit> {
        val albums = search().search("khruangbin", AppleMusicItem.Kind.ALBUM, country = "US")
        assertEquals(listOf(1299241642L, 1845322228L, 1721004325L), albums.map { it.catalogId })
        assertEquals("Con Todo El Mundo", albums.first().title)
        assertEquals("album:1299241642", albums.first().objectId)
    }

    /** Character for character what the player wrote, and what it accepted for an album. */
    @Test fun `queue URIs are the player's own`() {
        assertEquals("x-sonos-http:song%3a1299241643.mp4?sid=204&flags=8232&sn=28", AppleMusic.queueUri(song, "sn_28"))
        assertEquals(
            "x-rincon-cpcontainer:1004206calbum%3a1485581308?sid=204&flags=8300&sn=28",
            AppleMusic.queueUri(album, "sn_28"),
        )
    }

    @Test fun `queue metadata names the account and escapes the title`() {
        val didl = AppleMusic.queueMetadata(album.copy(title = "Rock & Roll <Live>"))
        assertTrue(didl.contains("""<item id="1004206calbum%3a1485581308" parentID="0""""))
        assertTrue(didl.contains("<upnp:class>object.container.album.musicAlbum</upnp:class>"))
        assertTrue(didl.contains("<dc:title>Rock &amp; Roll &lt;Live&gt;</dc:title>"))
        assertTrue(didl.contains(">SA_RINCON52231_X_#Svc52231-0-Token</desc>"))
        assertTrue(AppleMusic.queueMetadata(song).contains("""<item id="00032020song%3a1299241643" parentID="-1""""))
    }

    /** The captured history holds one Apple Music album, under the household's account sn_19. */
    @Test fun `the household's Apple Music account comes from what it has played`() = runBlocking<Unit> {
        assertEquals("sn_19", household.appleMusicAccount())
    }

    @Test fun `playing a result loads it by its catalogue id, under the household's account`() = runBlocking<Unit> {
        val groupId = fake.groupId(household)
        val call = scope.async(Dispatchers.IO) { household.playAppleMusic(groupId, song, "sn_19") }
        fake.awaitCommand("loadContent", 5_000)
        val body = fake.lastCommandBody("loadContent")!!
        assertEquals("track", body.get("type").asString)
        val id = body.getAsJsonObject("id")
        assertEquals("song:1299241643", id.get("objectId").asString)
        assertEquals("204", id.get("serviceId").asString)
        assertEquals("sn_19", id.get("accountId").asString)
        fake.pushPlaybackStatus(groupId, "PLAYBACK_STATE_PLAYING")
        withTimeout(10_000) { call.await() }
    }

    @Test fun `adding a result to the queue sends the URI and metadata, and plays nothing`() = runBlocking<Unit> {
        val groupId = fake.groupId(household)
        fake.clearHistory()
        household.queueAppleMusic(groupId, album, "sn_19")
        val (action, fields) = soap.single()
        assertEquals("AddURIToQueue", action)
        assertEquals("x-rincon-cpcontainer:1004206calbum%3a1485581308?sid=204&flags=8300&sn=19", fields["EnqueuedURI"])
        assertTrue(fields["EnqueuedURIMetaData"]!!.contains("SA_RINCON52231_X_#Svc52231-0-Token"))
        assertEquals("0", fields["EnqueueAsNext"])
        assertEquals(0, fake.commandsNamed("play"))
    }
}
