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
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The full-screen view's room, against the fake household and its captured payloads. */
class NowPlayingViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    private lateinit var fake: FakePlayer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold
    private lateinit var viewModel: NowPlayingViewModel
    private lateinit var groupId: String

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
        groupId = fake.groupId(household)
        viewModel = NowPlayingViewModel(household, SavedStateHandle(mapOf("groupId" to groupId)))
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        fake.shutdown()
    }

    /** The captured album track: its name the headline, the pane's line under it. */
    @Test fun `a track is its title, and its artist and album under it`() = runBlocking<Unit> {
        fake.pushFixture("metadataStatus", groupId)
        val state = withTimeout(5_000) { viewModel.uiState.first { it.title == "Ironic (2015 remaster)" } }
        assertEquals("Alanis Morissette • Certified Bangers - 90s", state.subtitle)
        assertEquals(household.state.value.groups.first { it.id == groupId }.name, state.room)
    }

    /**
     * Opened on a room, then another controller groups a second room into it: the group id moves,
     * and the view must go on showing this room's music, read from the new group.
     */
    @Test fun `the view follows its room across a regroup`() = runBlocking<Unit> {
        val original = household.state.value.groups.first { it.id == groupId }
        val other = household.state.value.groups.first { it.id != groupId }
        fake.pushTopology(FakePlayer.groupedTopology(coordinatorRoom = original.name, memberRoom = other.name))
        val moved = withTimeout(5_000) {
            household.state.first { s -> s.groups.none { it.id == groupId } }
        }.groups.first { original.coordinatorId in it.playerIds }
        assertNotEquals("the fixture must mint a new group id", groupId, moved.id)

        fake.pushFixture("metadataStatus", moved.id)
        val state = withTimeout(5_000) { viewModel.uiState.first { it.title == "Ironic (2015 remaster)" } }
        assertEquals(moved.name, state.room)
    }
}
