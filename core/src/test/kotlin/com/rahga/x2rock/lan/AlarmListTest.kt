package com.rahga.x2rock.lan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The household's alarms over `AlarmClock:1`. Every reply is Kitchen's, captured 2026-10-10: a list
 * of two alarms made for the purpose (disarmed, one chime on weekdays, one playing a favourite on
 * Mon/Wed/Fri), the empty list before them, `UpdateAlarm`'s reply and its 402 to a partial record,
 * `CreateAlarm`'s id and its 801 for a second alarm at the same time, the clock, and `Browse FV:2`.
 */
class AlarmListTest {

    private lateinit var fake: FakePlayer
    private lateinit var upnp: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold
    private val book = PlayerAddressBook()
    /** Each action and its fields, in order. */
    private val requests = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
    @Volatile private var list = "ListAlarms.two.xml"
    @Volatile private var duplicate = false

    /** The ten fields `UpdateAlarm` requires; the 402 capture is what a record missing one gets. */
    private val fields = listOf(
        "StartLocalTime", "Duration", "Recurrence", "Enabled", "RoomUUID",
        "ProgramURI", "ProgramMetaData", "PlayMode", "Volume", "IncludeLinkedZones",
    )

    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        upnp = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val action = soapAction(request)
                    val f = soapFields(request.body.readUtf8())
                    requests += action to f
                    return when (action) {
                        "ListAlarms" -> MockResponse().setBody(FakePlayer.fixtureText(list))
                        // As the player does: all ten fields and an id, or 402.
                        "UpdateAlarm" -> if (f.keys.containsAll(fields + "ID")) MockResponse().setBody(FakePlayer.fixtureText("UpdateAlarm.reply.xml"))
                            else MockResponse().setResponseCode(500).setBody(FakePlayer.fixtureText("UpdateAlarm.partial.fault402.xml"))
                        "CreateAlarm" -> if (duplicate) MockResponse().setResponseCode(500).setBody(FakePlayer.fixtureText("CreateAlarm.fault801.xml"))
                            else MockResponse().setBody(FakePlayer.fixtureText("CreateAlarm.reply.xml"))
                        "GetTimeNow" -> MockResponse().setBody(FakePlayer.fixtureText("GetTimeNow.xml"))
                        "GetTimeZone" -> MockResponse().setBody(FakePlayer.fixtureText("GetTimeZone.xml"))
                        "Browse" -> MockResponse().setBody(FakePlayer.fixtureText("Browse.FV2.xml"))
                        else -> MockResponse().setBody(EMPTY_SOAP)
                    }
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        scope = CoroutineScope(SupervisorJob())
        household = SonosHousehold(
            scope = scope, addressBook = book, multicast = MulticastGate.None,
            client = LanHttp.client(book), port = fake.port, upnpPort = upnp.port,
        )
        runBlocking { household.connectTo(fake, 10_000) }
        requests.clear()
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        fake.shutdown()
        upnp.shutdown()
    }

    @Test fun `the list is read out of the text the reply carries`() = runBlocking<Unit> {
        val (chime, album) = household.alarms()
        assertEquals(33, chime.id)
        assertEquals("06:45", chime.time)
        assertEquals("Weekdays", recurrenceLabel(chime.recurrence))
        assertEquals("Chime", chime.programTitle)
        assertFalse(chime.enabled)
        assertEquals(12, chime.volume)
        assertEquals(34, album.id)
        assertEquals("Mon, Wed, Fri", recurrenceLabel(album.recurrence))
        assertEquals("Scenery", album.programTitle)
        assertEquals("RINCON_ALARMROOM0001400", album.roomUuid)
    }

    @Test fun `no alarms is an empty list`() = runBlocking<Unit> {
        list = "ListAlarms.empty.xml"
        assertTrue(household.alarms().isEmpty())
    }

    /** The whole record goes back, read fresh, with only `Enabled` changed. */
    @Test fun `arming an alarm writes it back whole`() = runBlocking<Unit> {
        household.setAlarmEnabled(34, true)
        assertEquals(listOf("ListAlarms", "UpdateAlarm"), requests.map { it.first })
        val sent = requests.last().second
        assertEquals("34", sent["ID"])
        assertEquals("1", sent["Enabled"])
        assertEquals("07:30:00", sent["StartLocalTime"])
        assertEquals("ON_135", sent["Recurrence"])
        assertEquals("15", sent["Volume"])
        assertTrue("the program's metadata goes back with it", "Scenery" in sent["ProgramMetaData"].orEmpty())
    }

    @Test fun `an alarm already in that state is not written`() = runBlocking<Unit> {
        household.setAlarmEnabled(33, false)
        assertEquals(listOf("ListAlarms"), requests.map { it.first })
    }

    @Test fun `a new alarm answers the id it was given`() = runBlocking<Unit> {
        assertEquals(36, household.createAlarm(Alarm(id = 0, startTime = "05:15:00", roomUuid = "RINCON_ALARMROOM0001400")))
    }

    @Test fun `a second alarm at the same time in that room is refused 801`() = runBlocking<Unit> {
        duplicate = true
        try {
            household.createAlarm(Alarm(id = 0, startTime = "06:45:00", roomUuid = "RINCON_ALARMROOM0001400"))
            fail("created")
        } catch (e: UpnpRefusedException) {
            assertEquals("801", e.upnpCode)
        }
    }

    @Test fun `the household's clock and whether it knows its timezone`() = runBlocking<Unit> {
        val clock = household.householdClock()
        assertEquals("10:49", clock.time)
        assertTrue(clock.timeZoneSet)
    }

    @Test fun `favourites are programs, each with its URI and metadata`() = runBlocking<Unit> {
        val programs = household.alarmPrograms()
        assertEquals(8, programs.size)
        val scenery = programs.first { it.title == "Scenery" }
        assertTrue(scenery.uri.startsWith("x-rincon-cpcontainer:"))
        assertTrue("<dc:title>Scenery</dc:title>" in scenery.metaData)
    }
}
