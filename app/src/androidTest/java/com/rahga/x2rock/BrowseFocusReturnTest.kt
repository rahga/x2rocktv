package com.rahga.x2rock

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.Text
import com.rahga.x2rock.lan.FakePlayer
import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.lan.connectTo
import com.rahga.x2rock.lan.fakeHousehold
import com.rahga.x2rock.store.PendingRoomDeepLink
import com.rahga.x2rock.store.Preset
import com.rahga.x2rock.store.PresetStore
import com.rahga.x2rock.ui.screens.FavoritesScreen
import com.rahga.x2rock.ui.theme.X2RockTheme
import com.rahga.x2rock.viewmodel.FavoritesViewModel
import com.rahga.x2rock.viewmodel.PlayerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Back to Browse lands on the row that opened another screen, even one the list has to scroll to
 * (PUNCH P-02): a row not composed could not take focus, and focus fell to the first row. The list's
 * scroll is restored on its own, so it bites when rows above it change while Browse is away; ten
 * presets added then push a captured favourite out of view. On the emulator, as
 * [FocusChoreographyTest] runs.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class BrowseFocusReturnTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var fake: FakePlayer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold
    private val prefs = TestPreferences()

    @Before fun setUp() = runBlocking {
        fake = FakePlayer().also { it.start() }
        scope = CoroutineScope(SupervisorJob())
        household = fakeHousehold(fake, scope)
        household.connectTo(fake, 10_000)
        val presets = PresetStore(prefs)
        val player = household.state.value.groups.first().coordinatorId
        (1..15).forEach { presets.add(Preset("p$it", "Preset $it", listOf(player), mapOf(player to 5))) }
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        fake.shutdown()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun focusedOn(text: String) = hasText(text, substring = true).let { m ->
        compose.onAllNodes(isFocused() and (m or hasAnyDescendant(m))).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun backReturnsToARowBelowTheFold() {
        lateinit var nav: NavHostController
        val groupId = household.state.value.groups.first().id
        compose.setContent {
            nav = rememberNavController()
            X2RockTheme {
                NavHost(nav, startDestination = "browse") {
                    composable("browse") {
                        FavoritesScreen(
                            room = "Kitchen", onBack = {}, onOpenRadio = {}, onOpenServices = {}, onOpenFavorite = { _, _, _, _ -> },
                            playerViewModel = remember { PlayerViewModel(household, SilentNowPlaying, testClock) },
                            viewModel = remember {
                                FavoritesViewModel(household, SavedStateHandle(mapOf("groupId" to groupId)), PresetStore(prefs), PendingRoomDeepLink())
                            },
                        )
                    }
                    composable("elsewhere") { Text("Elsewhere") }
                }
            }
        }
        compose.waitUntil(10_000) { focusedOn("Preset 1") }
        // Down past the fifteen presets to a favourite off the first screen when it began.
        var guard = 0
        val trail = mutableListOf<String>()
        while (!focusedOn("Love Songs Radio") && guard++ < 25) {
            press(Key.DirectionDown)
            trail += compose.onAllNodes(isFocused()).fetchSemanticsNodes().joinToString { n ->
                n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString("|").orEmpty().take(40)
            }
        }
        org.junit.Assert.assertTrue("focus went: $trail", focusedOn("Love Songs Radio"))
        compose.runOnUiThread { nav.navigate("elsewhere") }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Elsewhere")).fetchSemanticsNodes().isNotEmpty() }
        // Rows added above it while away: the restored scroll position no longer shows it, which is
        // when a request for its focus failed (a preset saved from a room panel meanwhile, say).
        val player = household.state.value.groups.first().coordinatorId
        PresetStore(prefs).let { store -> (16..25).forEach { store.add(Preset("p$it", "Preset $it", listOf(player), mapOf(player to 5))) } }
        compose.runOnUiThread { nav.popBackStack() }
        compose.waitUntil(10_000) { focusedOn("Love Songs Radio") }
    }
}
