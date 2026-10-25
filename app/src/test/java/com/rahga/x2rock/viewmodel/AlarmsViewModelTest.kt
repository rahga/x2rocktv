package com.rahga.x2rock.viewmodel

import com.rahga.x2rock.lan.unknownAction
import com.rahga.x2rock.lan.FakePlayer
import com.rahga.x2rock.lan.PlayerAddressBook
import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.lan.connectTo
import com.rahga.x2rock.lan.soapAction
import com.rahga.x2rock.lan.soapFields
import com.rahga.x2rock.lan.fakeHousehold
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
import org.junit.Assert.assertTrue
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
    /** Whether DestroyAlarm answers as for an alarm already gone. */
    @Volatile private var gone = false
    /** While set, UpdateAlarm answers only once it is counted down: a write still out. */
    @Volatile private var holdUpdate: java.util.concurrent.CountDownLatch? = null

    @Before fun setUp() = runBlocking {
        fake = FakePlayer().also { it.start() }
        upnp = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val action = soapAction(request)
                    requests += action to soapFields(request.body.readUtf8())
                    val body = when (action) {
                        "ListAlarms" -> FakePlayer.fixtureText("ListAlarms.two.xml")
                        "UpdateAlarm" -> {
                            holdUpdate?.await(10, java.util.concurrent.TimeUnit.SECONDS)
                            FakePlayer.fixtureText("UpdateAlarm.reply.xml")
                        }
                        "DestroyAlarm" -> if (gone) return MockResponse().setResponseCode(500)
                            .setBody(FakePlayer.fixtureText("DestroyAlarm.gone.fault800.xml"))
                            else FakePlayer.fixtureText("DestroyAlarm.reply.xml")
                        "CreateAlarm" -> if (duplicate) return MockResponse().setResponseCode(500)
                            .setBody(FakePlayer.fixtureText("CreateAlarm.fault801.xml"))
                            else FakePlayer.fixtureText("CreateAlarm.reply.xml")
                        "GetTimeNow" -> FakePlayer.fixtureText("GetTimeNow.xml")
                        "GetTimeZone" -> FakePlayer.fixtureText("GetTimeZone.xml")
                        "Browse" -> FakePlayer.fixtureText("Browse.FV2.xml")
                        else -> return unknownAction()
                    }
                    return MockResponse().setBody(body.replace("RINCON_ALARMROOM0001400", playerId))
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        scope = CoroutineScope(SupervisorJob())
        val book = PlayerAddressBook()
        household = fakeHousehold(fake, scope, addressBook = book, upnpPort = upnp.port)
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

    /** A second press while the first is out would be refused as a duplicate of it, and say so. */
    @Test fun `make pressed twice makes one alarm`() = runBlocking<Unit> {
        ready()
        viewModel.startNew()
        viewModel.chooseRoom(viewModel.rooms().first { it.playerId == playerId })
        viewModel.chooseTime(5, 15)
        viewModel.chooseRecurrence("ONCE")
        viewModel.chooseProgram(null)
        requests.clear()
        viewModel.create()
        viewModel.create()
        withTimeout(5_000) { viewModel.draft.first { it == null } }
        assertEquals(1, requests.count { it.first == "CreateAlarm" })
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

    /**
     * Changing an alarm opens on its summary, a change comes back to it, and Save writes the whole
     * record with only what was changed: Wednesday's 07:30 album alarm moved to 08:15 keeps its days,
     * its album, its half hour and its volume.
     */
    @Test fun `an alarm changed keeps everything it was not told to change`() = runBlocking<Unit> {
        ready()
        viewModel.startEdit(34)
        assertEquals(AlarmDraft.Step.CONFIRM, viewModel.draft.value?.step)
        viewModel.change(AlarmDraft.Step.TIME)
        viewModel.chooseTime(8, 15)
        assertEquals("a change comes back to the summary", AlarmDraft.Step.CONFIRM, viewModel.draft.value?.step)
        requests.clear()
        viewModel.save()
        withTimeout(5_000) { while (requests.none { it.first == "UpdateAlarm" }) delay(20) }
        val sent = requests.first { it.first == "UpdateAlarm" }.second
        assertEquals("34", sent["ID"])
        assertEquals("08:15:00", sent["StartLocalTime"])
        assertEquals("ON_135", sent["Recurrence"])
        assertEquals("00:30:00", sent["Duration"])
        assertEquals("15", sent["Volume"])
        assertEquals("0", sent["Enabled"])
        assertTrue("its album, not the chime", sent["ProgramURI"].orEmpty().startsWith("x-rincon-cpcontainer:"))
        withTimeout(5_000) { viewModel.draft.first { it == null } }
    }

    /**
     * Save on one alarm, Back while its write is out, and another opened: the first write landing
     * used to close the second mid-change, and a Save on it then was ignored without a word (P-85).
     */
    @Test fun `a save landing late leaves the alarm opened since, and a press meanwhile is answered`() = runBlocking<Unit> {
        ready()
        val held = java.util.concurrent.CountDownLatch(1)
        holdUpdate = held
        viewModel.startEdit(34)
        requests.clear()
        viewModel.save()
        withTimeout(5_000) { while (requests.none { it.first == "UpdateAlarm" }) delay(20) }
        viewModel.back()
        viewModel.startEdit(33)
        viewModel.save()
        val said = viewModel.notice.value
        if (said != "Saving…") held.countDown() // not left holding the server's thread
        assertEquals("Saving…", said)

        // The list is read again once the write lands, after the draft would have been closed.
        val reads = requests.count { it.first == "ListAlarms" }
        held.countDown()
        withTimeout(5_000) { while (requests.count { it.first == "ListAlarms" } == reads) delay(20) }
        delay(300)
        assertEquals("the alarm opened since is still open", 33, viewModel.draft.value?.editing?.id)
    }

    /** Remove takes two presses; the second deletes that alarm. */
    @Test fun `remove takes a second press`() = runBlocking<Unit> {
        ready()
        viewModel.startEdit(33)
        requests.clear()
        viewModel.remove()
        assertTrue(viewModel.removeArmed.value)
        delay(300)
        assertTrue("one press sends nothing", requests.none { it.first == "DestroyAlarm" })
        viewModel.remove()
        withTimeout(5_000) { while (requests.none { it.first == "DestroyAlarm" }) delay(20) }
        assertEquals("33", requests.first { it.first == "DestroyAlarm" }.second["ID"])
        withTimeout(5_000) { viewModel.draft.first { it == null } }
    }

    /** An alarm another controller removed first is gone, which is what was asked: no failure said. */
    @Test fun `removing an alarm already gone is not a failure`() = runBlocking<Unit> {
        ready()
        gone = true
        viewModel.startEdit(33)
        viewModel.remove()
        viewModel.remove()
        withTimeout(5_000) { viewModel.draft.first { it == null } }
        delay(300)
        assertEquals(null, viewModel.notice.value)
    }
}
