package com.rahga.x2rock.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.rahga.x2rock.lan.Discovery
import com.rahga.x2rock.lan.FakePlayer
import com.rahga.x2rock.lan.LanHttp
import com.rahga.x2rock.lan.MulticastGate
import com.rahga.x2rock.lan.PlayerAddressBook
import com.rahga.x2rock.lan.SeedStore
import com.rahga.x2rock.lan.SonosHousehold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.CopyOnWriteArrayList

/** The queue screen's edits, against a UPnP fake serving a real ten-track queue. */
class QueueViewModelTest {

    private lateinit var fake: FakePlayer
    private lateinit var upnp: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold
    private lateinit var viewModel: QueueViewModel
    private val actions = CopyOnWriteArrayList<String>()

    private fun capture(name: String) =
        FakePlayer::class.java.getResourceAsStream("/fixtures/$name")!!.readBytes().decodeToString()

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        fake = FakePlayer().also { it.start() }
        upnp = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val action = request.getHeader("SOAPAction").orEmpty().substringAfter('#').trim('"')
                    val body = request.body.readUtf8()
                    actions += action
                    return when (action) {
                        "Browse" -> MockResponse().setBody(
                            capture(if ("<StartingIndex>0<" in body) "Browse.queue.page1.xml" else "Browse.queue.page2.xml")
                        )
                        "GetMediaInfo" -> MockResponse().setBody("<s:Envelope><s:Body><u:GetMediaInfoResponse><CurrentURI>x-rincon-queue:X#0</CurrentURI></u:GetMediaInfoResponse></s:Body></s:Envelope>")
                        "SaveQueue" -> MockResponse().setBody("<s:Envelope><s:Body><u:SaveQueueResponse><AssignedObjectID>SQ:11</AssignedObjectID></u:SaveQueueResponse></s:Body></s:Envelope>")
                        else -> MockResponse().setBody("<s:Envelope><s:Body/></s:Envelope>")
                    }
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        scope = CoroutineScope(SupervisorJob())
        val book = PlayerAddressBook()
        household = SonosHousehold(
            scope = scope, addressBook = book, multicast = MulticastGate.None,
            client = LanHttp.client(book), seeds = SeedStore.None, port = fake.port, upnpPort = upnp.port,
        )
        runBlocking {
            household.connect(Discovery.DiscoveredPlayer(fake.id, InetAddress.getByName("127.0.0.1"), fake.householdId))
            withTimeout(5_000) { household.state.first { it.connected } }
        }
        val groupId = household.state.value.groups.first { it.coordinatorId == fake.id }.id
        viewModel = QueueViewModel(household, SavedStateHandle(mapOf("groupId" to groupId)))
        runBlocking { withTimeout(5_000) { viewModel.uiState.first { it is QueueViewModel.UiState.Success } } }
        actions.clear()
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        fake.shutdown()
        upnp.shutdown()
        Dispatchers.resetMain()
    }

    /** A queue cannot be got back, so one press only arms it. */
    @Test fun `clear needs a second press`() = runBlocking<Unit> {
        viewModel.clearQueue()
        assertTrue(viewModel.clearArmed.value)
        delay(300)
        assertEquals(0, actions.count { it == "RemoveAllTracksFromQueue" })
        viewModel.clearQueue()
        withTimeout(5_000) { while ("RemoveAllTracksFromQueue" !in actions) delay(20) }
    }

    @Test fun `the first track has nowhere to move up to`() = runBlocking<Unit> {
        viewModel.moveUp(1)
        delay(300)
        assertEquals(0, actions.count { it == "ReorderTracksInQueue" })
        viewModel.moveDown(1)
        withTimeout(5_000) { while ("ReorderTracksInQueue" !in actions) delay(20) }
    }

    @Test fun `a saved queue says what it was saved as`() = runBlocking<Unit> {
        viewModel.saveAsPlaylist()
        val notice = withTimeout(5_000) { viewModel.notice.first { it != null } }!!
        assertTrue(notice, notice.startsWith("Saved as \""))
    }
}
