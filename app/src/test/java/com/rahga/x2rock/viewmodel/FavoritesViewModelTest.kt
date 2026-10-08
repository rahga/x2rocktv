package com.rahga.x2rock.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.rahga.x2rock.lan.FakePlayer
import com.rahga.x2rock.lan.connectTo
import com.rahga.x2rock.lan.LanHttp
import com.rahga.x2rock.lan.MulticastGate
import com.rahga.x2rock.lan.PlayerAddressBook
import com.rahga.x2rock.lan.SeedStore
import com.rahga.x2rock.lan.SonosHousehold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.rahga.x2rock.auth.PendingRoomDeepLink
import com.rahga.x2rock.store.Preset
import com.rahga.x2rock.store.PresetStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class FavoritesViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()


    private lateinit var fake: FakePlayer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold
    private lateinit var viewModel: FavoritesViewModel
    private lateinit var presets: PresetStore
    private val roomLink = PendingRoomDeepLink()

    @Before fun setUp() = runBlocking {
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
        household.connectTo(fake, 10_000)
        val groupId = fake.groupId(household)
        presets = PresetStore(FakePreferences())
        viewModel = FavoritesViewModel(household, SavedStateHandle(mapOf("groupId" to groupId)), presets, roomLink)
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        fake.shutdown()
    }

    /**
     * Two real favourites and one derived shell — a real album with its service and type
     * stripped, which is what a removed service leaves. Only the shell goes.
     */
    @Test fun `a favourite the household can no longer play is not listed`() = runBlocking<Unit> {
        val state = withTimeout(5_000) {
            viewModel.uiState.first { it is FavoritesViewModel.UiState.Success } as FavoritesViewModel.UiState.Success
        }
        assertEquals(listOf("Love Songs Radio", "Ryo Fukui in New York"), state.items.map { it.name })
    }

    @Test fun `playlists are listed after the favourites`() = runBlocking<Unit> {
        val state = withTimeout(5_000) {
            viewModel.uiState.first { it is FavoritesViewModel.UiState.Success } as FavoritesViewModel.UiState.Success
        }
        assertEquals(listOf("x2rock capture"), state.playlists.map { it.name })
    }

    /** The office One SL's history: eight entries, two of them streams that share a name. */
    @Test fun `recently played is listed, each stream its own row`() = runBlocking<Unit> {
        val state = withTimeout(5_000) {
            viewModel.uiState.first { it is FavoritesViewModel.UiState.Success } as FavoritesViewModel.UiState.Success
        }
        assertEquals(8, state.recent.size)
        assertEquals(2, state.recent.count { it.name == "The Main Mix" })
        assertEquals("Mellow Mix", state.recent.first().name)
    }

    /** With Personalization off the household refuses the read, and the screen says why. */
    @Test fun `history switched off is said, not shown as nothing`() = runBlocking<Unit> {
        fake.refuse("getHistory", "ERROR_DISALLOWED_BY_POLICY")
        viewModel.reload()
        val state = withTimeout(5_000) {
            viewModel.uiState.first { (it as? FavoritesViewModel.UiState.Success)?.recentNote != null } as FavoritesViewModel.UiState.Success
        }
        assertEquals(HISTORY_OFF, state.recentNote)
        assertTrue(state.recent.isEmpty())
    }

    @Test fun `a playlist that fails to load stays on the list and says why`() = runBlocking<Unit> {
        fake.refuse("loadPlaylist")
        var wentBack = false
        viewModel.loadPlaylist("6") { wentBack = true }
        val notice = withTimeout(5_000) { viewModel.notice.first { it != null } }
        assertEquals("Couldn't play that playlist: ERROR_COMMAND_FAILED", notice)
        assertFalse(wentBack)
    }

    /**
     * A favourite that would not load used to send the viewer back to the room anyway, with
     * nothing playing and no word why. Now the list stays, and says.
     */
    @Test fun `a favourite that fails to load stays on the list and says why`() = runBlocking<Unit> {
        fake.refuse("loadFavorite")
        var wentBack = false
        viewModel.loadFavorite("7") { wentBack = true }
        val notice = withTimeout(5_000) { viewModel.notice.first { it != null } }
        assertEquals("Couldn't play that favourite: ERROR_COMMAND_FAILED", notice)
        assertFalse("a failed load must not leave the list", wentBack)
    }

    /**
     * Levels before music, so nothing starts loud; then the favourite into the group the rooms now
     * form; then to that room. One room already alone needs no regroup, so this is the order alone.
     */
    @Test fun `a preset sets its levels before it starts its favourite`() = runBlocking<Unit> {
        val leader = fake.id
        val preset = Preset("p", "Kitchen · Love Songs Radio", listOf(leader), mapOf(leader to 12), "84")
        presets.add(preset)
        fake.clearHistory()
        var done = false

        viewModel.applyPreset(preset) { done = true }

        // In sequence: had the favourite gone first, waiting for the level would consume it and the
        // wait for the favourite after would find nothing.
        fake.awaitCommand("setVolume", 5_000)
        fake.awaitCommand("loadFavorite", 5_000)
        assertEquals(12, fake.lastCommandBody("setVolume")!!.get("volume").asInt)
        assertEquals("84", fake.lastCommandBody("loadFavorite")!!.get("favoriteId").asString)
        withTimeout(5_000) { while (!done) kotlinx.coroutines.delay(20) }
        assertEquals("then to the room it forms", leader, roomLink.roomId.value)
    }

    /** Menu twice: the first press only arms, so one stray press never loses a preset. */
    @Test fun `a preset is deleted on the second press, not the first`() {
        val preset = Preset("p", "Kitchen", listOf(fake.id), emptyMap())
        presets.add(preset)
        viewModel.deletePreset(preset)
        assertEquals(1, presets.presets.value.size)
        viewModel.deletePreset(preset)
        assertEquals(0, presets.presets.value.size)
    }

    /**
     * A joining room plays the group's music the moment it joins, at whatever level it was left on —
     * so a preset sets every level before it regroups. Waiting in sequence proves the order: had the
     * regroup gone first, waiting for both levels would have consumed it.
     */
    @Test fun `a preset sets levels before it regroups`() = runBlocking<Unit> {
        val groups = household.state.value.groups
        val kitchen = groups.first { it.name == "Kitchen" }.coordinatorId
        val guest = groups.first { it.name == "Guest TV" }.coordinatorId
        val preset = Preset("p2", "Kitchen + Guest TV", listOf(kitchen, guest), mapOf(kitchen to 12, guest to 8))
        fake.clearHistory()

        viewModel.applyPreset(preset) {}

        fake.awaitCommand("setVolume", 5_000)
        fake.awaitCommand("setVolume", 5_000)
        val regroup = fake.awaitCommand("modifyGroupMembers", 5_000)
        assertEquals(groups.first { it.name == "Kitchen" }.id, regroup.get("groupId").asString)
        fake.pushTopology(com.rahga.x2rock.lan.FakePlayer.groupedTopology(coordinatorRoom = "Kitchen", memberRoom = "Guest TV"))
    }

    @Test fun `Browse opened before a regroup still plays into the same room`() = runBlocking<Unit> {
        val original = household.state.value.groups.first { it.id == viewModel.groupId }
        val member = household.state.value.groups.first { it.id != original.id }
        val topology = FakePlayer.groupedTopology(original.name, member.name)
        fake.pushTopology(topology)
        withTimeout(5_000) { household.state.first { s -> s.groups.none { it.id == original.id } } }
        val changed = household.state.value.groups.first { original.coordinatorId in it.playerIds }
        assertTrue("fixture must replace the target group id", original.id != changed.id)
        val finished = kotlinx.coroutines.CompletableDeferred<Unit>()
        viewModel.loadFavorite("1") { finished.complete(Unit) }
        kotlinx.coroutines.withTimeoutOrNull(1_000) { finished.await() }
        assertTrue("saved target became invalid after regroup: ${viewModel.notice.value}", finished.isCompleted)
        assertEquals(changed.id, fake.awaitCommand("loadFavorite").get("groupId").asString)
    }

}
