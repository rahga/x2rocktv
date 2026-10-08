package com.rahga.x2rock.viewmodel

import com.rahga.x2rock.lan.FakePlayer
import com.rahga.x2rock.lan.LanHttp
import com.rahga.x2rock.lan.MulticastGate
import com.rahga.x2rock.lan.PlayerAddressBook
import com.rahga.x2rock.lan.SeedStore
import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.model.RepeatModes
import com.rahga.x2rock.model.isPlaying
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The view model against a real socket to a [FakePlayer], rather than a stubbed repository.
 *
 * Every bug found in this class so far was found by running the app on a television and
 * looking at it — the volume that read 0 before the speaker had said anything, the presses
 * that collapsed to one step, the error that never surfaced. Those are the cases here,
 * because they are the ones that actually happened.
 */
class PlayerViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()


    private lateinit var fake: FakePlayer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold
    private lateinit var publisher: RecordingNowPlaying
    private lateinit var viewModel: PlayerViewModel
    private lateinit var groupId: String

    @Before fun setUp() {
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
        viewModel = PlayerViewModel(household, publisher, testClock)

        runBlocking {
            household.connect(
                fake.seed
            )
            withTimeout(5_000) { household.state.first { it.connected } }
        }
        groupId = fake.groupId(household)
        viewModel.selectGroup(groupId, "Test Room")
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        fake.shutdown()
    }

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
     * The old regression was a Vol+ press in this window aiming at 0 + delta and turning the
     * speaker *down*, because the target was computed from a level that had not arrived. A
     * relative step has no baseline to get wrong: it moves the speaker by the step.
     */
    @Test fun `a step before a volume is known moves it by the step`() = runBlocking<Unit> {
        viewModel.adjustVolume(+5)
        fake.awaitCommand("setRelativeVolume", 3_000)
        assertEquals(5, sentDelta())
        assertEquals("no absolute level may be sent from a guess", 0, fake.commandsNamed("setVolume"))
    }

    @Test fun `muting before a volume is known sends nothing`() = runBlocking<Unit> {
        viewModel.toggleMute()
        delay(600)
        assertEquals(0, fake.commandsNamed("setMute"))
    }

    // ---------------------------------------------------------------- accumulation

    /**
     * Regression: `uiState` is purely pushed, so several presses inside the debounce window
     * all read the same unchanged volume and the speaker moved one step instead of five.
     */
    @Test fun `repeated presses accumulate into one command`() = runBlocking<Unit> {
        fake.pushGroupVolume(groupId, 30)
        awaitVolume(30)
        fake.clearHistory()

        repeat(4) { viewModel.adjustVolume(+5) }

        val command = fake.awaitCommand(timeoutMillis = 3_000) {
            it.get("command")?.asString == "setRelativeVolume"
        }
        assertEquals(groupId, command.get("groupId").asString)
        // Four steps, not one.
        assertEquals(20, sentDelta())
        // And one command, not four: the debounce still collapses them. Counted from the
        // fake's own log, because awaitCommand above consumed the queue entry.
        delay(600)
        assertEquals(1, fake.commandsNamed("setRelativeVolume"))
    }

    /**
     * A fixed line-out's level belongs to the amplifier it feeds, so a step is not sent and
     * the reason is said. No capture of one exists here — this household has no Port or Amp
     * set that way — so the event is the captured groupVolume shape with `fixed` true.
     */
    @Test fun `a step on a fixed volume sends nothing and says why`() = runBlocking<Unit> {
        fake.pushGroupVolume(groupId, 100, fixed = true)
        withTimeout(5_000) { viewModel.uiState.first { it.volumeFixed } }
        fake.clearHistory()
        viewModel.adjustVolume(+5)
        val state = withTimeout(5_000) { viewModel.uiState.first { it.notice != null } }
        assertEquals(FIXED_VOLUME, state.notice)
        delay(600)
        assertEquals(0, fake.commandsNamed("setRelativeVolume"))
    }

    /** Muted is not a reason to refuse a step: the player unmutes on either setter. */
    @Test fun `a step on a muted room is sent`() = runBlocking<Unit> {
        fake.pushGroupVolume(groupId, 30, muted = true)
        withTimeout(5_000) { viewModel.uiState.first { it.isMuted } }
        viewModel.adjustVolume(-5)
        fake.awaitCommand("setRelativeVolume", 3_000)
        assertEquals(-5, sentDelta())
    }

    private fun sentDelta(): Int? = fake.lastCommandBody("setRelativeVolume")?.get("volumeDelta")?.asInt


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
        fake.awaitCommand("setPlayModes", 3_000)
        val modes = fake.lastCommandBody("setPlayModes")!!.getAsJsonObject("playModes")
        assertTrue("shuffle should have been turned on", modes.get("shuffle").asBoolean)
        // The repeat the speaker reported must survive a shuffle toggle.
        assertTrue("repeat was cleared by toggling shuffle", modes.get("repeat").asBoolean)
    }

    // ---------------------------------------------------------------- going back

    /**
     * The captured status is a queue's first track: `canSkipToPrevious` false, `canSeek` true —
     * what shuffle reports on every track. Prev there goes back to the start of the track, as
     * the Sonos app's does, rather than asking for a previous track the player says is not
     * there.
     */
    @Test fun `going back with no previous track restarts this one`() = runBlocking<Unit> {
        fake.pushPlaybackStatus(groupId, "PLAYBACK_STATE_PLAYING")
        withTimeout(5_000) { viewModel.uiState.first { it.actions.canSeek && !it.actions.canSkipToPrevious } }
        assertTrue("Prev would not be drawn", viewModel.uiState.value.actions.canGoBack)
        fake.clearHistory()

        viewModel.skipToPreviousTrack()

        fake.awaitCommand("seek", 3_000)
        assertEquals(0, fake.lastCommandBody("seek")!!.get("positionMillis").asInt)
        assertEquals(0, fake.commandsNamed("skipToPreviousTrack"))
    }

    /**
     * A second press before the speaker has pushed where the first one landed aims from the first
     * one's target, not from the position it pushed before it. The target used to be dropped as
     * soon as the command was sent, and the push that moves the position comes later, so a press
     * in between went back to the old position (outside review, 2026-10-08).
     */
    @Test fun `a second seek before the new position is pushed aims from the first`() = runBlocking<Unit> {
        fake.pushPlaybackStatus(groupId, "PLAYBACK_STATE_PAUSED")
        withTimeout(5_000) { viewModel.uiState.first { it.playbackState == "PLAYBACK_STATE_PAUSED" && it.actions.canSeek } }
        val start = viewModel.uiState.value.positionMillis
        fake.clearHistory()

        viewModel.seekBy(10_000)
        fake.awaitCommand("seek", 3_000)
        // Sent, and nothing pushed since: the speaker has not said where it is now.
        kotlinx.coroutines.delay(100)
        fake.clearHistory()
        viewModel.seekBy(10_000)

        fake.awaitCommand("seek", 3_000)
        assertEquals(start + 20_000, fake.lastCommandBody("seek")!!.get("positionMillis").asLong)
    }

    // ---------------------------------------------------------------- publishing

    @Test fun `metadata reaches the system when a track arrives`() = runBlocking<Unit> {
        fake.pushFixture("metadataStatus", groupId)
        withTimeout(5_000) { viewModel.uiState.first { it.trackName != null } }
        assertTrue("nothing was published", publisher.metadata.isNotEmpty())
        assertEquals(viewModel.uiState.value.trackName, publisher.metadata.last().title)
        // And its cover, for the home screen's media card, which drew a blank without one.
        assertNotNull(viewModel.uiState.value.albumArtUrl)
        assertEquals(viewModel.uiState.value.albumArtUrl, publisher.metadata.last().artUrl)
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

    /**
     * Regression, found on the television: a soundbar playing TV audio carries no track
     * metadata, and the session was reporting STATE_NONE for it. The system then treats the
     * session as inactive, so media keys and voice transport do nothing — for precisely the
     * room most likely to be in use.
     */
    @Test fun `a playing room with no track still reports playing`() = runBlocking<Unit> {
        fake.push(
            "playback:1", "playbackStatus",
            """{"playbackState":"PLAYBACK_STATE_PLAYING"}""",
            groupId,
        )
        withTimeout(5_000) { viewModel.uiState.first { it.playbackState.isPlaying() } }
        withTimeout(5_000) {
            while (publisher.states.isEmpty()) delay(20)
        }
        val published = publisher.states.last()
        assertTrue("a playing room was published as not playing", published.playing)
        assertFalse("a playing room with no title was published as idle", published.idle)
    }

    /**
     * A soundbar on its HDMI input is not media this app is playing, so it advertises no
     * session at all.
     *
     * The Google TV home screen carries a card for every active session, and for a room with
     * no track it read "Unknown · x2rock · Unknown" — a row on the launcher saying nothing,
     * for a source the television already controls.
     */
    @Test fun `a room on its TV input advertises no session`() = runBlocking<Unit> {
        assertTrue("should start out presenting", publisher.presenting)
        fake.pushFixture("tvMetadataStatus", groupId)
        withTimeout(5_000) {
            viewModel.uiState.first { it.onTvInput }
        }
        withTimeout(5_000) {
            while (publisher.presenting) delay(20)
        }
    }

    /**
     * Idle is a different case and must keep its session: a play key, or a voice "play in the
     * living room", has to land somewhere, and idle is exactly when one is worth acting on.
     */
    @Test fun `an idle room still advertises a session`() = runBlocking<Unit> {
        fake.push("playback:1", "playbackStatus", """{"playbackState":"PLAYBACK_STATE_IDLE"}""", groupId)
        withTimeout(5_000) { viewModel.uiState.first { !it.isLoading } }
        delay(300)
        assertTrue("an idle room dropped its session", publisher.presenting)
    }

    /** And the title is never blank, which is what the launcher rendered as "Unknown". */
    @Test fun `a room with no track publishes its own name, not an empty title`() = runBlocking<Unit> {
        fake.push("playback:1", "playbackStatus", """{"playbackState":"PLAYBACK_STATE_IDLE"}""", groupId)
        withTimeout(5_000) {
            while (publisher.metadata.isEmpty()) delay(20)
        }
        val published = publisher.metadata.last()
        // The room's name: the one it was selected under, or the fixture's own once the topology
        // lands and renames it. Which of the two is last published is timing, not behaviour.
        assertTrue("title was ${published.title}", published.title in setOf("Test Room", "Dining Room"))
        assertTrue("the subtitle was blank too", !published.artist.isNullOrBlank())
        assertFalse("the transport's word, not the room's", published.artist == "Idle")
    }

    // ---------------------------------------------------------------- media session actions

    /**
     * A live station refuses skip, previous and seek, and the session must not offer them:
     * the launcher's media card and a voice "next" act on what it advertises. The captured
     * radio status is a SomaFM stream with `canSkip`, `canSkipToPrevious` and `canSeek` false.
     */
    @Test fun `the media session offers only what the source permits`() = runBlocking<Unit> {
        fake.pushFixture("radioPlaybackStatus", groupId)
        withTimeout(5_000) { viewModel.uiState.first { it.isRadio || !it.actions.canSkip } }
        val published = withTimeout(5_000) {
            while (publisher.states.lastOrNull()?.actions?.canSkip != false) delay(20)
            publisher.states.last().actions
        }
        assertFalse("a station was offered a skip", published.canSkip)
        assertFalse(published.canSkipToPrevious)
        assertFalse("a station with no duration was offered a seek", published.canSeek)
    }

    // ---------------------------------------------------------------- failed commands

    @Test fun `a refused command says so on the pane`() = runBlocking<Unit> {
        fake.refuse("togglePlayPause")
        viewModel.togglePlayPause()
        val state = withTimeout(5_000) { viewModel.uiState.first { it.notice != null } }
        assertEquals("Couldn't play or pause: ERROR_COMMAND_FAILED", state.notice)
    }

    /**
     * A newer press cancels the volume send still in flight, and `runCatching` catches that
     * cancellation too. It is not a failure; reported, a held key would flash an error on
     * every repeat. The reply is held so the cancellation lands mid-command, the one place
     * `runCatching` sees it.
     */
    @Test fun `a volume send cancelled by a newer press says nothing`() = runBlocking<Unit> {
        fake.pushGroupVolume(groupId, 20)
        awaitVolume(20)
        fake.holdRepliesTo("setRelativeVolume")
        viewModel.adjustVolume(+5)
        fake.awaitCommand("setRelativeVolume", 5_000)
        viewModel.adjustVolume(+5)
        fake.releaseReplies()
        fake.awaitCommand("setRelativeVolume", 5_000)
        delay(500)
        assertNull("a cancelled send was reported as a failure", viewModel.uiState.value.notice)
        // The first step had already gone when it was cancelled, so the second sends its own.
        assertEquals("a step was sent twice", 5, sentDelta())
    }

    /** UPnP off takes the queue away and puts the reason on the pane; on again restores it. */
    @Test fun `the pane follows the household's UPnP switch`() = runBlocking<Unit> {
        fake.setUpnpAllowed(false)
        withTimeout(5_000) { viewModel.uiState.first { it.upnpOff } }
        fake.setUpnpAllowed(true)
        withTimeout(5_000) { viewModel.uiState.first { !it.upnpOff } }
    }

    // ---------------------------------------------------------------- playback errors

    @Test fun `a room that failed to play says why on the pane`() = runBlocking<Unit> {
        fake.pushFixture("playbackError", groupId)
        val state = withTimeout(5_000) { viewModel.uiState.first { it.playbackError != null } }
        assertEquals("Couldn't play this: found nothing it could play", state.playbackError)
    }

    // ---------------------------------------------------------------- ratings

    /**
     * Nothing loaded at all is the deterministic case to test without a real music-service
     * endpoint to talk to (there is no `FakePlayer` for UPnP/SMAPI, unlike the socket):
     * `household.rate` fails before it ever leaves the LAN, and the point here is only that
     * the failure reaches the UI as a message rather than vanishing into a swallowed
     * exception the way the other transport commands' `runCatching` would let it.
     */
    @Test fun `a failed rating attempt surfaces a message rather than nothing`() = runBlocking<Unit> {
        viewModel.rateUp()
        val state = withTimeout(5_000) { viewModel.uiState.first { it.notice != null } }
        assertEquals("nothing rateable is playing in this room", state.notice)
    }

    // ---------------------------------------------------------------- failure

    /**
     * Regression: the error was carried only on the branch that has group state, which a
     * teardown clears — so a dead household spun forever instead of saying so.
     */
    @Test fun `losing the household surfaces an error rather than a spinner`() = runBlocking<Unit> {
        fake.pushGroupVolume(groupId, 20)
        awaitVolume(20)

        fake.dropConnection()
        val state = withTimeout(20_000) { viewModel.uiState.first { it.error != null } }
        assertFalse("still claiming to be loading", state.isLoading)
        assertNotNull(state.error)
    }
}
