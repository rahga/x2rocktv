package com.rahga.x2rock.viewmodel

import com.rahga.x2rock.store.BetaFeatures
import com.rahga.x2rock.store.PendingRoomDeepLink
import com.rahga.x2rock.store.RoomPreferencesStore
import com.rahga.x2rock.store.ThemeStore
import com.rahga.x2rock.lan.unknownAction
import com.rahga.x2rock.lan.FakePlayer
import com.rahga.x2rock.lan.soapAction
import com.rahga.x2rock.lan.PlayerAddressBook
import com.rahga.x2rock.lan.SonosHousehold
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
import com.rahga.x2rock.store.PresetStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bass and treble, stepped from the remote, against a player that takes a moment to answer.
 *
 * Each write is UPnP and each is followed by a read of the settings, so presses closer
 * together than a round trip used to race: an early read landed after a later press, put the
 * old level back on screen, and the next press stepped from that.
 */
class ToneStepsTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()


    private lateinit var fake: FakePlayer
    private lateinit var upnp: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold
    private lateinit var viewModel: HomeViewModel
    /** Every path asked of the speakers' UPnP port, SOAP and pages alike. */
    private val paths = CopyOnWriteArrayList<String>()
    /** Every SetBass, in the order the player received them. */
    private val basses = CopyOnWriteArrayList<Int>()

    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        upnp = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    paths += request.path.orEmpty()
                    val action = soapAction(request)
                    if (action == "SetBass") {
                        val level = Regex("<DesiredBass>(-?\\d+)<").find(request.body.readUtf8())!!.groupValues[1].toInt()
                        // Slow enough that a press can land while it is out, as one does on a remote.
                        Thread.sleep(200)
                        basses += level
                        fake.eq = Triple(level, 0, false)
                    }
                    // The read after a write asks TruePlay too; slow, so a press can land inside it.
                    if (action == "GetRoomCalibrationStatus") Thread.sleep(400)
                    val text = request.body.readUtf8()
                    if (action == "SetLEDState") lights += Regex("<DesiredLEDState>(\\w+)<").find(text)!!.groupValues[1]
                    if (action == "SetLEDFeedbackState") feedbacks += Regex("<LEDFeedbackState>(\\w+)<").find(text)!!.groupValues[1]
                    if (action == "SetEQ") eqWrites += Regex("<EQType>(\\w+)</EQType><DesiredValue>(-?\\d+)<").find(text)!!.destructured.let { (t, v) -> "$t=$v" }
                    return MockResponse().setBody(when (action) {
                        // Kitchen's, 2026-10-10: light off, controls unlocked.
                        "GetLEDState" -> FakePlayer.fixtureText("GetLEDState.xml")
                        "GetButtonLockState" -> FakePlayer.fixtureText("GetButtonLockState.xml")
                        "SetLEDState" -> FakePlayer.fixtureText("SetLEDState.xml")
                        // Bedroom's Beam, 2026-10-10.
                        "GetLEDFeedbackState" -> FakePlayer.fixtureText("GetLEDFeedbackState.xml")
                        "GetIRRepeaterState" -> FakePlayer.fixtureText("GetIRRepeaterState.xml")
                        "SetLEDFeedbackState" -> FakePlayer.fixtureText("SetLEDFeedbackState.xml")
                        // Kitchen's SubGain reply, 2026-10-11, standing for every EQ type read here.
                        "GetEQ" -> FakePlayer.fixtureText("GetEQ.SubGain.xml")
                        else -> return unknownAction()
                    })
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        scope = CoroutineScope(SupervisorJob())
        val book = PlayerAddressBook()
        household = fakeHousehold(fake, scope, addressBook = book, upnpPort = upnp.port)
        val prefs = FakePreferences()
        viewModel = HomeViewModel(
            household = household,
            themeStore = ThemeStore(prefs),
            roomPrefsStore = RoomPreferencesStore(prefs),
            channelSync = RecordingChannelSync(),
            pendingRoomDeepLink = PendingRoomDeepLink(),
            presetStore = PresetStore(prefs),
            betaFeatures = BetaFeatures(prefs),
            appleMusic = com.rahga.x2rock.store.AppleMusicSetting(prefs, household),
        )
        runBlocking {
            household.connect(fake.seed)
            withTimeout(10_000) { viewModel.uiState.first { it is HomeViewModel.UiState.Success } }
        }
    }

    /** Every `SetEQ` written, as `type=value`, in order. */
    private val eqWrites = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** Every `LEDFeedbackState` written, in order. */
    private val feedbacks = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** Every `DesiredLEDState` written, in order. */
    private val lights = java.util.concurrent.CopyOnWriteArrayList<String>()

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        fake.shutdown()
        upnp.shutdown()
    }

    private suspend fun openKitchen() {
        val kitchen = household.state.value.groups.first { it.name == "Kitchen" }
        viewModel.loadTone(kitchen.id)
        withTimeout(5_000) { viewModel.tone.first { it != null } }
    }

    /**
     * A write's own read lands after the panel has moved on to another room: it is Kitchen's, and
     * showing it in Bedroom's panel would aim Bedroom's switches at Kitchen.
     */
    @Test fun `a read after a write does not land in the next room's panel`() = runBlocking<Unit> {
        openKitchen()
        viewModel.toggleStatusLight()
        val bedroom = household.state.value.groups.first { it.name == "Bedroom" }
        viewModel.loadTone(bedroom.id)
        withTimeout(5_000) { while (lights.isEmpty()) delay(20) }
        // Past Kitchen's read after the write, which waits on the slow TruePlay answer.
        delay(1_500)
        assertEquals(bedroom.coordinatorId, viewModel.tone.value?.playerId)
    }

    /** A burst of presses is one write, of the total. */
    @Test fun `quick presses send their total once`() = runBlocking<Unit> {
        openKitchen()
        repeat(3) { viewModel.stepBass(+1) }
        withTimeout(5_000) { while (basses.isEmpty()) delay(20) }
        delay(500)
        assertEquals(listOf(3), basses.toList())
    }

    /**
     * The second press lands while the read after the first write is out, so that read answers
     * 1 after the screen already says 2. Shown, it would also make the third press step from 1.
     * Three presses up from 0 must end at 3, on the speaker and on screen.
     */
    @Test fun `a read that a press overtook does not lose the press`() = runBlocking<Unit> {
        openKitchen()
        viewModel.stepBass(+1)  // written at ~300ms, read from ~500ms to ~900ms
        delay(600)
        viewModel.stepBass(+1)  // inside that read
        delay(400)
        viewModel.stepBass(+1)  // after it has landed
        // Long enough for the last debounce, its write and the read after it.
        delay(1_500)
        assertEquals(3, basses.last())
        assertEquals(3, viewModel.tone.value?.bass)
    }

    /** The panel's Room settings read the speaker's own switches with its tone, and write them. */
    @Test fun `the status light is read with the tone and switched from the panel`() = runBlocking<Unit> {
        openKitchen()
        val read = withTimeout(5_000) { viewModel.tone.first { it?.statusLight != null } }!!
        assertEquals(false, read.statusLight)
        assertEquals(false, read.buttonsLocked)
        viewModel.toggleStatusLight()
        assertEquals("shown at once", true, viewModel.tone.value?.statusLight)
        withTimeout(5_000) { while (lights.isEmpty()) delay(20) }
        assertEquals(listOf("On"), lights.toList())
    }

    /** The limit is read with the tone: none, in Kitchen's captured settings. */
    @Test fun `the volume limit is read with the tone`() = runBlocking<Unit> {
        openKitchen()
        assertEquals(100, viewModel.tone.value?.volumeLimit)
    }

    /** A room with a soundbar reads its remote settings with the tone, and switches them on the soundbar. */
    @Test fun `a soundbar's remote light is read and switched from the panel`() = runBlocking<Unit> {
        val bedroom = household.state.value.groups.first { it.name == "Bedroom" }
        viewModel.loadTone(bedroom.id)
        val read = withTimeout(5_000) { viewModel.tone.first { it?.remote != null } }!!
        assertEquals(true, read.remote?.feedback)
        assertEquals(true, read.remote?.irRepeaterOn)
        viewModel.toggleRemoteFeedback()
        withTimeout(5_000) { while (feedbacks.isEmpty()) delay(20) }
        assertEquals(listOf("Off"), feedbacks.toList())
    }

    /** Kitchen has no soundbar, and runs on mains: no remote settings and no battery. */
    @Test fun `a room without a soundbar has no remote settings`() = runBlocking<Unit> {
        openKitchen()
        assertEquals(null, viewModel.tone.value?.remote)
        assertEquals(null, viewModel.tone.value?.battery)
    }

    /**
     * A portable's battery is asked for only with Beta features on, since it has never been read off
     * one (T-21). Kitchen is on mains, so the question is whether it is asked, not what it says.
     */
    @Test fun `the battery is asked for only with beta features on`() = runBlocking<Unit> {
        openKitchen()  // the tone is published only once every read in it, the battery's too, is back
        assertEquals(emptyList<String>(), paths.filter { it.startsWith("/status/batterystatus") })
        viewModel.setBetaEnabled(true)
        viewModel.loadTone(household.state.value.groups.first { it.name == "Kitchen" }.id)
        withTimeout(5_000) { while (paths.none { it.startsWith("/status/batterystatus") }) delay(20) }
    }

    /**
     * A room with a Sub and surrounds — Living Room in the captured zones — offers their levels with
     * its tone, and a press writes the Sub's; Kitchen, with neither, offers none.
     */
    @Test fun `the Sub and surrounds are offered where the room has them, and stepped`() = runBlocking<Unit> {
        fake.pushFixture("activeZonesChange")
        withTimeout(5_000) { household.state.first { it.bondedChannels.isNotEmpty() } }
        openKitchen()
        assertEquals("Kitchen has neither", null, viewModel.tone.value?.satellites)
        val living = household.state.value.groups.first { it.name == "Living Room" }
        viewModel.loadTone(living.id)
        val read = withTimeout(5_000) { viewModel.tone.first { it?.playerId == living.coordinatorId && it.satellites != null } }!!
        assertEquals(0, read.satellites?.subLevel)
        assertEquals(0, read.satellites?.tvLevel)
        viewModel.stepSubLevel(+1)
        viewModel.stepSubLevel(+1)
        assertEquals("shown at once", 2, viewModel.tone.value?.satellites?.subLevel)
        withTimeout(5_000) { while (eqWrites.isEmpty()) delay(20) }
        assertEquals(listOf("SubGain=2"), eqWrites.toList())
    }
}
