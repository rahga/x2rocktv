package com.rahga.x2rock.viewmodel

import com.rahga.x2rock.auth.PendingRoomDeepLink
import com.rahga.x2rock.auth.RoomPreferencesStore
import com.rahga.x2rock.auth.ThemeStore
import com.rahga.x2rock.lan.Discovery
import com.google.gson.JsonObject
import com.rahga.x2rock.lan.FakePlayer
import com.rahga.x2rock.lan.LanHttp
import com.rahga.x2rock.lan.MulticastGate
import com.rahga.x2rock.lan.PlayerAddressBook
import com.rahga.x2rock.lan.SeedStore
import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.lan.TvSoundbar
import com.rahga.x2rock.model.AppColorTheme
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

private const val VOLUME_DEBOUNCE = 300L

/**
 * The room list, against a [FakePlayer] serving the captured five-group topology.
 *
 * Group changes are the interesting part here: they arrive as `groups:1` events rather than
 * as replies to the command that caused them, so nothing re-fetches, and every assertion
 * below is about state that arrived on its own.
 */
class HomeViewModelTest {

    private lateinit var fake: FakePlayer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold
    private lateinit var channels: RecordingChannelSync
    private lateinit var prefs: FakePreferences
    private lateinit var roomPrefs: RoomPreferencesStore
    private lateinit var viewModel: HomeViewModel

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
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
        prefs = FakePreferences()
        roomPrefs = RoomPreferencesStore(prefs)
        channels = RecordingChannelSync()
        viewModel = HomeViewModel(
            household = household,
            themeStore = ThemeStore(prefs),
            roomPrefsStore = roomPrefs,
            channelSync = channels,
            pendingRoomDeepLink = PendingRoomDeepLink(),
        )
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        fake.shutdown()
        Dispatchers.resetMain()
    }

    private fun connect() = runBlocking {
        household.connect(
            Discovery.DiscoveredPlayer(fake.id, InetAddress.getByName("127.0.0.1"), fake.householdId)
        )
        withTimeout(10_000) {
            viewModel.uiState.first { it is HomeViewModel.UiState.Success } as HomeViewModel.UiState.Success
        }
    }

    @Test fun `it is loading until the household answers`() {
        assertTrue(viewModel.uiState.value is HomeViewModel.UiState.Loading)
    }

    @Test fun `the room list comes from the household, not a fetch`() {
        val state = connect()
        assertEquals(5, state.groups.size)
        assertTrue(state.groups.any { it.name == "Dining Room" })
    }

    /** Picked once the household is known: alphabetically first, with nothing else set. */
    @Test fun `a room is selected on arrival`() = runBlocking<Unit> {
        connect()
        val selected = withTimeout(5_000) { viewModel.selectedGroupId.first { it != null } }
        assertNotNull(selected)
        assertTrue(household.state.value.groups.any { it.id == selected })
    }

    @Test fun `now playing follows the pushed metadata`() = runBlocking<Unit> {
        connect()
        val groupId = household.state.value.groups.first { it.coordinatorId == fake.id }.id
        fake.pushFixture("metadataStatus", groupId)

        val state = withTimeout(10_000) {
            viewModel.uiState.first {
                it is HomeViewModel.UiState.Success && it.nowPlaying[groupId] != null
            } as HomeViewModel.UiState.Success
        }
        assertNotNull(state.nowPlaying[groupId])
    }

    @Test fun `the TV home-screen channel follows the room list`() = runBlocking<Unit> {
        connect()
        withTimeout(5_000) {
            while (channels.synced.isEmpty()) delay(50)
        }
        assertEquals(5, channels.synced.last().first.size)
    }

    // ---------------------------------------------------------------- grouping

    /**
     * Party mode gathers every other group's players into the first. Asserted on what was
     * sent, because the result comes back as an event and the fake does not restructure
     * its topology.
     */
    @Test fun `party mode asks every other player to join the first group`() = runBlocking<Unit> {
        val state = connect()
        fake.clearHistory()
        viewModel.partyMode()

        val command = fake.awaitCommand(timeoutMillis = 5_000) {
            it.get("command")?.asString == "modifyGroupMembers"
        }
        val host = sortGroups(state.groups).first()
        assertEquals(host.id, command.get("groupId").asString)

        val added = fake.lastCommandBody("modifyGroupMembers")!!
            .getAsJsonArray("playerIdsToAdd").map { it.asString }
        val expected = state.groups.filter { it.id != host.id }.flatMap { it.playerIds }
        assertEquals(expected.toSet(), added.toSet())
        assertTrue("the host should not be asked to join itself", host.coordinatorId !in added)
    }

    /**
     * Solo removes the members and never the coordinator — that would dissolve the group.
     *
     * Every group in the captured topology has one player, so this first regroups two
     * rooms. Without that the early "nothing to remove" path always won and the test could
     * not fail.
     */
    @Test fun `solo removes every member except the coordinator`() = runBlocking<Unit> {
        connect()
        fake.pushTopology(FakePlayer.groupedTopology(coordinatorRoom = "Kitchen", memberRoom = "Guest TV"))
        val grouped = withTimeout(10_000) {
            viewModel.uiState.first {
                it is HomeViewModel.UiState.Success &&
                    it.groups.firstOrNull { g -> g.name == "Kitchen" }?.playerIds?.size == 2
            } as HomeViewModel.UiState.Success
        }
        val group = grouped.groups.first { it.name == "Kitchen" }
        fake.clearHistory()

        viewModel.soloGroup(group.id)
        fake.awaitCommand(timeoutMillis = 5_000) { it.get("command")?.asString == "modifyGroupMembers" }
        val body = fake.lastCommandBody("modifyGroupMembers")!!
        val removed = body.getAsJsonArray("playerIdsToRemove").map { it.asString }

        assertEquals("only the joined member should leave", 1, removed.size)
        assertTrue("the coordinator was removed from its own group", group.coordinatorId !in removed)
        assertTrue(
            "the member was not removed",
            group.playerIds.first { it != group.coordinatorId } in removed,
        )
    }

    /** A group of one has nothing to remove, so nothing is sent at all. */
    @Test fun `solo on an ungrouped room sends nothing`() = runBlocking<Unit> {
        val state = connect()
        val alone = state.groups.first { it.playerIds.size == 1 }
        fake.clearHistory()
        viewModel.soloGroup(alone.id)
        delay(600)
        assertEquals(0, fake.commandsNamed("modifyGroupMembers"))
    }

    /**
     * The panel parties from the room it was opened on, not from the top of the list.
     *
     * Party mode hinges on a source — the room whose music the house follows — so naming
     * the host is the whole point of moving it into the room panel. Asserted against a room
     * that [sortGroups] would *not* have picked, or the old behaviour would pass this too.
     */
    @Test fun `party mode hosts from the room it was asked for`() = runBlocking<Unit> {
        val state = connect()
        val sorted = sortGroups(state.groups)
        val host = sorted.last()
        assertTrue("the chosen host must differ from the default", host.id != sorted.first().id)
        fake.clearHistory()

        viewModel.partyMode(host.id)

        val command = fake.awaitCommand(timeoutMillis = 5_000) {
            it.get("command")?.asString == "modifyGroupMembers"
        }
        assertEquals(host.id, command.get("groupId").asString)
        val added = fake.lastCommandBody("modifyGroupMembers")!!
            .getAsJsonArray("playerIdsToAdd").map { it.asString }
        assertEquals(
            state.groups.filter { it.id != host.id }.flatMap { it.playerIds }.toSet(),
            added.toSet(),
        )
    }

    // ---------------------------------------------------------- member volumes

    private suspend fun awaitPlayerVolume(playerId: String, volume: Int) = withTimeout(5_000) {
        viewModel.playerVolumes.first { it[playerId] == volume }
    }

    @Test fun `a speaker's own level follows what the player pushed`() = runBlocking<Unit> {
        connect()
        fake.pushPlayerVolume(fake.id, volume = 37)
        awaitPlayerVolume(fake.id, 37)
    }

    /**
     * The same accumulation the group volume needed: the level is purely pushed, so several
     * presses inside the debounce window would otherwise all read the same unchanged value
     * and the speaker would move one step instead of four.
     */
    @Test fun `repeated presses on a member accumulate into one command`() = runBlocking<Unit> {
        connect()
        fake.pushPlayerVolume(fake.id, volume = 30)
        awaitPlayerVolume(fake.id, 30)
        fake.clearHistory()

        repeat(4) { viewModel.adjustPlayerVolume(fake.id, +5) }

        fake.awaitCommand(timeoutMillis = 5_000) { it.get("command")?.asString == "setVolume" }
        assertEquals(50, fake.lastCommandBody("setVolume")!!.get("volume").asInt)
        delay(600)
        assertEquals(1, fake.commandsNamed("setVolume"))
    }

    @Test fun `member presses are clamped to the usable range`() = runBlocking<Unit> {
        connect()
        fake.pushPlayerVolume(fake.id, volume = 96)
        awaitPlayerVolume(fake.id, 96)
        repeat(3) { viewModel.adjustPlayerVolume(fake.id, +5) }
        fake.awaitCommand(timeoutMillis = 5_000) { it.get("command")?.asString == "setVolume" }
        assertEquals(100, fake.lastCommandBody("setVolume")!!.get("volume").asInt)
    }

    /** Aiming from a level nobody has stated would aim from zero and turn the speaker down. */
    @Test fun `adjusting a member before its level is known sends nothing`() = runBlocking<Unit> {
        connect()
        fake.clearHistory()
        viewModel.adjustPlayerVolume(fake.id, +5)
        delay(600)
        assertEquals(0, fake.commandsNamed("setVolume"))
    }

    // -------------------------------------------------- naming the TV's soundbar

    /**
     * Stating it by hand stores the *soundbar*, not the coordinator and not the group.
     *
     * Grouped first so the two differ: a soundbar that joined a speaker's group is a member,
     * and storing the coordinator there would name a One SL as the television's. The group
     * id would be worse still — a regroup mints a new one.
     */
    @Test fun `naming the TV room stores the soundbar, not the coordinator`() = runBlocking<Unit> {
        connect()
        fake.pushTopology(FakePlayer.groupedTopology(coordinatorRoom = "Kitchen", memberRoom = "Bedroom"))
        val grouped = withTimeout(10_000) {
            viewModel.uiState.first {
                it is HomeViewModel.UiState.Success &&
                    it.groups.firstOrNull { g -> g.name == "Kitchen" }?.playerIds?.size == 2
            } as HomeViewModel.UiState.Success
        }
        val group = grouped.groups.first { it.name == "Kitchen" }

        viewModel.setTvSoundbar(group.id)

        val stored = viewModel.tvPlayerId.value
        assertTrue("nothing was stored", stored != null)
        assertTrue("the group id was stored instead of a player", stored != group.id)
        assertTrue(
            "the coordinator was stored, but it is the One SL with no HDMI",
            stored != group.coordinatorId,
        )
        assertEquals(TvSoundbar.soundbarOf(group, household.state.value), stored)
    }

    /**
     * King of the hill: naming a second room moves the crown rather than adding to it.
     *
     * That is the whole reason there is no un-naming row — a wrong answer is corrected by
     * naming the right room, not by clearing the wrong one first.
     */
    @Test fun `naming another room moves it off the first`() = runBlocking<Unit> {
        val state = connect()
        val withTv = state.groups.filter { state.rooms[it.id]?.hasTvInput == true }
        assertTrue("this test needs two rooms with soundbars", withTv.size >= 2)

        viewModel.setTvSoundbar(withTv[0].id)
        val first = viewModel.tvPlayerId.value
        assertNotNull(first)

        viewModel.setTvSoundbar(withTv[1].id)
        val second = viewModel.tvPlayerId.value

        assertTrue("the crown did not move", second != first)
        assertTrue("it did not land in the room that was named", second in withTv[1].playerIds)
    }

    /** A room with no soundbar has nothing to name, so the press does nothing. */
    @Test fun `a room with no soundbar cannot be named as the TV's`() = runBlocking<Unit> {
        val state = connect()
        val noSoundbar = state.groups.first { state.rooms[it.id]?.hasTvInput != true }
        viewModel.setTvSoundbar(noSoundbar.id)
        assertNull(viewModel.tvPlayerId.value)
    }

    /**
     * A press landing while the previous one's command is in flight must not lose a step.
     *
     * The newer press cancels the debounced job, and `runCatching` swallows the
     * `CancellationException` that throws inside it — so the clear that followed used to
     * delete the target the newer press had just written. The row fell back to the level the
     * speaker last stated, and the press after it aimed from there: 20 → 25 → 30 → **25**.
     *
     * The fake is told to hold its reply, because in-process it answers far too fast for
     * that window to be reachable by waiting.
     */
    @Test fun `a press during the previous command keeps accumulating`() = runBlocking<Unit> {
        connect()
        fake.pushPlayerVolume(fake.id, volume = 20)
        awaitPlayerVolume(fake.id, 20)
        fake.clearHistory()
        fake.holdRepliesTo("setVolume")

        viewModel.adjustPlayerVolume(fake.id, +5)                        // 25
        fake.awaitCommand(timeoutMillis = 5_000) { it.get("command")?.asString == "setVolume" }
        // Now suspended inside that command, which is the only place the bug lives.
        viewModel.adjustPlayerVolume(fake.id, +5)                        // 30, cancels it
        delay(60)

        assertEquals(
            "the cancelled job cleared the newer press's target",
            30,
            viewModel.playerVolumes.value[fake.id],
        )

        // And the press after it must aim from 30, not from the 20 the speaker stated.
        viewModel.adjustPlayerVolume(fake.id, +5)                        // 35
        fake.releaseReplies()
        withTimeout(5_000) {
            while (fake.lastCommandBody("setVolume")?.get("volume")?.asInt != 35) delay(20)
        }
    }

    // ---------------------------------------------------------------- TV input

    /**
     * The switch goes over UPnP, which the fake does not speak, so what is checked here is
     * the part that can be got wrong without hardware: which speaker is named. It must be
     * the one holding the HDMI socket, which need not be the coordinator.
     */
    @Test fun `a room with no soundbar cannot be switched to a TV input`() = runBlocking<Unit> {
        val state = connect()
        val noSoundbar = state.groups.first { !(state.rooms[it.id]?.hasTvInput ?: false) }
        val thrown = runCatching { household.useTvInput(noSoundbar.id) }.exceptionOrNull()
        assertTrue(
            "expected a refusal naming the room, got $thrown",
            thrown is IllegalStateException && thrown.message!!.contains(noSoundbar.name),
        )
    }

    // ---------------------------------------------------------------- preferences

    @Test fun `preferences survive a new store over the same storage`() {
        viewModel.setTheme(AppColorTheme.EMBER)
        roomPrefs.setTvPlayer("RINCON_TEST")

        assertEquals(AppColorTheme.EMBER, ThemeStore(prefs).theme.value)
        assertEquals("RINCON_TEST", RoomPreferencesStore(prefs).tvPlayerId.value)
    }

    @Test fun `losing the household surfaces an error`() = runBlocking<Unit> {
        connect()
        fake.dropConnection()
        val state = withTimeout(20_000) {
            viewModel.uiState.first { it is HomeViewModel.UiState.Error }
        }
        assertTrue(state is HomeViewModel.UiState.Error)
    }
}
