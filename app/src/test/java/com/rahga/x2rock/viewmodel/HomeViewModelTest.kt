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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

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
        channels = RecordingChannelSync()
        viewModel = HomeViewModel(
            household = household,
            themeStore = ThemeStore(prefs),
            roomPrefsStore = RoomPreferencesStore(prefs),
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

    /** Picked once the household is known, honouring the stored primary room. */
    @Test fun `a room is selected on arrival`() = runBlocking<Unit> {
        connect()
        val selected = withTimeout(5_000) { viewModel.selectedGroupId.first { it != null } }
        assertNotNull(selected)
        assertTrue(household.state.value.groups.any { it.id == selected })
    }

    @Test fun `the stored primary room is selected ahead of the rest`() = runBlocking<Unit> {
        val preferred = FakePlayer.reachableTopology()
            .getAsJsonArray("groups").last().asJsonObject.get("id").asString
        RoomPreferencesStore(prefs).setPrimaryRoom(preferred)

        // A fresh view model, so it reads the preference on the way up.
        viewModel = HomeViewModel(
            household, ThemeStore(prefs), RoomPreferencesStore(prefs), channels, PendingRoomDeepLink(),
        )
        connect()
        assertEquals(preferred, withTimeout(5_000) { viewModel.selectedGroupId.first { it != null } })
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
        val host = sortGroups(state.groups, null, emptySet()).first()
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

    // ---------------------------------------------------------------- preferences

    @Test fun `theme and favourites survive a new store over the same storage`() {
        viewModel.setTheme(AppColorTheme.EMBER)
        viewModel.toggleFavorite("room-1")

        assertEquals(AppColorTheme.EMBER, ThemeStore(prefs).theme.value)
        assertEquals(setOf("room-1"), RoomPreferencesStore(prefs).favoriteRoomIds.value)
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
