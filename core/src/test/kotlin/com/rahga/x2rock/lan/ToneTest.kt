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
    /** Kitchen's replies say Off; a test may turn one to On, which is the only other word sent. */
    @Volatile private var lightAndLock = "Off"

    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        upnp = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val action = soapAction(request)
                    val fields = soapFields(request.body.readUtf8())
                    requests += action to fields
                    return when (action) {
                        "GetRoomCalibrationStatus" -> MockResponse().setBody(FakePlayer.fixtureText("GetRoomCalibrationStatus.reply.xml"))
                        // Kitchen's, 2026-10-10: light off, controls unlocked, and the empty write reply.
                        "GetLEDState" -> MockResponse().setBody(FakePlayer.fixtureText("GetLEDState.xml").replace(">Off<", ">$lightAndLock<"))
                        "GetButtonLockState" -> MockResponse().setBody(FakePlayer.fixtureText("GetButtonLockState.xml").replace(">Off<", ">$lightAndLock<"))
                        "SetLEDState", "SetButtonLockState" -> MockResponse().setBody(FakePlayer.fixtureText("SetLEDState.xml"))
                        // Bedroom's Beam, 2026-10-10: feedback on, repeater on.
                        "GetLEDFeedbackState" -> MockResponse().setBody(FakePlayer.fixtureText("GetLEDFeedbackState.xml"))
                        "GetIRRepeaterState" -> MockResponse().setBody(FakePlayer.fixtureText("GetIRRepeaterState.xml"))
                        "SetLEDFeedbackState", "SetIRRepeaterState" -> MockResponse().setBody(FakePlayer.fixtureText("SetLEDFeedbackState.xml"))
                        else -> MockResponse().setBody(EMPTY_SOAP)
                    }
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        scope = CoroutineScope(SupervisorJob())
        val book = PlayerAddressBook()
        household = fakeHousehold(fake, scope, addressBook = book, upnpPort = upnp.port)
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

    // ---------------------------------------------------------------- the speaker's own switches

    @Test fun `the status light and the touch controls are read off the speaker`() = runBlocking<Unit> {
        assertEquals(false, household.statusLight(fake.id))
        assertEquals("unlocked: Kitchen's controls work", false, household.buttonsLocked(fake.id))
        assertEquals(listOf("GetLEDState", "GetButtonLockState"), requests.map { it.first }.filter { it.startsWith("Get") && it != "GetRoomCalibrationStatus" })
    }

    @Test fun `a lit light and locked controls read as such`() = runBlocking<Unit> {
        lightAndLock = "On"
        assertEquals(true, household.statusLight(fake.id))
        assertEquals(true, household.buttonsLocked(fake.id))
    }

    /** Only the two words are sent: the player lights the light for anything but `Off`. */
    @Test fun `the switches are written as On and Off`() = runBlocking<Unit> {
        household.setStatusLight(fake.id, true)
        household.setStatusLight(fake.id, false)
        household.setButtonsLocked(fake.id, true)
        household.setButtonsLocked(fake.id, false)
        assertEquals(listOf("On", "Off"), requests.filter { it.first == "SetLEDState" }.map { it.second["DesiredLEDState"] })
        assertEquals(listOf("On", "Off"), requests.filter { it.first == "SetButtonLockState" }.map { it.second["DesiredButtonLockState"] })
    }

    // ---------------------------------------------------------------- volume limit

    /** The captured getPlayerSettings carries `volumeScalingFactor: 1.000000` — no limit. */
    @Test fun `a speaker's volume limit is read from its settings`() = runBlocking<Unit> {
        assertEquals(1.0, household.playerSettings(fake.id).volumeScalingFactor!!, 0.0)
    }

    // ---------------------------------------------------------------- a soundbar's remote settings

    @Test fun `a soundbar's remote settings are read`() = runBlocking<Unit> {
        val remote = household.remoteSettings(fake.id)
        assertTrue(remote.feedback)
        assertEquals("On", remote.irRepeater)
        assertEquals(true, remote.irRepeaterOn)
    }

    /** The feedback setter's argument has no `Desired`; the repeater's does. A swap is a 402. */
    @Test fun `the remote settings are written under their own argument names`() = runBlocking<Unit> {
        household.setRemoteFeedback(fake.id, false)
        household.setIrRepeater(fake.id, false)
        assertEquals("Off", requests.first { it.first == "SetLEDFeedbackState" }.second["LEDFeedbackState"])
        assertEquals("Off", requests.first { it.first == "SetIRRepeaterState" }.second["DesiredIRRepeaterState"])
    }

    @Test fun `a repeater state other than On or Off is kept as said`() {
        assertEquals(null, RemoteSettings(feedback = true, irRepeater = "Disabled").irRepeaterOn)
    }
}
