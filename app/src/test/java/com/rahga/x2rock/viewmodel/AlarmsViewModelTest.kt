package com.rahga.x2rock.viewmodel

import com.rahga.x2rock.lan.EMPTY_SOAP
import com.rahga.x2rock.lan.FakePlayer
import com.rahga.x2rock.lan.LanHttp
import com.rahga.x2rock.lan.MulticastGate
import com.rahga.x2rock.lan.PlayerAddressBook
import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.lan.connectTo
import com.rahga.x2rock.lan.soapAction
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
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The alarms screen against the fake household and Kitchen's captured `AlarmClock` replies (see
 * `AlarmListTest`). The captures name their room by a redacted id, swapped here for a player the
 * fake household has, so the rows can be named.
 */
class AlarmsViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    private lateinit var fake: FakePlayer
    private lateinit var upnp: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold
    private lateinit var viewModel: AlarmsViewModel
    private lateinit var playerId: String
    private lateinit var roomName: String
    private val requests = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
    @Volatile private var duplicate = false

    @Before fun setUp() = runBlocking {
        fake = FakePlayer().also { it.start() }
        upnp = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val action = soapAction(request)
                    requests += action to soapFields(request.body.readUtf8())
                    val body = when (action) {
                        "ListAlarms" -> FakePlayer.fixtureText("ListAlarms.two.xml")
                        "UpdateAlarm" -> FakePlayer.fixtureText("UpdateAlarm.reply.xml")
                        "CreateAlarm" -> if (duplicate) return MockResponse().setResponseCode(500)
                            .setBody(FakePlayer.fixtureText("CreateAlarm.fault801.xml"))
                            else FakePlayer.fixtureText("CreateAlarm.reply.xml")
                        "GetTimeNow" -> FakePlayer.fixtureText("GetTimeNow.xml")
                        "GetTimeZone" -> FakePlayer.fixtureText("GetTimeZone.xml")
                        "Browse" -> FakePlayer.fixtureText("Browse.FV2.xml")
                        else -> EMPTY_SOAP
                    }
                    return MockResponse().setBody(body.replace("RINCON_ALARMROOM0001400", playerId))
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        scope = CoroutineScope(SupervisorJob())
        val book = PlayerAddressBook()
        household = SonosHousehold(
            scope = scope, addressBook = book, multicast = MulticastGate.None,
            client = LanHttp.client(book), port = fake.port, upnpPort = upnp.port,
        )
        household.connectTo(fake, 10_000)
        playerId = household.state.value.groups.first().playerIds.first()
        roomName = household.playerName(playerId)
        viewModel = AlarmsViewModel(household)
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        fake.shutdown()
        upnp.shutdown()
    }

    private suspend fun ready() = withTimeout(5_000) {
        viewModel.uiState.first { it is AlarmsViewModel.UiState.Ready } as AlarmsViewModel.UiState.Ready
    }

    @Test fun `each alarm names its time, room, days, sound and volume`() = runBlocking<Unit> {
        val state = ready()
        assertEquals(listOf("06:45 · $roomName", "07:30 · $roomName"), state.alarms.map { it.title })
        assertEquals("Weekdays · Chime · volume 12", state.alarms[0].detail)
        assertEquals("Mon, Wed, Fri · Scenery · volume 15", state.alarms[1].detail)
        assertEquals("10:49", state.clock?.time)
    }

    @Test fun `select arms an alarm that is off`() = runBlocking<Unit> {
        ready()
        requests.clear()
        viewModel.toggle(33)
        withTimeout(5_000) { while (requests.none { it.first == "UpdateAlarm" }) delay(20) }
        val sent = requests.first { it.first == "UpdateAlarm" }.second
        assertEquals("33", sent["ID"])
        assertEquals("1", sent["Enabled"])
    }

    /** Room, time, how often, a favourite: the alarm made is that, armed, at the default volume. */
    @Test fun `a new alarm is made from the steps chosen`() = runBlocking<Unit> {
        ready()
        viewModel.startNew()
        viewModel.chooseRoom(viewModel.rooms().first { it.playerId == playerId })
        viewModel.chooseTime(6, 30)
        viewModel.chooseRecurrence("WEEKDAYS")
        val scenery = withTimeout(5_000) { viewModel.programs.first { it != null } }!!.first { it.title == "Scenery" }
        viewModel.chooseProgram(scenery)
        assertEquals(AlarmDraft.Step.CONFIRM, viewModel.draft.value?.step)
        requests.clear()
        viewModel.create()
        withTimeout(5_000) { while (requests.none { it.first == "CreateAlarm" }) delay(20) }
        val sent = requests.first { it.first == "CreateAlarm" }.second
        assertEquals("06:30:00", sent["StartLocalTime"])
        assertEquals("WEEKDAYS", sent["Recurrence"])
        assertEquals(playerId, sent["RoomUUID"])
        assertEquals("1", sent["Enabled"])
        assertEquals("20", sent["Volume"])
        assertEquals(scenery.uri.replace("&", "&amp;"), sent["ProgramURI"])
        withTimeout(5_000) { viewModel.draft.first { it == null } }
    }

    @Test fun `a second alarm at that time in that room is said in words`() = runBlocking<Unit> {
        ready()
        duplicate = true
        viewModel.startNew()
        viewModel.chooseRoom(viewModel.rooms().first { it.playerId == playerId })
        viewModel.chooseTime(6, 45)
        viewModel.chooseRecurrence("DAILY")
        viewModel.chooseProgram(null)
        viewModel.create()
        assertEquals("$roomName already has an alarm at 06:45.", withTimeout(5_000) { viewModel.notice.first { it != null } })
        assertNotNull("the draft stays, to be changed", viewModel.draft.value)
    }
}
