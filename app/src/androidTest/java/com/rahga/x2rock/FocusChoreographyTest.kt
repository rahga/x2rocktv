package com.rahga.x2rock

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rahga.x2rock.lan.FakePlayer
import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.lan.connectTo
import com.rahga.x2rock.lan.fakeHousehold
import com.rahga.x2rock.store.BetaFeatures
import com.rahga.x2rock.store.PendingRoomDeepLink
import com.rahga.x2rock.store.PresetStore
import com.rahga.x2rock.store.RoomPreferencesStore
import com.rahga.x2rock.store.ThemeStore
import com.rahga.x2rock.ui.screens.HomeScreen
import com.rahga.x2rock.ui.screens.QueueScreen
import com.rahga.x2rock.ui.theme.X2RockTheme
import com.rahga.x2rock.viewmodel.HomeViewModel
import com.rahga.x2rock.viewmodel.PlayerViewModel
import com.rahga.x2rock.viewmodel.QueueViewModel
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
 * The focus paths in [HomeScreen] that have caused most of the month's regressions, on a device,
 * against the fake household the unit tests use: a TLS player in this process, replaying captured
 * payloads. Run on the emulator (`ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest`),
 * never on the household's TVs — the run installs and removes its own APKs.
 *
 * Keys go through Compose's focus system as the remote's would. The view models are built by hand
 * over the fake, as the JVM tests build them; nothing here needs Hilt.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class FocusChoreographyTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var fake: FakePlayer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold
    private lateinit var home: HomeViewModel
    private lateinit var player: PlayerViewModel

    private val prefs = TestPreferences()

    @Before fun setUp() = runBlocking {
        fake = FakePlayer().also { it.start() }
        scope = CoroutineScope(SupervisorJob())
        household = fakeHousehold(fake, scope)
        household.connectTo(fake, 10_000)
        // Every room playing the captured album, so each pane is the music pane with a transport.
        household.state.value.groups.forEach { group ->
            fake.pushFixture("metadataStatus", group.id)
            fake.pushFixture("playbackStatus", group.id)
        }
        buildViewModels()
    }

    private fun buildViewModels() {
        home = HomeViewModel(household, ThemeStore(prefs), RoomPreferencesStore(prefs), NoChannelSync, PendingRoomDeepLink(), PresetStore(prefs), BetaFeatures(prefs))
        player = PlayerViewModel(household, SilentNowPlaying, testClock, BetaFeatures(prefs))
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        fake.shutdown()
    }

    private fun showHome(onOpenQueue: (String) -> Unit = {}) = compose.setContent {
        X2RockTheme {
            HomeScreen(
                onOpenQueue = onOpenQueue, onOpenFavorites = {}, onOpenSearch = {}, onOpenNowPlaying = {},
                onOpenPreferredServices = {}, onOpenAlarms = {}, onOpenAbout = {}, onOpenReport = {},
                homeViewModel = home, playerViewModel = player,
            )
        }
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** Wait until whatever holds focus satisfies [matcher]: a room's state and focus both settle a frame or two late. */
    private fun waitForFocusOn(what: String, matcher: SemanticsMatcher) {
        compose.waitUntil(10_000) {
            compose.onAllNodes(isFocused() and (matcher or hasAnyDescendant(matcher))).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(isFocused()).assert(matcher or hasAnyDescendant(matcher), { "focus is not on $what" })
    }

    private val playPause = hasContentDescription("Play") or hasContentDescription("Pause")

    private fun awaitRoomList() = waitForFocusOn("the first room", hasText("Bedroom"))

    @Test fun rightFromARoomLandsOnPlayPause() {
        showHome()
        awaitRoomList()
        compose.waitUntil(10_000) { compose.onAllNodes(playPause).fetchSemanticsNodes().isNotEmpty() }
        press(Key.DirectionRight)
        waitForFocusOn("Play/Pause", playPause)
    }

    /** Shuffle is the transport row's leftmost control here; left from it is the room list, never lost. */
    @Test fun leftFromTheLeftmostControlReturnsToTheRoomList() {
        showHome()
        awaitRoomList()
        compose.waitUntil(10_000) { compose.onAllNodes(playPause).fetchSemanticsNodes().isNotEmpty() }
        press(Key.DirectionRight)
        waitForFocusOn("Play/Pause", playPause)
        press(Key.DirectionLeft)   // Previous
        press(Key.DirectionLeft)   // Shuffle, the leftmost
        waitForFocusOn("Shuffle", hasContentDescription("Shuffle, off") or hasContentDescription("Shuffle, on"))
        press(Key.DirectionLeft)
        waitForFocusOn("the room list", hasText("Bedroom"))
    }

    @Test fun closingTheRoomPanelReturnsFocusToTheGroupButton() {
        showHome()
        awaitRoomList()
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Group")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasContentDescription("Group")).requestFocus()
        waitForFocusOn("Group", hasContentDescription("Group"))
        press(Key.DirectionCenter)
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Play everywhere")).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitForFocusOn("Group", hasContentDescription("Group"))
    }

    /** Back from Queue lands on the Queue button, not on the room and not on the primary control. */
    @Test fun backFromQueueReturnsToTheQueueButton() {
        compose.setContent {
            val nav = rememberNavController()
            X2RockTheme {
                NavHost(nav, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            onOpenQueue = { id -> nav.navigate("queue/$id") }, onOpenFavorites = {}, onOpenSearch = {},
                            onOpenNowPlaying = {}, onOpenPreferredServices = {}, onOpenAlarms = {}, onOpenAbout = {}, onOpenReport = {},
                            homeViewModel = home, playerViewModel = player,
                        )
                    }
                    composable("queue/{groupId}") { entry ->
                        val groupId = entry.arguments?.getString("groupId").orEmpty()
                        QueueScreen(
                            room = "Bedroom",
                            onBack = { nav.popBackStack() },
                            playerViewModel = player,
                            viewModel = remember(groupId) { QueueViewModel(household, SavedStateHandle(mapOf("groupId" to groupId))) },
                        )
                    }
                }
            }
        }
        awaitRoomList()
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Queue")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasContentDescription("Queue")).requestFocus()
        waitForFocusOn("Queue", hasContentDescription("Queue"))
        press(Key.DirectionCenter)
        // On the queue screen once the home screen, and its Queue button, have gone. The screen's own
        // title depends on a UPnP read this fake does not answer, so it is not what is waited for.
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Queue")).fetchSemanticsNodes().isEmpty() }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitForFocusOn("Queue", hasContentDescription("Queue"))
    }

    /**
     * Focus that reaches a room without a key does not change the selection. At launch on the
     * Shield the pane's first control left the composition (its music pane gave way to the TV
     * pane), Compose dropped focus, the platform put it on the first row, and that row selected
     * Bedroom over the stated TV room, Living Room (2026-10-11, from its logcat). Forcing focus onto
     * a row here is that same arrival with no key behind it.
     */
    @Test fun focusArrivingWithoutAKeyKeepsTheStatedTvRoom() = runBlocking<Unit> {
        val living = household.state.value.groups.first { it.name == "Living Room" }
        RoomPreferencesStore(prefs).setTvPlayer(living.coordinatorId)
        buildViewModels()
        showHome()
        compose.waitUntil(10_000) { home.selectedGroupId.value == living.id }
        waitForFocusOn("Living Room's row", hasText("Living Room"))
        compose.onNode(isFocusable() and hasText("Bedroom", substring = true)).requestFocus()
        compose.waitForIdle()
        waitForFocusOn("Living Room's row, put back", hasText("Living Room"))
        org.junit.Assert.assertEquals("the TV room must stay selected", living.id, home.selectedGroupId.value)
    }

    /** A key still moves the selection: four Downs from Bedroom's row, the first, select Living Room. */
    @Test fun aKeyStillSelectsTheRoomItReaches() {
        showHome()
        awaitRoomList()
        repeat(4) { press(Key.DirectionDown) }
        waitForFocusOn("Living Room's row", hasText("Living Room"))
        compose.waitUntil(5_000) { home.selectedGroupId.value == household.state.value.groups.first { it.name == "Living Room" }.id }
    }
}
