package com.rahga.x2rock

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rahga.x2rock.lan.EMPTY_SOAP
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
import com.rahga.x2rock.ui.screens.HomeScreen
import com.rahga.x2rock.ui.theme.X2RockTheme
import com.rahga.x2rock.viewmodel.HomeViewModel
import com.rahga.x2rock.viewmodel.PlayerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress

/**
 * A ringing alarm's Snooze and Stop sit in the pane without pushing its last row off the screen
 * (PUNCH P-25): as a row of their own above the track they did, and the places row was cut at the
 * Shield's bottom edge until focus scrolled to it — 1114 of 1080px here, with the captured album's
 * two-line title. They take the places row's slot now, and are one press down from Play/Pause. On the emulator, as [FocusChoreographyTest] runs.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AlarmPaneTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var fake: FakePlayer
    private lateinit var upnp: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold
    private val prefs = TestPreferences()

    @Before fun setUp() = runBlocking<Unit> {
        fake = FakePlayer().also { it.start() }
        upnp = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val action = request.getHeader("SOAPACTION").orEmpty().substringAfter('#').trim('"')
                    // Kitchen's reply while an alarm rang (captured 2026-10-02); anything else answers empty.
                    return MockResponse().setBody(
                        if (action == "GetRunningAlarmProperties") FakePlayer.fixtureText("GetRunningAlarmProperties.ringing.xml") else EMPTY_SOAP
                    )
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        scope = CoroutineScope(SupervisorJob())
        household = fakeHousehold(fake, scope, addressBook = PlayerAddressBook(), upnpPort = upnp.port)
        household.connectTo(fake, 10_000)
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        fake.shutdown()
        upnp.shutdown()
    }

    @Test fun aRingingAlarmLeavesThePaneOnTheScreen() {
        val bedroom = household.state.value.groups.first { it.name == "Bedroom" }
        val home = HomeViewModel(household, ThemeStore(prefs), RoomPreferencesStore(prefs), NoChannelSync, PendingRoomDeepLink(), PresetStore(prefs), BetaFeatures(prefs), com.rahga.x2rock.store.AppleMusicSetting(prefs, household))
        val player = PlayerViewModel(household, SilentNowPlaying, testClock)
        compose.setContent {
            X2RockTheme {
                HomeScreen(
                    onOpenQueue = {}, onOpenFavorites = {}, onOpenSearch = {}, onOpenNowPlaying = {},
                    onOpenPreferredServices = {}, onOpenAlarms = {}, onOpenAbout = {}, onOpenReport = {},
                    homeViewModel = home, playerViewModel = player,
                )
            }
        }
        // The captured album, a two-line title, then playing: the start the household asks about.
        fake.pushFixture("metadataStatus", bedroom.id)
        fake.pushPlaybackStatus(bedroom.id, "PLAYBACK_STATE_PAUSED")
        compose.waitForIdle()
        fake.pushPlaybackStatus(bedroom.id, "PLAYBACK_STATE_PLAYING")
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Snooze", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        // Unclipped: `boundsInRoot` is cut to what shows, so a button half off the screen measured as
        // fitting. Its own position and size say where it really ends.
        val screen = compose.onRoot().fetchSemanticsNode().size.height
        // Whatever the lowest pressable thing on the screen is, all of it shows.
        val bottom = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().maxOf { it.positionInRoot.y + it.size.height }
        assertTrue("the pane's last row runs off the screen: $bottom > $screen", bottom <= screen)
        // Still in reach: from Play/Pause, Down gets to the alarm's buttons, in the places row's slot.
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        val alarmButton = hasText("Snooze", substring = true) or hasText("Stop")
        var ups = 0
        while (ups < 4 && compose.onAllNodes(isFocused() and (alarmButton or hasAnyDescendant(alarmButton))).fetchSemanticsNodes().isEmpty()) {
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            compose.waitForIdle()
            ups++
        }
        assertTrue("Down from the transport never reached Snooze or Stop", compose.onAllNodes(isFocused() and (alarmButton or hasAnyDescendant(alarmButton))).fetchSemanticsNodes().isNotEmpty())
    }
}
