package com.rahga.x2rock.lan

import com.rahga.x2rock.model.EqSettings
import com.rahga.x2rock.model.TruePlay
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A speaker's tone: read from the captured `getPlayerSettings`, written over RenderingControl.
 * The TruePlay reply is Kitchen's One SL, captured 2026-10-02.
 */
class ToneTest {

    private lateinit var fake: FakePlayer
    private lateinit var upnp: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold
    private val requests = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()

    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        upnp = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val action = soapAction(request)
                    val fields = Regex("<(\\w+)>([^<]*)</\\1>").findAll(request.body.readUtf8())
                        .associate { it.groupValues[1] to it.groupValues[2] }
                    requests += action to fields
                    return if (action == "GetRoomCalibrationStatus") {
                        MockResponse().setBody(FakePlayer.fixtureText("GetRoomCalibrationStatus.reply.xml"))
                    } else MockResponse().setBody(EMPTY_SOAP)
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

    private val player get() = fake.id

    @Test fun `the captured settings carry the tone`() = runBlocking<Unit> {
        assertEquals(EqSettings(bass = 0, treble = 0, loudness = false), household.playerSettings(player).eq)
    }

    @Test fun `bass and treble are sent as levels, and out of range is refused before sending`() = runBlocking<Unit> {
        household.setBass(player, -3)
        household.setTreble(player, 4)
        assertEquals("SetBass" to "-3", requests[0].first to requests[0].second["DesiredBass"])
        assertEquals("SetTreble" to "4", requests[1].first to requests[1].second["DesiredTreble"])
        val refused = runCatching { household.setBass(player, 11) }.exceptionOrNull()
        assertTrue("11 should be refused, got $refused", refused is IllegalArgumentException)
        assertEquals(2, requests.size)
    }

    /** Without `Channel` the player answers 402, which reads like a bad value. */
    @Test fun `loudness names its channel`() = runBlocking<Unit> {
        household.setLoudness(player, true)
        assertEquals(mapOf("InstanceID" to "0", "Channel" to "Master", "DesiredLoudness" to "1"), requests.single().second)
    }

    @Test fun `TruePlay is read from the room calibration, and turned off by it`() = runBlocking<Unit> {
        assertEquals(TruePlay(enabled = true, available = true), household.trueplay(player))
        household.setTrueplay(player, false)
        assertEquals("0", requests.last { it.first == "SetRoomCalibrationStatus" }.second["RoomCalibrationEnabled"])
    }
}
