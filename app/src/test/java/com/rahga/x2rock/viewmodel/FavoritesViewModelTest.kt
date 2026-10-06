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
        viewModel = FavoritesViewModel(household, SavedStateHandle(mapOf("groupId" to groupId)))
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

    /** The captured history holds an Apple Music album, so this household's account is known. */
    @Test fun `Apple Music search is offered where the household has played from it`() = runBlocking<Unit> {
        val state = withTimeout(5_000) {
            viewModel.uiState.first { it is FavoritesViewModel.UiState.Success } as FavoritesViewModel.UiState.Success
        }
        assertTrue(state.appleMusic)
    }

    /** With nothing played from it known, a result would have no account to play through. */
    @Test fun `Apple Music search is not offered with no account to play through`() = runBlocking<Unit> {
        fake.refuse("getHistory", "ERROR_DISALLOWED_BY_POLICY")
        viewModel.reload()
        val state = withTimeout(5_000) {
            viewModel.uiState.first { (it as? FavoritesViewModel.UiState.Success)?.recentNote != null } as FavoritesViewModel.UiState.Success
        }
        assertTrue("offered with no account known", !state.appleMusic)
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
}
