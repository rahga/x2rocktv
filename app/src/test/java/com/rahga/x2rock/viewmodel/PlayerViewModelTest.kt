package com.rahga.x2rock.viewmodel

import com.rahga.x2rock.lan.Discovery
import com.rahga.x2rock.lan.FakePlayer
import com.rahga.x2rock.lan.LanHttp
import com.rahga.x2rock.lan.MulticastGate
import com.rahga.x2rock.lan.PlayerAddressBook
import com.rahga.x2rock.lan.SeedStore
import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.model.RepeatModes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

/**
 * The view model against a real socket to a [FakePlayer], rather than a stubbed repository.
 *
 * Every bug found in this class so far was found by running the app on a television and
 * looking at it — the volume that read 0 before the speaker had said anything, the presses
 * that collapsed to one step, the error that never surfaced. Those are the cases here,
 * because they are the ones that actually happened.
 */
class PlayerViewModelTest {

    private lateinit var fake: FakePlayer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold
    private lateinit var publisher: RecordingNowPlaying
    private lateinit var viewModel: PlayerViewModel
    private lateinit var groupId: String

    @Before fun setUp() {
        // ViewModel coroutines need a main dispatcher; the household keeps its own scope.
        kotlinx.coroutines.Dispatchers.setMain(Dispatchers.Unconfined)
        fake = FakePlayer().also { it.start() }
        scope = CoroutineScope(SupervisorJob())
        val book = PlayerAddressBook()
        household = SonosHousehold(
            scope = scope,
            addressBook = book,
            multicast = MulticastGate.None,
            client = LanHttp.client(book),
            seeds = SeedStore.None,
            port = fake.port,
        )
        publisher = RecordingNowPlaying()
        viewModel = PlayerViewModel(household, publisher)

        runBlocking {
            household.connect(
                Discovery.DiscoveredPlayer(fake.id, InetAddress.getByName("127.0.0.1"), fake.householdId)
            )
            withTimeout(5_000) { household.state.first { it.connected } }
        }
        groupId = household.state.value.groups.first { it.coordinatorId == fake.id }.id
        viewModel.selectGroup(groupId, "Test Room")
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        fake.shutdown()
        Dispatchers.resetMain()
    }

    private fun pushVolume(volume: Int) = fake.push(
        "groupVolume:1", "groupVolume",
        """{"volume":$volume,"muted":false,"fixed":false}""",
        groupId,
    )

    private suspend fun awaitVolume(volume: Int) = withTimeout(5_000) {
        viewModel.uiState.first { it.volume == volume }
    }

    // ---------------------------------------------------------------- known volume

    /**
     * Regression: volume defaulted to 0, so the app claimed a speaker sitting at 12 was at
     * zero for the moment before its first `groupVolume` snapshot.
     */
    @Test fun `volume is null until the speaker has said, not zero`() {
        assertNull("volume was reported before the speaker said anything", viewModel.uiState.value.volume)
    }

    /**
     * Worse than the wrong label: `adjustVolume` derived its target from that 0, so a
     * Vol+ press in that window aimed at 0 + delta and turned the speaker *down*.
     */
    @Test fun `adjusting before a volume is known sends nothing`() = runBlocking<Unit> {
        viewModel.adjustVolume(+5)
        delay(600)
        val sent = fake.received.any { it.get("command")?.asString == "setVolume" }
        assertFalse("a volume was set with no baseline to set it from", sent)
    }

    @Test fun `muting before a volume is known sends nothing`() = runBlocking<Unit> {
        viewModel.toggleMute()
        delay(600)
        assertFalse(fake.received.any { it.get("command")?.asString == "setMute" })
    }

    // ---------------------------------------------------------------- accumulation

    /**
     * Regression: `uiState` is purely pushed, so several presses inside the debounce window
     * all read the same unchanged volume and the speaker moved one step instead of five.
     */
    @Test fun `repeated presses accumulate into one command`() = runBlocking<Unit> {
        pushVolume(30)
        awaitVolume(30)
        fake.clearHistory()

        repeat(4) { viewModel.adjustVolume(+5) }

        val command = fake.awaitCommand(timeoutMillis = 3_000) {
            it.get("command")?.asString == "setVolume"
        }
        assertEquals(groupId, command.get("groupId").asString)
        // 30 + 5 + 5 + 5 + 5, not 30 + 5.
        assertEquals(50, sentVolume())
        // And one command, not four: the debounce still collapses them. Counted from the
        // fake's own log, because awaitCommand above consumed the queue entry.
        delay(600)
        assertEquals(1, fake.commandsNamed("setVolume"))
    }

    @Test fun `presses are clamped to the usable range`() = runBlocking<Unit> {
        pushVolume(98)
        awaitVolume(98)
        repeat(3) { viewModel.adjustVolume(+5) }
        fake.awaitCommand(timeoutMillis = 3_000) { it.get("command")?.asString == "setVolume" }
        assertEquals(100, sentVolume())
    }

    private fun sentVolume(): Int? = fake.lastCommandBody("setVolume")?.get("volume")?.asInt

    // ---------------------------------------------------------------- play modes

    /** Toggling derives from the pushed state, so it must reflect what the speaker said. */
    @Test fun `toggling shuffle sends every flag, based on current state`() = runBlocking<Unit> {
        fake.push(
            "playback:1", "playbackStatus",
            """{"playbackState":"PLAYBACK_STATE_PLAYING","playModes":{"repeat":true,"repeatOne":false,"shuffle":false,"crossfade":false}}""",
            groupId,
        )
        withTimeout(5_000) { viewModel.uiState.first { it.repeat == RepeatModes.ALL } }
        fake.clearHistory()

        viewModel.toggleShuffle()
        fake.awaitCommand(timeoutMillis = 3_000) { it.get("command")?.asString == "setPlayModes" }
        val modes = fake.lastCommandBody("setPlayModes")!!.getAsJsonObject("playModes")
        assertTrue("shuffle should have been turned on", modes.get("shuffle").asBoolean)
        // The repeat the speaker reported must survive a shuffle toggle.
        assertTrue("repeat was cleared by toggling shuffle", modes.get("repeat").asBoolean)
    }

    // ---------------------------------------------------------------- publishing

    @Test fun `metadata reaches the system when a track arrives`() = runBlocking<Unit> {
        fake.pushFixture("metadataStatus", groupId)
        withTimeout(5_000) { viewModel.uiState.first { it.trackName != null } }
        assertTrue("nothing was published", publisher.metadata.isNotEmpty())
        assertEquals(viewModel.uiState.value.trackName, publisher.metadata.last().title)
    }

    /** Media keys and voice transport arrive here; they must reach the speaker. */
    @Test fun `a transport control from the system reaches the player`() = runBlocking<Unit> {
        assertNotNull("nothing attached to the publisher", publisher.controls)
        publisher.controls!!.next()
        val command = fake.awaitCommand(timeoutMillis = 3_000) {
            it.get("command")?.asString == "skipToNextTrack"
        }
        assertEquals(groupId, command.get("groupId").asString)
    }

    // ---------------------------------------------------------------- failure

    /**
     * Regression: the error was carried only on the branch that has group state, which a
     * teardown clears — so a dead household spun forever instead of saying so.
     */
    @Test fun `losing the household surfaces an error rather than a spinner`() = runBlocking<Unit> {
        pushVolume(20)
        awaitVolume(20)

        fake.dropConnection()
        val state = withTimeout(20_000) { viewModel.uiState.first { it.error != null } }
        assertFalse("still claiming to be loading", state.isLoading)
        assertNotNull(state.error)
    }
}
