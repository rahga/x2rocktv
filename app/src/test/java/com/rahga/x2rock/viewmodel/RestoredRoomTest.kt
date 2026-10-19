package com.rahga.x2rock.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.rahga.x2rock.lan.FakePlayer
import com.rahga.x2rock.lan.PlayerAddressBook
import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.lan.connectTo
import com.rahga.x2rock.lan.fakeHousehold
import com.rahga.x2rock.store.BetaFeatures
import com.rahga.x2rock.store.PendingRoomDeepLink
import com.rahga.x2rock.store.PresetStore
import com.rahga.x2rock.store.RoomPreferencesStore
import com.rahga.x2rock.store.ThemeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

/**
 * The shared player and the home screen's selection after Android reclaimed the app with a room's
 * screen in front (P-51). Only the home screen ever named the player's room, and it is not composed
 * when Queue is restored: the restored screen had no now-playing bar, no room in its header, no room
 * for the media keys, and Back opened on the default room. A fresh view model given only the saved
 * state stands in for the restore, with the room regrouped meanwhile, so its group id is gone.
 */
class RestoredRoomTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val fake = FakePlayer().also { it.start() }
    private val scope = CoroutineScope(SupervisorJob())
    private val households = mutableListOf<SonosHousehold>()

    private fun household() = fakeHousehold(fake, scope, addressBook = PlayerAddressBook()).also { households += it }

    @After fun tearDown() {
        households.forEach { it.disconnect() }
        scope.cancel()
        fake.shutdown()
    }

    /** What survives the process: the saved keys, copied into the handle Android would rebuild. */
    private fun restored(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })

    private fun kitchen(household: SonosHousehold) = household.state.value.groups.first { it.name == "Kitchen" }

    /** Regroup Kitchen into Guest TV, so Kitchen's group id is gone; the group now holding it. */
    private suspend fun regroup(household: SonosHousehold, kitchenId: String): String {
        val coordinator = kitchen(household).coordinatorId
        fake.pushTopology(FakePlayer.groupedTopology(coordinatorRoom = "Guest TV", memberRoom = "Kitchen"))
        return withTimeout(5_000) {
            household.state.first { s -> s.groups.none { it.id == kitchenId } }
        }.groups.first { coordinator in it.playerIds }.id
    }

    private fun homeViewModel(household: SonosHousehold, saved: SavedStateHandle): HomeViewModel {
        val prefs = FakePreferences()
        return HomeViewModel(
            household, ThemeStore(prefs), RoomPreferencesStore(prefs), RecordingChannelSync(), PendingRoomDeepLink(),
            PresetStore(prefs), BetaFeatures(prefs), com.rahga.x2rock.store.AppleMusicSetting(prefs, household), saved,
        )
    }

    @Test fun `a restored player finds its room with nothing selecting it`() = runBlocking<Unit> {
        val saved = SavedStateHandle()
        val before = household()
        before.connectTo(fake)
        val kitchenId = kitchen(before).id
        PlayerViewModel(before, RecordingNowPlaying(), testClock, saved).selectGroup(kitchenId, "Kitchen")
        before.disconnect()

        val after = household()
        val player = PlayerViewModel(after, RecordingNowPlaying(), testClock, restored(saved))
        after.connectTo(fake)
        val now = regroup(after, kitchenId)
        withTimeout(5_000) { player.uiState.first { it.groupName.isNotEmpty() } }
        fake.clearHistory()

        player.adjustVolume(+5)

        val sent = fake.awaitCommand(timeoutMillis = 5_000) { it.get("namespace")?.asString == "groupVolume:1" }
        assertEquals("the media keys and the bar act on the room the screen was for", now, sent.get("groupId").asString)
    }

    @Test fun `a restored home selection is the room left, not the default`() = runBlocking<Unit> {
        val saved = SavedStateHandle()
        val before = household()
        val first = homeViewModel(before, saved)
        before.connectTo(fake)
        val kitchenId = kitchen(before).id
        assertNotEquals("Kitchen must not be the default, or this proves nothing",
            sortGroups(before.state.value.groups).first().id, kitchenId)
        first.selectGroup(kitchenId)
        withTimeout(5_000) { first.selectedGroupId.first { it == kitchenId } }
        before.disconnect()

        val after = household()
        val home = homeViewModel(after, restored(saved))
        after.connectTo(fake)
        val now = regroup(after, kitchenId)

        withTimeout(5_000) { home.selectedGroupId.first { it == now } }
    }
}
