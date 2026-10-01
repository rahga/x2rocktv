package com.rahga.x2rock.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.rahga.x2rock.lan.Discovery
import com.rahga.x2rock.lan.FakePlayer
import com.rahga.x2rock.lan.LanHttp
import com.rahga.x2rock.lan.MulticastGate
import com.rahga.x2rock.lan.PlayerAddressBook
import com.rahga.x2rock.lan.SeedStore
import com.rahga.x2rock.lan.SonosHousehold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

class FavoritesViewModelTest {

    private lateinit var fake: FakePlayer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold
    private lateinit var viewModel: FavoritesViewModel

    @Before fun setUp() = runBlocking {
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
        household.connect(Discovery.DiscoveredPlayer(fake.id, InetAddress.getByName("127.0.0.1"), fake.householdId))
        withTimeout(10_000) { household.state.first { it.connected } }
        val groupId = household.state.value.groups.first { it.coordinatorId == fake.id }.id
        viewModel = FavoritesViewModel(household, SavedStateHandle(mapOf("groupId" to groupId)))
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        fake.shutdown()
        Dispatchers.resetMain()
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
