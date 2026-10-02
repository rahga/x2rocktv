package com.rahga.x2rock.viewmodel

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

/**
 * The pane's sleep timer is Sonos's, so it shows a timer someone else set, and what it shows
 * after setting one is what the speaker then reports. The replies are the office One SL's.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain and resetMain
class SleepTimerViewModelTest {

    private lateinit var fake: FakePlayer
    private lateinit var upnp: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold
    private lateinit var viewModel: PlayerViewModel
    private lateinit var groupId: String
    @Volatile private var armed = false

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
                    return when (action) {
                        "GetRemainingSleepTimerDuration" -> MockResponse().setBody(
                            capture(if (armed) "GetRemainingSleepTimerDuration.armed.xml" else "GetRemainingSleepTimerDuration.none.xml")
                        )
                        "ConfigureSleepTimer" -> {
                            armed = "<NewSleepTimerDuration></NewSleepTimerDuration>" !in body
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
        viewModel = PlayerViewModel(household, RecordingNowPlaying())
        runBlocking {
            household.connect(Discovery.DiscoveredPlayer(fake.id, InetAddress.getByName("127.0.0.1"), fake.householdId))
            withTimeout(5_000) { household.state.first { it.connected } }
        }
        groupId = household.state.value.groups.first { it.coordinatorId == fake.id }.id
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        fake.shutdown()
        upnp.shutdown()
        Dispatchers.resetMain()
    }

    /** Set from the Sonos app, say: the old timer lived in this view model and never knew. */
    @Test fun `a timer set elsewhere shows when the room is selected`() = runBlocking<Unit> {
        armed = true
        viewModel.selectGroup(groupId, "Room")
        val remaining = withTimeout(5_000) { viewModel.uiState.first { it.sleepTimerRemainingMillis != null } }
            .sleepTimerRemainingMillis!!
        assertTrue("expected about 45 minutes, got $remaining", remaining in 44 * 60_000L..45 * 60_000L)
    }

    @Test fun `setting and cancelling show what the speaker then reports`() = runBlocking<Unit> {
        viewModel.selectGroup(groupId, "Room")
        viewModel.setSleepTimer(45)
        withTimeout(5_000) { viewModel.uiState.first { it.sleepTimerRemainingMillis != null } }
        viewModel.cancelSleepTimer()
        withTimeout(5_000) { viewModel.uiState.first { it.sleepTimerRemainingMillis == null } }
    }
}
