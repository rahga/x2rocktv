package com.rahga.x2rock.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.rahga.x2rock.lan.EMPTY_SOAP
import com.rahga.x2rock.lan.FakePlayer
import com.rahga.x2rock.lan.connectTo
import com.rahga.x2rock.lan.soapAction
import com.rahga.x2rock.lan.soapFields
import com.rahga.x2rock.lan.LanHttp
import com.rahga.x2rock.lan.MulticastGate
import com.rahga.x2rock.lan.PlayerAddressBook
import com.rahga.x2rock.lan.SeedStore
import com.rahga.x2rock.lan.SonosHousehold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.CopyOnWriteArrayList

/** The queue screen's edits, against a UPnP fake serving a real ten-track queue. */
class QueueViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()


    private lateinit var fake: FakePlayer
    private lateinit var upnp: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold
    private lateinit var viewModel: QueueViewModel
    private val actions = CopyOnWriteArrayList<String>()
    /** Every Browse's RequestedCount: "1" is the version check, anything else a full read. */
    private val browses = CopyOnWriteArrayList<String>()
    /** One per read of the whole queue, which is two Browse pages of this capture. */
    private val loads = CopyOnWriteArrayList<Unit>()
    /** What the fake reports as the queue's UpdateID; the capture says 58. */
    @Volatile private var updateId = "58"
    /** The UpdateID each edit quoted. */
    private val editVersions = CopyOnWriteArrayList<String>()
    /** Refuse every edit as made against a queue that has changed: UPnP 1028. */
    @Volatile private var refuseEdits = false
    /** Serve slot 1 as a different track, as if an edit elsewhere had moved one into it. */
    @Volatile private var slotOneReplaced = false


    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        upnp = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val action = soapAction(request)
                    val body = request.body.readUtf8()
                    actions += action
                    if (action == "ReorderTracksInQueue" || action == "RemoveTrackFromQueue") {
                        editVersions += soapFields(body)["UpdateID"].orEmpty()
                        if (refuseEdits) return MockResponse().setResponseCode(500).setBody(
                            "<s:Envelope><s:Body><s:Fault><detail><UPnPError><errorCode>1028</errorCode></UPnPError></detail></s:Fault></s:Body></s:Envelope>"
                        )
                    }
                    return when (action) {
                        "Browse" -> {
                            browses += Regex("<RequestedCount>(\\d+)<").find(body)!!.groupValues[1]
                            if ("<StartingIndex>0<" in body) loads += Unit
                            MockResponse().setBody(
                                FakePlayer.fixtureText(if ("<StartingIndex>0<" in body) "Browse.queue.page1.xml" else "Browse.queue.page2.xml")
                                    .replace("<UpdateID>58</UpdateID>", "<UpdateID>$updateId</UpdateID>")
                                    .let { if (slotOneReplaced) it.replaceFirst("Cómo Me Quieres", "Somebody Else") else it }
                            )
                        }
                        "GetMediaInfo" -> MockResponse().setBody("<s:Envelope><s:Body><u:GetMediaInfoResponse><CurrentURI>x-rincon-queue:X#0</CurrentURI></u:GetMediaInfoResponse></s:Body></s:Envelope>")
                        "SaveQueue" -> MockResponse().setBody("<s:Envelope><s:Body><u:SaveQueueResponse><AssignedObjectID>SQ:11</AssignedObjectID></u:SaveQueueResponse></s:Body></s:Envelope>")
                        else -> MockResponse().setBody(EMPTY_SOAP)
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
            household.connectTo(fake)
        }
        val groupId = fake.groupId(household)
        viewModel = QueueViewModel(household, SavedStateHandle(mapOf("groupId" to groupId)))
        runBlocking { withTimeout(5_000) { viewModel.uiState.first { it is QueueViewModel.UiState.Success } } }
        actions.clear()
        browses.clear()
    }

    private val groupId get() = fake.groupId(household)
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
        fake.pushPlaybackStatus(groupId, queueVersion = "9")
        withTimeout(5_000) { while (fullReads() == before) delay(20) }
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        fake.shutdown()
        upnp.shutdown()
    }

    /** A queue cannot be got back, so one press only arms it. */
    /** The watcher re-reads on the pushed version; a second read from the edit itself was waste. */
    @Test fun `an edit with the version known reads the queue once`() = runBlocking<Unit> {
        fake.pushFixture("playbackStatus", groupId)
        withTimeout(5_000) { household.groupStates.first { it[groupId]?.queueVersion == "8" } }
        delay(300)
        loads.clear()
        viewModel.moveDown(1)
        withTimeout(5_000) { while ("ReorderTracksInQueue" !in actions) delay(20) }
        fake.pushPlaybackStatus(groupId, queueVersion = "9")
        withTimeout(5_000) { while (loads.isEmpty()) delay(20) }
        delay(500)
        assertEquals(1, loads.size)
    }

    /** Refused as stale (1028): the list must show what the queue moved to. */
    @Test fun `a refused edit re-reads the queue and says so`() = runBlocking<Unit> {
        refuseEdits = true
        loads.clear()
        viewModel.moveDown(1)
        withTimeout(5_000) { while (loads.isEmpty()) delay(20) }
        withTimeout(5_000) { viewModel.notice.first { it != null } }
        delay(300)
        assertEquals(1, loads.size)
    }

    /** The version the list on screen was read at, not one fetched fresh, so stale is possible. */
    @Test fun `an edit quotes the version the list was read at`() = runBlocking<Unit> {
        viewModel.moveDown(1)
        withTimeout(5_000) { while (editVersions.isEmpty()) delay(20) }
        assertEquals(listOf("58"), editVersions.toList())
    }

    /**
     * Two presses close together: the second waits for the first to land and the list to be read
     * again, then quotes the version after it. They used to go out together on one version, and the
     * player refused the second as stale (1028).
     */
    @Test fun `a second edit waits for the first and quotes the version after it`() = runBlocking<Unit> {
        fake.pushFixture("playbackStatus", groupId)
        withTimeout(5_000) { household.groupStates.first { it[groupId]?.queueVersion == "8" } }
        delay(300)

        viewModel.moveDown(1)
        viewModel.moveDown(3)
        withTimeout(5_000) { while (editVersions.isEmpty()) delay(20) }
        delay(300)
        assertEquals("the second went out before the first had landed", 1, editVersions.size)

        // The first lands: the player moves the version, and the list is read at the new one.
        updateId = "59"
        fake.pushPlaybackStatus(groupId, queueVersion = "9")
        withTimeout(5_000) { while (editVersions.size < 2) delay(20) }
        assertEquals(listOf("58", "59"), editVersions.toList())
    }

    /**
     * A queued edit checks its slot still holds the track that was pressed: slot numbers shift when
     * a row above goes. Sent anyway, with the fresh version it now quotes, the player would accept it
     * and edit whatever had moved into the slot.
     */
    @Test fun `a queued edit whose track has moved is not sent`() = runBlocking<Unit> {
        fake.pushFixture("playbackStatus", groupId)
        withTimeout(5_000) { household.groupStates.first { it[groupId]?.queueVersion == "8" } }
        delay(300)

        viewModel.moveDown(3)
        viewModel.moveDown(1)
        withTimeout(5_000) { while (editVersions.isEmpty()) delay(20) }

        // The first lands and the list is read again with another track in slot 1.
        slotOneReplaced = true
        updateId = "59"
        fake.pushPlaybackStatus(groupId, queueVersion = "9")
        val notice = withTimeout(5_000) { viewModel.notice.first { it != null } }
        delay(300)
        assertEquals("only the first edit was sent", 1, editVersions.size)
        assertTrue(notice!!, "changed" in notice)
    }

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
