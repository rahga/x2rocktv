package com.rahga.x2rock.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.rahga.x2rock.lan.Discovery
import com.rahga.x2rock.lan.FakePlayer
import com.rahga.x2rock.lan.LanHttp
import com.rahga.x2rock.lan.MulticastGate
import com.rahga.x2rock.lan.PlayerAddressBook
import com.rahga.x2rock.lan.SeedStore
import com.rahga.x2rock.lan.SonosHousehold
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain and resetMain
class QueueViewModelTest {

    private lateinit var fake: FakePlayer
    private lateinit var upnp: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold
    private lateinit var viewModel: QueueViewModel
    private val actions = CopyOnWriteArrayList<String>()
    /** Every Browse's RequestedCount: "1" is the version check, anything else a full read. */
    private val browses = CopyOnWriteArrayList<String>()
    /** What the fake reports as the queue's UpdateID; the capture says 58. */
    @Volatile private var updateId = "58"

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
                        "Browse" -> {
                            browses += Regex("<RequestedCount>(\\d+)<").find(body)!!.groupValues[1]
                            MockResponse().setBody(
                                capture(if ("<StartingIndex>0<" in body) "Browse.queue.page1.xml" else "Browse.queue.page2.xml")
                                    .replace("<UpdateID>58</UpdateID>", "<UpdateID>$updateId</UpdateID>")
                            )
                        }
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
        browses.clear()
    }

    private val groupId get() = household.state.value.groups.first { it.coordinatorId == fake.id }.id
    private fun fullReads() = browses.count { it != "1" }

    // ---------------------------------------------------------------- freshness

    /** A playback event at the version already shown costs nothing: no browse at all. */
    @Test fun `a playback event at the same queue version reads nothing`() = runBlocking<Unit> {
        fake.pushFixture("playbackStatus", groupId)
        withTimeout(5_000) { household.groupStates.first { it[groupId]?.queueVersion == "8" } }
        delay(300)
        browses.clear()
        fake.pushFixture("playbackStatus", groupId)
        delay(500)
        assertEquals(0, browses.size)
    }

    /**
     * Opened before the room had said which version its queue is at — early in a session, or
     * on a room whose status has not arrived. The list on screen was read at no known version,
     * so the first one to arrive must read it again: it may carry an edit the list lacks.
     */
    @Test fun `the first queue version after an unknown one reads the queue again`() = runBlocking<Unit> {
        assertEquals(null, household.groupStates.value[groupId]?.queueVersion)
        fake.pushFixture("playbackStatus", groupId)
        withTimeout(5_000) { while (fullReads() == 0) delay(20) }
    }

    /** Opened at a known version: that version arriving again is no change. */
    @Test fun `a queue opened at a known version does not read it again for that version`() = runBlocking<Unit> {
        fake.pushFixture("playbackStatus", groupId)
        withTimeout(5_000) { household.groupStates.first { it[groupId]?.queueVersion == "8" } }
        delay(300)
        // What reading the queue once costs, so the count below is opening and nothing more.
        browses.clear()
        viewModel.reload()
        delay(500)
        val oneRead = fullReads()

        browses.clear()
        val reopened = QueueViewModel(household, SavedStateHandle(mapOf("groupId" to groupId)))
        withTimeout(5_000) { reopened.uiState.first { it is QueueViewModel.UiState.Success } }
        fake.pushFixture("playbackStatus", groupId)
        delay(500)
        assertEquals("opening read the queue more than once", oneRead, fullReads())
    }

    @Test fun `a new queue version reads the queue again`() = runBlocking<Unit> {
        fake.pushFixture("playbackStatus", groupId)
        withTimeout(5_000) { household.groupStates.first { it[groupId]?.queueVersion == "8" } }
        delay(300)
        val before = fullReads()
        val body = FakePlayer.fixture("event.playbackStatus.json").apply { addProperty("queueVersion", "9") }
        fake.push("playback:1", "playbackStatus", body.toString(), groupId)
        withTimeout(5_000) { while (fullReads() == before) delay(20) }
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
