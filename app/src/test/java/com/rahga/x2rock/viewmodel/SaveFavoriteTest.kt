package com.rahga.x2rock.viewmodel

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.rahga.x2rock.lan.*
import com.rahga.x2rock.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Captured TIDAL SOAP; the captured metadata event is deliberately given that track for this scenario. */
class SaveFavoriteTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()
    private lateinit var fake: FakePlayer
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold
    private lateinit var viewModel: PlayerViewModel
    private lateinit var group: String
    private val requests = CopyOnWriteArrayList<Pair<String, String>>()
    private val track = Track("So What (feat. John Coltrane, Cannonball Adderley & Bill Evans)",
        TrackArtist("Miles Davis"), null, null, id = MusicObjectId("track/2178486", "174", "sn_27"),
        service = ServiceRef("TIDAL", "174"))
    @Volatile private var saved = false
    @Volatile private var refuse = false
    @Volatile private var hold: CountDownLatch? = null
    private val readStarted = CountDownLatch(1)

    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val action = request.getHeader("SOAPACTION").orEmpty().substringAfter('#').trim('"')
                    requests += action to request.body.readUtf8()
                    return when (action) {
                        "GetPositionInfo" -> {
                            readStarted.countDown()
                            hold?.await(5, TimeUnit.SECONDS)
                            MockResponse().setBody(FakePlayer.fixtureText("GetPositionInfo.tidal.xml"))
                        }
                        "Browse" -> MockResponse().setBody(FakePlayer.fixtureText("Browse.FV2.created-track.xml").let {
                            if (saved) it else it.replace(Regex("<Result>.*?</Result>", RegexOption.DOT_MATCHES_ALL), "<Result/>")
                        })
                        "ListAvailableServices" -> MockResponse().setBody(FakePlayer.fixtureText("ListAvailableServices.favorite.xml"))
                        "CreateObject" -> if (refuse) MockResponse().setResponseCode(500).setBody("refused") else {
                            saved = true
                            MockResponse().setBody(FakePlayer.fixtureText("CreateObject.favorite.xml"))
                        }
                        else -> MockResponse().setBody("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body/></s:Envelope>")
                    }
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        scope = CoroutineScope(SupervisorJob())
        household = fakeHousehold(fake, scope, upnpPort = server.port)
        viewModel = PlayerViewModel(household, RecordingNowPlaying(), testClock)
        runBlocking { household.connectTo(fake) }
        group = household.state.value.groups.first().id
        viewModel.selectGroup(group, "Test room")
        runBlocking { pushTrack(track); withTimeout(5_000) { viewModel.uiState.first { it.canSaveFavorite } } }
        requests.clear()
        fake.clearHistory()
    }

    @After fun tearDown() {
        hold?.countDown()
        household.disconnect()
        scope.cancel()
        server.shutdown()
        fake.shutdown()
    }

    private fun pushTrack(track: Track, radio: Boolean = false) {
        val event = JsonParser.parseString(FakePlayer.fixtureText("event.metadataStatus.json")).asJsonObject
        event.getAsJsonObject("currentItem").add("track", Gson().toJsonTree(track))
        event.getAsJsonObject("container").addProperty("type", if (radio) "station" else "album")
        fake.push("playbackMetadata:1", "metadataStatus", event.toString(), group)
    }
    private suspend fun notice(prefix: String) = withTimeout(5_000) {
        viewModel.uiState.first { it.notice?.startsWith(prefix) == true }.notice!!
    }

    @Test fun `save reports success then a duplicate without touching playback`() = runBlocking<Unit> {
        viewModel.saveFavorite()
        assertTrue(notice("Saved to Sonos Favorites:").contains(track.name!!))
        viewModel.saveFavorite()
        notice("Already in Sonos Favorites:")
        assertEquals(1, requests.count { it.first == "CreateObject" })
        val create = requests.single { it.first == "CreateObject" }.second
        assertTrue(create.contains("<ContainerID>FV:2</ContainerID>"))
        assertTrue(create.contains("00032020track%2f2178486"))
        assertTrue(create.contains("SA_RINCON44551_X_#Svc44551-0-Token"))
        // The connected household also makes background read-only SOAP requests.
        assertTrue(requests.toString(), requests.all {
            it.first.isEmpty() || it.first.startsWith("Get") || it.first.startsWith("List") ||
                it.first in setOf("Browse", "CreateObject")
        })
        for (command in listOf("play", "loadContent", "loadFavorite", "setVolume", "setPlayModes")) {
            assertEquals(command, 0, fake.commandsNamed(command))
        }
    }

    @Test fun `repeat presses while saving send one create`() = runBlocking<Unit> {
        hold = CountDownLatch(1)
        viewModel.saveFavorite()
        assertTrue(readStarted.await(5, TimeUnit.SECONDS))
        repeat(5) { viewModel.saveFavorite() }
        hold!!.countDown()
        notice("Saved to Sonos Favorites:")
        // Let any accidentally queued second save acquire the household lock and issue its read.
        delay(500)
        assertEquals(1, requests.count { it.first == "GetPositionInfo" })
        assertEquals(1, requests.count { it.first == "CreateObject" })
    }

    @Test fun `a changed track cancels the save before mutation`() = runBlocking<Unit> {
        hold = CountDownLatch(1)
        viewModel.saveFavorite()
        assertTrue(readStarted.await(5, TimeUnit.SECONDS))
        pushTrack(track.copy(name = "Another song", id = track.id!!.copy(objectId = "another")))
        withTimeout(5_000) { viewModel.uiState.first { it.trackName == "Another song" } }
        hold!!.countDown()
        withTimeout(5_000) { viewModel.uiState.first { it.notice?.contains("changed") == true } }
        assertEquals(0, requests.count { it.first == "CreateObject" })
    }

    @Test fun `radio and unidentified tracks offer no save and send nothing`() = runBlocking<Unit> {
        pushTrack(track, radio = true)
        withTimeout(5_000) { viewModel.uiState.first { it.isRadio } }
        assertFalse(viewModel.uiState.value.canSaveFavorite)
        viewModel.saveFavorite()
        pushTrack(track.copy(id = null))
        withTimeout(5_000) { viewModel.uiState.first { !it.isRadio } }
        assertFalse(viewModel.uiState.value.canSaveFavorite)
        viewModel.saveFavorite()
        assertEquals(0, requests.count { it.first == "CreateObject" })
    }

    @Test fun `refused save reports failure and is not retried`() = runBlocking<Unit> {
        refuse = true
        viewModel.saveFavorite()
        withTimeout(5_000) { viewModel.uiState.first { it.notice?.contains("500") == true } }
        assertFalse(saved)
        assertEquals(1, requests.count { it.first == "CreateObject" })
    }
}
