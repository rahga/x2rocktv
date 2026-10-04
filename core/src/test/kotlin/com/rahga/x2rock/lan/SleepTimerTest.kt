package com.rahga.x2rock.lan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Sonos's own sleep timer, over AVTransport. Both replies are the office One SL's, captured
 * 2026-10-01: none set, and armed for 45 minutes.
 */
class SleepTimerTest {

    private lateinit var fake: FakePlayer
    private lateinit var upnp: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold
    @Volatile private var armed = false
    /** Every `NewSleepTimerDuration` sent, in order. */
    private val durations = CopyOnWriteArrayList<String>()


    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        upnp = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val action = soapAction(request)
                    val body = request.body.readUtf8()
                    return when (action) {
                        "GetRemainingSleepTimerDuration" -> MockResponse().setBody(
                            FakePlayer.fixtureText(if (armed) "GetRemainingSleepTimerDuration.armed.xml" else "GetRemainingSleepTimerDuration.none.xml")
                        )
                        "ConfigureSleepTimer" -> {
                            val duration = Regex("<NewSleepTimerDuration>([^<]*)</NewSleepTimerDuration>").find(body)!!.groupValues[1]
                            durations += duration
                            armed = duration.isNotEmpty()
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
            client = LanHttp.client(book), port = fake.port, upnpPort = upnp.port,
        )
        runBlocking {
            household.connectTo(fake)
        }
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        fake.shutdown()
        upnp.shutdown()
    }

    @Test fun `none set reads as none, armed reads its time, and cancel sends an empty duration`() = runBlocking<Unit> {
        val group = household.state.value.groups.first().id
        assertNull(household.sleepTimer(group))

        household.setSleepTimer(group, 45)
        assertEquals("00:45:00", durations.last())
        assertEquals(45 * 60_000L, household.sleepTimer(group))

        household.setSleepTimer(group, null)
        assertEquals("an empty duration is what cancels", "", durations.last())
        assertNull(household.sleepTimer(group))
    }

    /** Expired and about to stop is a time, zero — not "no timer", which is the empty element. */
    @Test fun `the clock reads HH MM SS, and zero is a time`() {
        assertEquals(0L, parseClock("00:00:00"))
        assertEquals(3_723_000L, parseClock("01:02:03"))
        assertNull(parseClock("90"))
        assertEquals("01:30:00", formatClock(90 * 60_000L))
        assertEquals("00:00:59", formatClock(59_999L))
    }
}
