package com.rahga.x2rock.viewmodel

import com.rahga.x2rock.store.BetaFeatures
import com.rahga.x2rock.store.PendingRoomDeepLink
import com.rahga.x2rock.store.RoomPreferencesStore
import com.rahga.x2rock.store.ThemeStore
import com.google.gson.JsonObject
import com.rahga.x2rock.lan.FakePlayer
import com.rahga.x2rock.lan.PlayerAddressBook
import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.lan.TvSoundbar
import com.rahga.x2rock.lan.fakeHousehold
import com.rahga.x2rock.model.AppColorTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.rahga.x2rock.store.PresetStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

private const val VOLUME_DEBOUNCE = 300L

/**
 * The room list, against a [FakePlayer] serving the captured five-group topology.
 *
 * Group changes are the interesting part here: they arrive as `groups:1` events rather than
 * as replies to the command that caused them, so nothing re-fetches, and every assertion
 * below is about state that arrived on its own.
 */
class HomeViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()


    private lateinit var fake: FakePlayer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold
    private lateinit var channels: RecordingChannelSync
    private lateinit var prefs: FakePreferences
    private lateinit var roomPrefs: RoomPreferencesStore
    private lateinit var viewModel: HomeViewModel
    private val deepLink = PendingRoomDeepLink()

    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        scope = CoroutineScope(SupervisorJob())
        val book = PlayerAddressBook()
        household = fakeHousehold(fake, scope, addressBook = book)
        prefs = FakePreferences()
        roomPrefs = RoomPreferencesStore(prefs)
        channels = RecordingChannelSync()
        viewModel = HomeViewModel(
            household = household,
            themeStore = ThemeStore(prefs),
            roomPrefsStore = roomPrefs,
            channelSync = channels,
            pendingRoomDeepLink = deepLink,
            presetStore = PresetStore(prefs),
            betaFeatures = BetaFeatures(prefs),
            appleMusic = com.rahga.x2rock.store.AppleMusicSetting(prefs, household),
        )
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        fake.shutdown()
    }

    private fun connect() = runBlocking {
        household.connect(
            fake.seed
        )
        withTimeout(10_000) {
            viewModel.uiState.first { it is HomeViewModel.UiState.Success } as HomeViewModel.UiState.Success
        }
    }

    /** The captured topology, with [playerId] reporting a line-in: the capability from Sonos's list. */
    private fun withLineIn(playerId: String) = FakePlayer.reachableTopology().also { topology ->
        topology.getAsJsonArray("players").map { it.asJsonObject }
            .first { it.get("id").asString == playerId }
            .getAsJsonArray("capabilities").add(com.rahga.x2rock.lan.LINE_IN)
    }

    private suspend fun roomsOnceALineInShows() = withTimeout(5_000) {
        viewModel.uiState.first { (it as? HomeViewModel.UiState.Success)?.rooms?.values?.any { r -> r.lineIns.isNotEmpty() } == true }
            as HomeViewModel.UiState.Success
    }.rooms

    /**
     * With Beta features on, every room is offered Kitchen's line-in: Kitchen as its own, the rest as
     * another room's (T-21). Untested on hardware: the captured topology with `LINE_IN` added.
     */
    @Test fun `with beta features on, every room is offered the line-in, its own first`() = runBlocking<Unit> {
        val state = connect()
        val kitchen = state.groups.first { it.name == "Kitchen" }
        viewModel.setBetaEnabled(true)
        fake.pushTopology(withLineIn(kitchen.coordinatorId))
        val rooms = roomsOnceALineInShows()
        assertEquals(listOf(HomeViewModel.LineIn(kitchen.coordinatorId, "Kitchen", own = true)), rooms.getValue(kitchen.id).lineIns)
        val other = state.groups.first { it.id != kitchen.id }
        assertEquals(listOf(HomeViewModel.LineIn(kitchen.coordinatorId, "Kitchen", own = false)), rooms.getValue(other.id).lineIns)
    }

    /** Off, the default: no room offers a line-in, though a player has one, until the switch is turned on. */
    @Test fun `with beta features off, no room offers a line-in`() = runBlocking<Unit> {
        val state = connect()
        val kitchen = state.groups.first { it.name == "Kitchen" }
        fake.pushTopology(withLineIn(kitchen.coordinatorId))
        withTimeout(5_000) { household.state.first { s -> s.lineInPlayers.isNotEmpty() } }
        // Held for a second rather than read once: one read could land before the topology does.
        repeat(10) {
            kotlinx.coroutines.delay(100)
            val rooms = (viewModel.uiState.value as HomeViewModel.UiState.Success).rooms
            assertEquals(emptyList<HomeViewModel.LineIn>(), rooms.values.flatMap { it.lineIns })
        }
        // The same household, the switch turned on: now it shows, so the absence above was the switch.
        viewModel.setBetaEnabled(true)
        roomsOnceALineInShows()
    }

    /**
     * A device that has been told its television's room opens on it, whatever sorts first. The
     * Shield, with Living Room stored, opened on Bedroom every launch (2026-10-11).
     */
    @Test fun `launch selects the stated TV room, not the first`() = runBlocking<Unit> {
        val kitchenPlayer = FakePlayer.reachableTopology().getAsJsonArray("groups").map { it.asJsonObject }
            .first { it.get("name").asString == "Kitchen" }.get("coordinatorId").asString
        roomPrefs.setTvPlayer(kitchenPlayer)
        val state = connect()
        val kitchen = state.groups.first { it.name == "Kitchen" }
        withTimeout(5_000) { viewModel.selectedGroupId.first { it != null } }
        kotlinx.coroutines.delay(1_000)
        assertEquals(kitchen.id, viewModel.selectedGroupId.value)
    }

    // ---------------------------------------------------------------- channel tiles

    /**
     * A tile opened from a cold start: the link arrives before any room is known. It used to
     * select the raw id at once, which was in no list yet, so the default room replaced it.
     */
    @Test fun `a tile opened from a cold start selects its room`() = runBlocking<Unit> {
        // Not Bedroom: it sorts first, so it is also the room the old code fell back to, and a
        // test asking for it passed against the bug.
        val kitchen = FakePlayer.reachableTopology().getAsJsonArray("groups")
            .map { it.asJsonObject }.first { it.get("name").asString == "Kitchen" }
        deepLink.set(kitchen.get("coordinatorId").asString)
        connect()
        withTimeout(5_000) { viewModel.navigateToRoom.first { it } }
        assertEquals(kitchen.get("id").asString, viewModel.selectedGroupId.value)
    }

    /**
     * Guest TV's tile names its speaker. After Guest TV joins Kitchen, that speaker is a member
     * of a group with a new id and a different coordinator — and the tile must still find it.
     */
    @Test fun `a tile finds its speaker in whatever group holds it now`() = runBlocking<Unit> {
        val state = connect()
        val guestSpeaker = state.groups.first { it.name == "Guest TV" }.coordinatorId
        fake.pushTopology(FakePlayer.groupedTopology(coordinatorRoom = "Kitchen", memberRoom = "Guest TV"))
        val joined = withTimeout(5_000) {
            household.state.first { s -> s.groups.any { guestSpeaker in it.playerIds && it.coordinatorId != guestSpeaker } }
        }.groups.first { guestSpeaker in it.playerIds }

        deepLink.set(guestSpeaker)
        withTimeout(5_000) { viewModel.selectedGroupId.first { it == joined.id } }
    }

    /** A tile published before tiles were keyed by player carries a group id; it still opens. */
    @Test fun `a tile carrying a group id still opens its room`() = runBlocking<Unit> {
        val state = connect()
        val kitchen = state.groups.first { it.name == "Kitchen" }
        deepLink.set(kitchen.id)
        withTimeout(5_000) { viewModel.selectedGroupId.first { it == kitchen.id } }
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
        assertEquals(sortGroups(household.state.value.groups).first().id, selected)
    }

    /**
     * A split follows the coordinator, not any shared speaker: Guest TV lists before Kitchen in
     * the captured household, so "any speaker" sent the selection to Guest TV's fragment.
     */
    @Test fun `a selection follows its coordinator when the group splits`() = runBlocking<Unit> {
        connect()
        fake.pushTopology(FakePlayer.groupedTopology(coordinatorRoom = "Kitchen", memberRoom = "Guest TV"))
        val grouped = withTimeout(5_000) {
            viewModel.uiState.first { it is HomeViewModel.UiState.Success && it.groups.size == 4 } as HomeViewModel.UiState.Success
        }.groups.first { it.name == "Kitchen" }
        viewModel.selectGroup(grouped.id)
        withTimeout(5_000) { viewModel.selectedGroupId.first { it == grouped.id } }

        fake.pushTopology(FakePlayer.reachableTopology())
        val split = withTimeout(5_000) {
            viewModel.uiState.first { it is HomeViewModel.UiState.Success && it.groups.size == 5 } as HomeViewModel.UiState.Success
        }
        val kitchen = split.groups.first { it.name == "Kitchen" }
        withTimeout(5_000) { viewModel.selectedGroupId.first { it != grouped.id } }
        assertEquals(kitchen.id, viewModel.selectedGroupId.value)
    }

    @Test fun `now playing follows the pushed metadata`() = runBlocking<Unit> {
        connect()
        val groupId = fake.groupId(household)
        fake.pushFixture("metadataStatus", groupId)

        val state = withTimeout(10_000) {
            viewModel.uiState.first {
                it is HomeViewModel.UiState.Success && it.rooms[groupId]?.track != null
            } as HomeViewModel.UiState.Success
        }
        assertNotNull(state.rooms[groupId]?.track)
    }

    @Test fun `the TV home-screen channel follows the room list`() = runBlocking<Unit> {
        connect()
        withTimeout(5_000) {
            while (channels.synced.isEmpty()) delay(50)
        }
        assertEquals(5, channels.synced.last().first.size)
    }

    /**
     * Bedroom on iHeartRadio had a blank tile on the Shield's home screen: the tile took the
     * track's art, and a station sends its logo on the container with none on the track. The
     * capture is that station. The tile now shows what the room list does.
     */
    @Test fun `a station's tile shows its logo, not nothing`() = runBlocking<Unit> {
        connect()
        val groupId = fake.groupId(household)
        fake.pushFixture("stationMetadataStatus", groupId)
        val tile = withTimeout(5_000) {
            while (channels.synced.lastOrNull()?.second?.get(groupId)?.subtitle == null) delay(50)
            channels.synced.last().second.getValue(groupId)
        }
        assertEquals("You're Still The One", tile.subtitle)
        assertEquals("https://i.iheart.com/v3/re/assets.streams/67b0d43da19ce5cf02ee9dba", tile.artUrl)
    }

    // ---------------------------------------------------------------- grouping

    /** The captured zones event, Bedroom's surround unplugged: Bedroom's row says so, no other. */
    @Test fun `a room with a bonded speaker offline is marked, and only that room`() = runBlocking<Unit> {
        val state = connect()
        fake.pushFixture("activeZonesChange")
        val rooms = withTimeout(5_000) {
            viewModel.uiState.first { s -> (s as? HomeViewModel.UiState.Success)?.rooms?.values?.any { it.offlineSpeakers > 0 } == true }
                as HomeViewModel.UiState.Success
        }.rooms
        val bedroom = state.groups.first { it.name == "Bedroom" }.id
        assertEquals(mapOf(bedroom to 1), rooms.filterValues { it.offlineSpeakers > 0 }.mapValues { it.value.offlineSpeakers })
    }

    /**
     * The panel's Sound rows come from the captured settings. With no UPnP to answer here,
     * TruePlay reads as unknown, and the panel leaves its row out rather than guess.
     */
    @Test fun `the panel reads its room's tone`() = runBlocking<Unit> {
        val state = connect()
        val kitchen = state.groups.first { it.name == "Kitchen" }
        viewModel.loadTone(kitchen.id)
        val tone = withTimeout(5_000) { viewModel.tone.first { it != null } }!!
        assertEquals(kitchen.coordinatorId, tone.playerId)
        assertEquals(Triple(0, 0, false), Triple(tone.bass, tone.treble, tone.loudness))
        assertNull(tone.trueplay)
    }

    /** A muted group's mute reaches the room list, pushed. */
    @Test fun `a muted group is shown as muted`() = runBlocking<Unit> {
        val state = connect()
        val kitchen = state.groups.first { it.name == "Kitchen" }
        fake.pushGroupVolume(kitchen.id, 30, muted = true)
        withTimeout(5_000) {
            viewModel.uiState.first { (it as? HomeViewModel.UiState.Success)?.rooms?.get(kitchen.id)?.muted == true }
        }
    }

    /** The panel stays open while grouping, so a failed join has to be said, not swallowed. */
    @Test fun `a refused join says so`() = runBlocking<Unit> {
        val state = connect()
        fake.refuse("modifyGroupMembers")
        val kitchen = state.groups.first { it.name == "Kitchen" }
        val guest = state.groups.first { it.name == "Guest TV" }
        viewModel.joinGroup(guest.id, kitchen.id)
        val notice = withTimeout(5_000) { viewModel.notice.first { it != null } }
        assertEquals("Couldn't add Guest TV: ERROR_COMMAND_FAILED", notice)
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
        fake.awaitCommand("modifyGroupMembers", 5_000)
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

    /**
     * Party with the volume evened: every speaker is set to the host room's level, and all of it
     * before any room joins, so nothing joins loud. Without the evening no level is sent at all.
     */
    @Test fun `party mode can even every speaker to the host's level first`() = runBlocking<Unit> {
        val state = connect()
        val host = state.groups.first { it.name == "Kitchen" }
        val speakers = state.groups.flatMap { it.playerIds }
        fake.pushGroupVolume(host.id, 12)
        withTimeout(5_000) { household.groupStates.first { it[host.id]?.volume?.volume == 12 } }

        fake.clearHistory()
        viewModel.partyMode(host.id)
        fake.awaitCommand(timeoutMillis = 5_000) { it.get("command")?.asString == "modifyGroupMembers" }
        assertEquals("a plain party set a level", 0, fake.commandsNamed("setVolume"))

        fake.clearHistory()
        viewModel.partyMode(host.id, evenVolume = true)
        fake.awaitCommand(timeoutMillis = 5_000) { it.get("command")?.asString == "modifyGroupMembers" }
        assertEquals("not every speaker was set before the rooms joined", speakers.size, fake.commandsNamed("setVolume"))
        assertEquals(12, fake.lastCommandBody("setVolume")!!.get("volume").asInt)
    }

    /**
     * A speaker whose level could not be set does not join: it would play at once at whatever level
     * it was left on, which is what evening out was for. The rest join, and the notice names it (P-40).
     */
    @Test fun `a party leaves out a speaker whose volume was refused, and says so`() = runBlocking<Unit> {
        val state = connect()
        val host = state.groups.first { it.name == "Kitchen" }
        val refusing = state.groups.first { it.name == "Guest TV" }.coordinatorId
        fake.pushGroupVolume(host.id, 12)
        withTimeout(5_000) { household.groupStates.first { it[host.id]?.volume?.volume == 12 } }
        fake.refuseFor("setVolume", refusing)
        fake.clearHistory()

        viewModel.partyMode(host.id, evenVolume = true)

        fake.awaitCommand(timeoutMillis = 10_000) { it.get("command")?.asString == "modifyGroupMembers" }
        val added = fake.lastCommandBody("modifyGroupMembers")!!.getAsJsonArray("playerIdsToAdd").map { it.asString }
        assertFalse("the refusing speaker joined", refusing in added)
        assertTrue("the others still joined", added.isNotEmpty())
        val said = withTimeout(5_000) { viewModel.notice.first { it != null } }!!
        assertTrue(said, said.startsWith("Left out Guest TV"))
    }

    /** Every joiner refused: nobody joins, and the notice says so. */
    @Test fun `a party with every joiner's volume refused joins nobody`() = runBlocking<Unit> {
        val state = connect()
        val host = state.groups.first { it.name == "Kitchen" }
        fake.pushGroupVolume(host.id, 12)
        withTimeout(5_000) { household.groupStates.first { it[host.id]?.volume?.volume == 12 } }
        state.groups.filter { it.id != host.id }.flatMap { it.playerIds }.forEach { fake.refuseFor("setVolume", it) }
        fake.clearHistory()

        viewModel.partyMode(host.id, evenVolume = true)

        val said = withTimeout(10_000) { viewModel.notice.first { it != null } }!!
        assertTrue(said, said.endsWith("so no room joined"))
        assertEquals("a room joined", 0, fake.commandsNamed("modifyGroupMembers"))
    }

    /** No reply is not "set": a speaker that never answers about its level does not join either. */
    @Test fun `a party leaves out a speaker that never answers about its volume`() = runBlocking<Unit> {
        val state = connect()
        val host = state.groups.first { it.name == "Kitchen" }
        fake.pushGroupVolume(host.id, 12)
        withTimeout(5_000) { household.groupStates.first { it[host.id]?.volume?.volume == 12 } }
        fake.holdRepliesTo("setVolume")
        fake.clearHistory()

        viewModel.partyMode(host.id, evenVolume = true)

        val said = withTimeout(15_000) { viewModel.notice.first { it != null } }!!
        fake.releaseReplies()
        assertTrue(said, said.endsWith("so no room joined"))
        assertEquals("a room joined", 0, fake.commandsNamed("modifyGroupMembers"))
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

        val stored = roomPrefs.tvPlayerId.value
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
        val first = roomPrefs.tvPlayerId.value
        assertNotNull(first)

        viewModel.setTvSoundbar(withTv[1].id)
        val second = roomPrefs.tvPlayerId.value

        assertTrue("the crown did not move", second != first)
        assertTrue("it did not land in the room that was named", second in withTv[1].playerIds)
    }

    /**
     * The row that wears this device's TV mark is the named room's, and only it: naming another
     * moves the mark, which is the one sign a press of "This is my TV" took (T-33).
     */
    @Test fun `the TV room's row is marked, and the mark follows the naming`() = runBlocking<Unit> {
        val state = connect()
        val withTv = state.groups.filter { state.rooms[it.id]?.hasTvInput == true }
        assertTrue("this test needs two rooms with soundbars", withTv.size >= 2)

        for (named in withTv.take(2)) {
            viewModel.setTvSoundbar(named.id)
            val marked = withTimeout(5_000) {
                viewModel.uiState.map { s ->
                    (s as? HomeViewModel.UiState.Success)?.rooms?.filterValues { it.isThisTv }?.keys
                }.first { it == setOf(named.id) }
            }
            assertEquals(setOf(named.id), marked)
        }
    }

    /** A room with no soundbar has nothing to name, so the press does nothing. */
    @Test fun `a room with no soundbar cannot be named as the TV's`() = runBlocking<Unit> {
        val state = connect()
        val noSoundbar = state.groups.first { state.rooms[it.id]?.hasTvInput != true }
        viewModel.setTvSoundbar(noSoundbar.id)
        assertNull(roomPrefs.tvPlayerId.value)
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
        fake.clearHistory()
        viewModel.useTvInput(noSoundbar.id)
        val said = withTimeout(5_000) { viewModel.notice.first { it != null } }!!
        assertTrue("expected a refusal naming the room, got $said", said.startsWith("Couldn't switch to the TV") && noSoundbar.name in said)
    }

    // ---------------------------------------------------------------- preferences

    @Test fun `preferences survive a new store over the same storage`() {
        viewModel.setTheme(AppColorTheme.EMBER)
        roomPrefs.setTvPlayer("RINCON_TEST")

        assertEquals(AppColorTheme.EMBER, ThemeStore(prefs).theme.value)
        assertEquals("RINCON_TEST", RoomPreferencesStore(prefs).tvPlayerId.value)
    }

    /** The room order is this device's: placed once, it is still the order after a restart. */
    @Test fun `the room order survives a new store, and starts empty`() {
        assertEquals(emptyList<String>(), viewModel.roomRanks.value)
        roomPrefs.setRoomRanks(listOf("RINCON_A", "RINCON_B"))
        assertEquals(listOf("RINCON_A", "RINCON_B"), RoomPreferencesStore(prefs).roomRanks.value)
        viewModel.resetRoomOrder()
        assertEquals(emptyList<String>(), RoomPreferencesStore(prefs).roomRanks.value)
    }

    @Test fun `losing the household surfaces an error`() = runBlocking<Unit> {
        connect()
        fake.dropConnection()
        val state = withTimeout(20_000) {
            viewModel.uiState.first { it is HomeViewModel.UiState.Error }
        }
        assertTrue(state is HomeViewModel.UiState.Error)
        // In the viewer's words, not the exception's (P-56).
        val shown = (state as HomeViewModel.UiState.Error).message
        assertTrue(shown, shown.startsWith("Couldn't reach the Sonos speakers."))
    }

    // ---------------------------------------------------------------- presets

    /**
     * The room's speakers and levels, and the favourite it plays — matched by the container's name,
     * as Browse marks a favourite as playing. Love Songs Radio is both a captured favourite and the
     * captured station event's container, so this match is between two real payloads.
     */
    @Test fun `a preset keeps the room, its levels and the favourite it plays`() = runBlocking<Unit> {
        val state = connect()
        val kitchen = state.groups.first { it.name == "Kitchen" }
        fake.pushFixture("stationMetadataStatus", kitchen.id)
        fake.pushPlayerVolume(kitchen.coordinatorId, 17)
        withTimeout(5_000) {
            household.groupStates.first { it[kitchen.id]?.container?.name == "Love Songs Radio" }
            household.playerVolumes.first { it[kitchen.coordinatorId]?.volume == 17 }
        }

        viewModel.savePreset(kitchen.id)

        val saved = withTimeout(5_000) { viewModel.notice.first { it != null } }
        val preset = PresetStore(prefs).presets.value.single()
        assertEquals("Saved \"Kitchen · Love Songs Radio\" to Browse", saved)
        assertEquals(listOf(kitchen.coordinatorId), preset.playerIds)
        assertEquals(mapOf(kitchen.coordinatorId to 17), preset.volumes)
        assertEquals("84", preset.favoriteId)
    }

    /** Music that is not a favourite is not kept, and the notice says so. */
    @Test fun `a preset of a room playing no favourite keeps rooms and levels only`() = runBlocking<Unit> {
        val state = connect()
        val kitchen = state.groups.first { it.name == "Kitchen" }
        fake.pushFixture("metadataStatus", kitchen.id)
        withTimeout(5_000) { household.groupStates.first { it[kitchen.id]?.container?.name == "Certified Bangers - 90s" } }

        viewModel.savePreset(kitchen.id)

        val saved = withTimeout(5_000) { viewModel.notice.first { it != null } }
        assertTrue(saved, saved!!.contains("rooms and levels only"))
        assertNull(PresetStore(prefs).presets.value.single().favoriteId)
    }
}
