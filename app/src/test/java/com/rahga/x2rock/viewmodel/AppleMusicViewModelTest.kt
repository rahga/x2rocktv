package com.rahga.x2rock.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.rahga.x2rock.apple.AppleMusicItem
import com.rahga.x2rock.apple.ITunesSearch
import com.rahga.x2rock.lan.FakePlayer
import com.rahga.x2rock.lan.LanHttp
import com.rahga.x2rock.lan.MulticastGate
import com.rahga.x2rock.lan.PlayerAddressBook
import com.rahga.x2rock.lan.SeedStore
import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.lan.connectTo
import com.rahga.x2rock.lan.soapAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress

/**
 * Searching Apple Music and playing a result in the room. The search replies are Apple's own,
 * captured 2026-10-06; the household's Apple Music account comes from its captured history.
 */
class AppleMusicViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    private lateinit var fake: FakePlayer
    private lateinit var itunes: MockWebServer
    private lateinit var upnp: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold
    private lateinit var viewModel: AppleMusicViewModel
    private lateinit var groupId: String
    private val soapActions = java.util.concurrent.CopyOnWriteArrayList<String?>()

    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        itunes = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val entity = request.requestUrl?.queryParameter("entity")
                    return MockResponse().setBody(FakePlayer.fixtureText("itunes.search.${entity}s.json"))
                }
            }
            start()
        }
        upnp = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    soapActions += soapAction(request)
                    return MockResponse().setBody("<s:Envelope><s:Body><u:AddURIToQueueResponse/></s:Body></s:Envelope>")
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        scope = CoroutineScope(SupervisorJob())
        val book = PlayerAddressBook()
        household = SonosHousehold(
            scope = scope, addressBook = book, multicast = MulticastGate.None,
            client = LanHttp.client(book), seeds = SeedStore.None, port = fake.port, upnpPort = upnp.port,
            settleMillis = 2_000,
        )
        runBlocking { household.connectTo(fake) }
        groupId = fake.groupId(household)
        viewModel = AppleMusicViewModel(
            household,
            ITunesSearch(OkHttpClient(), itunes.url("/").toString().trimEnd('/')),
            SavedStateHandle(mapOf("groupId" to groupId)),
        )
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        fake.shutdown()
        itunes.shutdown()
        upnp.shutdown()
    }

    private suspend fun found(): List<AppleMusicItem> =
        (withTimeout(5_000) { viewModel.results.first { it is AppleMusicViewModel.Results.Found } }
            as AppleMusicViewModel.Results.Found).items

    @Test fun `a search finds songs, and switching to albums searches again`() = runBlocking<Unit> {
        viewModel.setQuery("khruangbin")
        viewModel.search()
        assertEquals("Texas Sun", found().first().title)
        viewModel.setKind(AppleMusicItem.Kind.ALBUM)
        withTimeout(5_000) {
            viewModel.results.first { (it as? AppleMusicViewModel.Results.Found)?.items?.firstOrNull()?.kind == AppleMusicItem.Kind.ALBUM }
        }
        assertEquals("Con Todo El Mundo", found().first().title)
    }

    @Test fun `nothing typed is not searched`() = runBlocking<Unit> {
        viewModel.setQuery("   ")
        viewModel.search()
        delay(300)
        assertEquals(AppleMusicViewModel.Results.Idle, viewModel.results.value)
        assertEquals(0, itunes.requestCount)
    }

    @Test fun `a result plays under the household's account, then goes back to the room`() = runBlocking<Unit> {
        viewModel.setQuery("khruangbin")
        viewModel.search()
        val song = found().first()
        var done = false
        viewModel.play(song) { done = true }
        fake.awaitCommand("loadContent", 5_000)
        val id = fake.lastCommandBody("loadContent")!!.getAsJsonObject("id")
        assertEquals("song:1485581309", id.get("objectId").asString)
        assertEquals("sn_19", id.get("accountId").asString)
        fake.pushPlaybackStatus(groupId, "PLAYBACK_STATE_PLAYING")
        withTimeout(10_000) { while (!done) delay(50) }
    }

    @Test fun `a hold adds the result to the queue and says so`() = runBlocking<Unit> {
        viewModel.setQuery("khruangbin")
        viewModel.search()
        val song = found().first()
        viewModel.queue(song)
        val said = withTimeout(5_000) { viewModel.notice.first { it != null } }
        assertEquals("Added \"Texas Sun\" to the queue", said)
        assertEquals(listOf<String?>("AddURIToQueue"), soapActions.toList())
    }

    /** No account known: said, and nothing sent that would play nothing. */
    @Test fun `with no account known it says why and sends nothing`() = runBlocking<Unit> {
        fake.refuse("getHistory", "ERROR_DISALLOWED_BY_POLICY")
        viewModel.setQuery("khruangbin")
        viewModel.search()
        val song = found().first()
        fake.clearHistory()
        viewModel.play(song) {}
        val said = withTimeout(5_000) { viewModel.notice.first { it != null } }
        assertEquals(NO_ACCOUNT, said)
        assertEquals(0, fake.commandsNamed("loadContent"))
        assertTrue(soapActions.isEmpty())
    }
}
