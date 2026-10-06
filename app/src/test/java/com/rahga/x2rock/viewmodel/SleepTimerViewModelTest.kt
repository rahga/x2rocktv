package com.rahga.x2rock.viewmodel

import com.rahga.x2rock.lan.FakePlayer
import com.rahga.x2rock.lan.connectTo
import com.rahga.x2rock.lan.soapAction
import com.rahga.x2rock.lan.LanHttp
import com.rahga.x2rock.lan.MulticastGate
import com.rahga.x2rock.lan.PlayerAddressBook
import com.rahga.x2rock.lan.SeedStore
import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.lan.soapFields
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

/**
 * The pane's sleep timer is Sonos's, and `sleepTimer:1` pushes it: a timer set or cancelled
 * by anyone shows here, and what shows after setting one is what the speaker then reports.
 * The reports are the office One SL's; setting still goes over UPnP, the only way there is.
 */
class SleepTimerViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    private lateinit var fake: FakePlayer
    private lateinit var upnp: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold
    private lateinit var viewModel: PlayerViewModel
    private lateinit var groupId: String
    /** The duration of each `ConfigureSleepTimer` sent, empty for a cancel. */
    private val configured = java.util.concurrent.CopyOnWriteArrayList<String>()
    /** Every UPnP read of the timer, which the push leaves no reason to make. */
    @Volatile private var reads = 0

    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        upnp = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = request.body.readUtf8()
                    return when (soapAction(request)) {
                        "GetRemainingSleepTimerDuration" -> {
                            reads++
                            MockResponse().setBody(FakePlayer.fixtureText("GetRemainingSleepTimerDuration.none.xml"))
                        }
                        "ConfigureSleepTimer" -> {
                            configured += soapFields(body)["NewSleepTimerDuration"].orEmpty()
                            MockResponse().setBody("<s:Envelope><s:Body><u:ConfigureSleepTimerResponse/></s:Body></s:Envelope>")
                        }
                        else -> MockResponse().setResponseCode(404)
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
        viewModel = PlayerViewModel(household, RecordingNowPlaying(), testClock)
        runBlocking { household.connectTo(fake) }
        groupId = fake.groupId(household)
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        fake.shutdown()
        upnp.shutdown()
    }

    private fun left(): Long = viewModel.uiState.value.sleepTimerEndsAt!! - testClock.now()

    /** `x2rock sleep 15` from a laptop, say: the old reads, made on selecting, never saw it. */
    @Test fun `a timer set elsewhere shows while the room is open`() = runBlocking<Unit> {
        viewModel.selectGroup(groupId, "Room")
        fake.pushSleepTimer(groupId, active = true)
        withTimeout(5_000) { viewModel.uiState.first { it.sleepTimerEndsAt != null } }
        assertTrue("expected about 15 minutes, got ${left()}", left() in 14 * 60_000L..15 * 60_000L)
    }

    @Test fun `a timer cancelled elsewhere goes`() = runBlocking<Unit> {
        viewModel.selectGroup(groupId, "Room")
        fake.pushSleepTimer(groupId, active = true)
        withTimeout(5_000) { viewModel.uiState.first { it.sleepTimerEndsAt != null } }
        fake.pushSleepTimer(groupId, active = false)
        withTimeout(5_000) { viewModel.uiState.first { it.sleepTimerEndsAt == null } }
    }

    /**
     * Reported before the room was opened: the time left is counted from the report, not from
     * opening it, or a room looked at late would show more time than it has.
     */
    @Test fun `a timer reported before the room is opened counts from the report`() = runBlocking<Unit> {
        fake.pushSleepTimer(groupId, active = true)
        withTimeout(5_000) { household.groupStates.first { it[groupId]?.sleepTimerEndsAt != null } }
        delay(1_200)
        viewModel.selectGroup(groupId, "Room")
        withTimeout(5_000) { viewModel.uiState.first { it.sleepTimerEndsAt != null } }
        assertTrue("counted from the opening: ${left()}", left() <= 15 * 60_000L - 1_000)
    }

    /** Setting goes over UPnP; what shows afterwards is the speaker's own report, and nothing is read. */
    @Test fun `setting and cancelling send the timer and read nothing back`() = runBlocking<Unit> {
        viewModel.selectGroup(groupId, "Room")
        viewModel.setSleepTimer(45)
        withTimeout(5_000) { while (configured.isEmpty()) delay(20) }
        viewModel.cancelSleepTimer()
        withTimeout(5_000) { while (configured.size < 2) delay(20) }
        assertEquals(listOf("00:45:00", ""), configured.toList())
        delay(300)
        assertEquals("the timer was read back instead of waiting for its report", 0, reads)
    }
}
