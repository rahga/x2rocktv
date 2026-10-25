package com.rahga.x2rock.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.rahga.x2rock.lan.EMPTY_SOAP
import com.rahga.x2rock.lan.unknownAction
import com.rahga.x2rock.lan.FakePlayer
import com.rahga.x2rock.lan.PlayerAddressBook
import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.lan.connectTo
import com.rahga.x2rock.lan.fakeHousehold
import com.rahga.x2rock.lan.soapAction
import com.rahga.x2rock.lan.soapFields
import com.rahga.x2rock.smapi.Auth
import com.rahga.x2rock.smapi.Item
import com.rahga.x2rock.smapi.LinkedService
import com.rahga.x2rock.smapi.Service
import com.rahga.x2rock.store.PendingRoomDeepLink
import com.rahga.x2rock.store.PresetStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Sonos Favorites added and removed from the app (T-32): Browse's rows remove on two presses, and the
 * item menu offers Add, or Remove where the item already is one. Replies are the office One SL's
 * (see `FavoriteEditsTest`); the favourites are the fake household's captured `getFavorites`.
 */
class SonosFavoriteEditsTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    private lateinit var fake: FakePlayer
    private lateinit var upnp: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold
    private lateinit var groupId: String
    private val requests = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()

    /** YouTube Music, as the household's favourite "Ryo Fukui in New York" (83) names it. No service is asked anything. */
    private val ytm = LinkedService(
        Service("284", "YouTube Music", "http://127.0.0.1:1/smapi", Auth.APP_LINK, null, serviceType = 72711),
        token = null, accountId = "sn_23", selector = "0", nickname = "",
    )
    private val album = Item("ALkSOiF60SmN3aW6x6j0p5ECA5_qJlToFZPtLSwFkyBNbzYO", "Ryo Fukui in New York", "album", null, null, container = true)

    @Before fun setUp() = runBlocking {
        fake = FakePlayer().also { it.start() }
        upnp = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val action = soapAction(request)
                    requests += action to soapFields(request.body.readUtf8())
                    return when (action) {
                        "DestroyObject" -> MockResponse().setBody(FakePlayer.fixtureText("DestroyObject.favorite.xml"))
                        "CreateObject" -> MockResponse().setBody(FakePlayer.fixtureText("CreateObject.serviceTrack.xml"))
                        else -> unknownAction()
                    }
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        scope = CoroutineScope(SupervisorJob())
        household = fakeHousehold(fake, scope, addressBook = PlayerAddressBook(), upnpPort = upnp.port)
        household.connectTo(fake, 10_000)
        groupId = fake.groupId(household)
        requests.clear()
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        upnp.shutdown()
        fake.shutdown()
    }

    private fun destroyed() = requests.filter { it.first == "DestroyObject" }.map { it.second["ObjectID"] }

    private suspend fun browse(): FavoritesViewModel {
        val viewModel = FavoritesViewModel(household, SavedStateHandle(mapOf("groupId" to groupId)), PresetStore(FakePreferences()), PendingRoomDeepLink())
        withTimeout(5_000) { viewModel.uiState.first { it is FavoritesViewModel.UiState.Success } }
        return viewModel
    }

    @Test fun `one press on a favourite only arms its removal`() = runBlocking<Unit> {
        val viewModel = browse()
        val station = (viewModel.uiState.value as FavoritesViewModel.UiState.Success).items.first { it.name == "Love Songs Radio" }
        viewModel.removeFavorite(station)
        assertEquals(favoriteKey("84"), viewModel.deleteArmed.value)
        delay(300)
        assertTrue(destroyed().isEmpty())
    }

    @Test fun `a second press removes it, and its row goes`() = runBlocking<Unit> {
        val viewModel = browse()
        val station = (viewModel.uiState.value as FavoritesViewModel.UiState.Success).items.first { it.name == "Love Songs Radio" }
        viewModel.removeFavorite(station)
        viewModel.removeFavorite(station)
        withTimeout(5_000) { viewModel.uiState.first { s -> (s as FavoritesViewModel.UiState.Success).items.none { it.id == "84" } } }
        assertEquals(listOf("FV:2/84"), destroyed())
        assertNull(viewModel.deleteArmed.value)
    }

    @Test fun `a press on another row arms that one instead`() = runBlocking<Unit> {
        val viewModel = browse()
        val items = (viewModel.uiState.value as FavoritesViewModel.UiState.Success).items
        viewModel.removeFavorite(items.first { it.id == "84" })
        viewModel.removeFavorite(items.first { it.id == "83" })
        assertEquals(favoriteKey("83"), viewModel.deleteArmed.value)
        delay(300)
        assertTrue(destroyed().isEmpty())
    }

    private fun menu(): ServiceItemMenu {
        val notice = TransientNotice(scope)
        return ServiceItemMenu(household, { groupId }, scope, notice, StartGuard(scope, notice)) { it.title }
    }

    @Test fun `the menu knows an item already a Sonos favourite, and removes it`() = runBlocking<Unit> {
        val menu = menu()
        val target = ServiceItemMenu.Target.Service(ytm, album)
        menu.show(target)
        assertEquals("83", withTimeout(5_000) { menu.sonosFavorite.first { it != null } })
        menu.removeSonosFavorite(target, "83")
        withTimeout(5_000) { while (destroyed().isEmpty()) delay(20) }
        assertEquals(listOf("FV:2/83"), destroyed())
    }

    @Test fun `an item that is no favourite is offered to be saved`() = runBlocking<Unit> {
        val menu = menu()
        val other = ServiceItemMenu.Target.Service(ytm, album.copy(id = "ALkSOiOther", title = "Another"))
        menu.show(other)
        delay(500)
        assertNull(menu.sonosFavorite.value)
        menu.saveSonosFavorite(other)
        withTimeout(5_000) { while (requests.none { it.first == "CreateObject" }) delay(20) }
    }
}
