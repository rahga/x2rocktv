package com.rahga.x2rock.lan

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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A ringing alarm, which the player says only when asked. The replies are Kitchen's, captured
 * 2026-10-02 with the built-in chime at volume 3: `AlarmID` while it rang, the fault 800 when
 * nothing was running, and an empty `SnoozeAlarm` reply.
 */
class AlarmTest {

    private lateinit var fake: FakePlayer
    private lateinit var upnp: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold
    @Volatile private var ringing = true
    /** A fault code other than 800, to answer with instead of the captures. */
    @Volatile private var refuseWith: String? = null
    private val book = PlayerAddressBook()
    /** Each AVTransport action and its body, in order. */
    private val actions = CopyOnWriteArrayList<Pair<String, String>>()


    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        upnp = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val action = soapAction(request)
                    actions += action to request.body.readUtf8()
                    return when (action) {
                        "GetRunningAlarmProperties" ->
                            if (refuseWith != null) MockResponse().setResponseCode(500)
                                .setBody(FakePlayer.fixtureText("GetRunningAlarmProperties.none.xml").replace("<errorCode>800<", "<errorCode>$refuseWith<"))
                            else if (ringing) MockResponse().setBody(FakePlayer.fixtureText("GetRunningAlarmProperties.ringing.xml"))
                            else MockResponse().setResponseCode(500).setBody(FakePlayer.fixtureText("GetRunningAlarmProperties.none.xml"))
                        "SnoozeAlarm" -> MockResponse().setBody(FakePlayer.fixtureText("SnoozeAlarm.reply.xml"))
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
        runBlocking {
            household.connectTo(fake, 10_000)
        }
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        fake.shutdown()
        upnp.shutdown()
    }

    private val kitchen get() = household.state.value.groups.first { it.name == "Kitchen" }


    private fun asked() = actions.count { it.first == "GetRunningAlarmProperties" }

    @Test fun `a room that starts playing because its alarm rang says which alarm`() = runBlocking<Unit> {
        fake.pushPlaybackStatus(kitchen.id, "PLAYBACK_STATE_PLAYING")
        withTimeout(5_000) { household.groupStates.first { it[kitchen.id]?.ringingAlarm == 15 } }
    }

    /** The ordinary case: a room started playing and the player says no alarm is running. */
    @Test fun `a room that starts playing on its own has no alarm`() = runBlocking<Unit> {
        ringing = false
        fake.pushPlaybackStatus(kitchen.id, "PLAYBACK_STATE_PLAYING")
        withTimeout(5_000) { while (asked() == 0) delay(20) }
        delay(200)
        assertNull(household.groupStates.value[kitchen.id]?.ringingAlarm)
    }

    /** Snoozed or stopped, the room is not playing, and is no longer offered snooze. */
    @Test fun `an alarm stops ringing when the room stops playing`() = runBlocking<Unit> {
        fake.pushPlaybackStatus(kitchen.id, "PLAYBACK_STATE_PLAYING")
        withTimeout(5_000) { household.groupStates.first { it[kitchen.id]?.ringingAlarm == 15 } }
        fake.pushPlaybackStatus(kitchen.id, "PLAYBACK_STATE_PAUSED")
        withTimeout(5_000) { household.groupStates.first { it[kitchen.id]?.ringingAlarm == null } }
    }

    /** With UPnP off every SOAP call is a 403, so a room starting to play is not worth one. */
    @Test fun `a household with UPnP off is not asked`() = runBlocking<Unit> {
        fake.setUpnpAllowed(false)
        withTimeout(5_000) { household.state.first { it.upnpOff } }
        fake.pushPlaybackStatus(kitchen.id, "PLAYBACK_STATE_PLAYING")
        delay(500)
        assertEquals(0, asked())
    }

    /** Asked on starting to play, not on every event while playing: nothing polls. */
    @Test fun `a room already playing is not asked again`() = runBlocking<Unit> {
        fake.pushPlaybackStatus(kitchen.id, "PLAYBACK_STATE_PLAYING")
        withTimeout(5_000) { household.groupStates.first { it[kitchen.id]?.ringingAlarm == 15 } }
        repeat(3) { fake.pushPlaybackStatus(kitchen.id, "PLAYBACK_STATE_PLAYING") }
        delay(500)
        assertEquals(1, asked())
    }

    /** 800 is the player's answer that nothing is ringing; any other refusal is a failure. */
    @Test fun `the fault 800 means no alarm, and other faults are failures`() = runBlocking<Unit> {
        val direct = Upnp(LanHttp.client(book), port = upnp.port)
        val host = PlayerNames.localHostname(kitchen.coordinatorId)!!
        assertEquals(15, direct.runningAlarm(host))
        ringing = false
        assertNull(direct.runningAlarm(host))
        refuseWith = "701"
        assertEquals("701", runCatching { direct.runningAlarm(host) }.exceptionOrNull().let { (it as UpnpRefusedException).upnpCode })
    }

    @Test fun `snooze is nine minutes, sent to the group`() = runBlocking<Unit> {
        household.snoozeAlarm(kitchen.id)
        val (_, body) = actions.first { it.first == "SnoozeAlarm" }
        assertEquals("00:09:00", Regex("<Duration>([^<]*)<").find(body)!!.groupValues[1])
    }
}
