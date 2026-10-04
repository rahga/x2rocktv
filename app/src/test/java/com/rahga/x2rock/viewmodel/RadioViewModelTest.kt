package com.rahga.x2rock.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.rahga.x2rock.lan.FakePlayer
import com.rahga.x2rock.lan.connectTo
import com.rahga.x2rock.lan.LanHttp
import com.rahga.x2rock.lan.MulticastGate
import com.rahga.x2rock.lan.PlayerAddressBook
import com.rahga.x2rock.lan.SeedStore
import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.radio.RadioDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class RadioViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()


    private lateinit var fake: FakePlayer
    private lateinit var directoryServer: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold
    private lateinit var viewModel: RadioViewModel

    private val capture = FakePlayer::class.java.getResourceAsStream("/fixtures/radiobrowser.stations.jazz.json")!!
        .readBytes().decodeToString()

    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        directoryServer = MockWebServer().apply {
            dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = MockResponse().setBody(capture)
            }
            start()
        }
        scope = CoroutineScope(SupervisorJob())
        val book = PlayerAddressBook()
        household = SonosHousehold(
            scope = scope, addressBook = book, multicast = MulticastGate.None,
            client = LanHttp.client(book), seeds = SeedStore.None, port = fake.port,
        )
        runBlocking {
            household.connectTo(fake, 10_000)
        }
        viewModel = RadioViewModel(
            household,
            RadioDirectory(OkHttpClient(), base = directoryServer.url("/").toString().trimEnd('/')),
            SavedStateHandle(mapOf("groupId" to kitchen.id)),
        )
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        fake.shutdown()
        directoryServer.shutdown()
    }

    private val kitchen get() = household.state.value.groups.first { it.name == "Kitchen" }


    @Test fun `the most voted come first, then this country, then genres`() {
        val us = categoriesFor(Locale.US)
        assertEquals(RadioViewModel.Category("Popular"), us[0])
        assertEquals("US", us[1].countryCode)
        assertEquals("jazz", us.first { it.label == "Jazz" }.tag)
        assertTrue("no country to name", categoriesFor(Locale.ROOT).none { it.countryCode != null })
    }

    @Test fun `a station that plays goes back to the room`() = runBlocking<Unit> {
        val station = (withTimeout(5_000) { viewModel.stations.first { it is RadioViewModel.Stations.Loaded } }
            as RadioViewModel.Stations.Loaded).stations.first()
        var done = false
        viewModel.play(station) { done = true }
        fake.awaitCommand(5_000) { it.get("command")?.asString == "loadStreamUrl" }
        fake.pushPlaybackStatus(kitchen.id, "PLAYBACK_STATE_PLAYING")
        withTimeout(5_000) { while (!done) delay(20) }
    }

    /** The directory's liveness check is days old; a station that will not play stays listed, said. */
    @Test fun `a station the room would not play stays on the list, said`() = runBlocking<Unit> {
        val station = (withTimeout(5_000) { viewModel.stations.first { it is RadioViewModel.Stations.Loaded } }
            as RadioViewModel.Stations.Loaded).stations.first()
        var done = false
        viewModel.play(station) { done = true }
        fake.awaitCommand(5_000) { it.get("command")?.asString == "loadStreamUrl" }
        fake.pushPlaybackStatus(kitchen.id, "PLAYBACK_STATE_IDLE")
        // The whole start window: the silent answer is only known once it has run out.
        val said = withTimeout(15_000) { viewModel.notice.first { it != null } }
        assertEquals(silentNotice(station), said)
        assertFalse(done)
    }
}
