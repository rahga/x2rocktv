package com.rahga.x2rock.ui

import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rahga.x2rock.ui.screens.AboutSystemScreen
import com.rahga.x2rock.ui.screens.AccountScreen
import com.rahga.x2rock.ui.screens.BetaReportScreen
import com.rahga.x2rock.ui.screens.AlarmsScreen
import com.rahga.x2rock.ui.screens.AppleMusicScreen
import com.rahga.x2rock.ui.screens.ServiceBrowseScreen
import com.rahga.x2rock.ui.screens.FavoritesScreen
import com.rahga.x2rock.ui.screens.HomeScreen
import com.rahga.x2rock.ui.screens.NowPlayingScreen
import com.rahga.x2rock.ui.screens.QueueScreen
import com.rahga.x2rock.ui.screens.PreferredServicesScreen
import com.rahga.x2rock.ui.screens.RadioScreen
import com.rahga.x2rock.ui.screens.SearchScreen
import com.rahga.x2rock.viewmodel.AccountViewModel
import com.rahga.x2rock.viewmodel.AfterPlayingViewModel
import com.rahga.x2rock.viewmodel.HomeViewModel
import com.rahga.x2rock.viewmodel.PlayerViewModel
import com.rahga.x2rock.viewmodel.RoomRoute

/**
 * A room screen's route, opened on [room]: its group, and the speakers it is followed by, which a
 * screen opened while the group id was already gone still finds it by (P-88).
 */
private fun roomQuery(room: RoomRoute) = "groupId=${Uri.encode(room.groupId)}&${RoomRoute.SPEAKERS}=${Uri.encode(room.speakers)}"

/** The arguments every room screen's route takes, after its own. */
private val roomArguments = listOf(
    navArgument("groupId") { type = NavType.StringType },
    navArgument(RoomRoute.SPEAKERS) { type = NavType.StringType; defaultValue = "" },
)

@Composable
fun X2RockNavGraph() {
    val navController = rememberNavController()
    val playerViewModel: PlayerViewModel = hiltViewModel()
    val homeViewModel: HomeViewModel = hiltViewModel()
    val afterPlayingViewModel: AfterPlayingViewModel = hiltViewModel()
    // Something started: back to the room, past Browse, where what started is shown — unless Browse's
    // pin says stay, which leaves the viewer where they chose it (asked for 2026-10-15). Only from the
    // screen in front: a start landing in the fade after Back, from the screen being left, popped the
    // one it revealed too (P-90, 2026-10-23).
    fun played(from: NavBackStackEntry) {
        if (!from.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (!afterPlayingViewModel.stay.value) navController.popBackStack("home", inclusive = false)
    }

    // Connect once the UI is on screen. This used to gate two poll timers; there is no
    // timer now, so there is nothing to switch off when the TV moves to another input —
    // the subscriptions simply sit idle, and state is already current on the way back.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            homeViewModel.connect()
        }
    }

    PlayerFollowsHome(homeViewModel, playerViewModel)

    val navigateToRoom by homeViewModel.navigateToRoom.collectAsState()
    LaunchedEffect(navigateToRoom) {
        if (navigateToRoom) {
            // Back to the home already at the bottom, not a second one on top: with only
            // launchSingleTop, a room opened from the launcher while Search was showing left
            // [home, search, home], and Back from home went into Search (outside review, 2026-10-08).
            navController.navigate("home") {
                popUpTo("home") { inclusive = false }
                launchSingleTop = true
            }
            homeViewModel.clearNavigateToRoom()
        }
    }

    // Every screen opened from a room names it: the selected room is the player's, and every route
    // below is opened on it.
    // The room name alone, so a position tick or a volume step does not recompose the graph.
    val room by remember(playerViewModel) { playerViewModel.uiState.map { it.groupName }.distinctUntilChanged() }
        .collectAsState(initial = "")
    // The shared player's line — a media key's failure, a saved favourite, what Home's actions
    // answered once the viewer had gone on — for the screens that hold no player of their own. Each
    // draws it when it has nothing of its own to say, as Queue, Browse and Radio do (P-80).
    val sharedNotice by remember(playerViewModel) { playerViewModel.uiState.map { it.notice }.distinctUntilChanged() }
        .collectAsState(initial = null)
    fun openService(room: RoomRoute, serviceKey: String, containerId: String, title: String, favorite: String = "", kind: String = "") =
        navController.navigate(
            "services?${roomQuery(room)}&service=${Uri.encode(serviceKey)}" +
                "&container=${Uri.encode(containerId)}&title=${Uri.encode(title)}&favorite=${Uri.encode(favorite)}" +
                "&kind=${Uri.encode(kind)}"
        )

    // Home's Settings panel, from a screen opened on top of it: back to Home, which opens it once in front.
    fun openSettings() {
        homeViewModel.requestSettings()
        navController.popBackStack("home", inclusive = false)
    }

    // Never past home: the leaving screen keeps focus through the fade, so a second quick press on
    // its Back would pop the start destination too and leave nothing but a blank.
    val back: () -> Unit = { if (navController.previousBackStackEntry != null) navController.popBackStack() }

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            val accountViewModel: AccountViewModel = hiltViewModel()
            val accountOffered by accountViewModel.offered.collectAsState()
            val accountSignedIn by accountViewModel.signedIn.collectAsState()
            HomeScreen(
                onOpenQueue = { navController.navigate("queue?${roomQuery(it)}") },
                onOpenFavorites = { navController.navigate("favorites?${roomQuery(it)}") },
                onOpenSearch = { navController.navigate("search?${roomQuery(it)}") },
                onOpenNowPlaying = { navController.navigate("nowPlaying?${roomQuery(it)}") },
                onOpenPreferredServices = { navController.navigate("preferredServices") },
                onOpenAlarms = { navController.navigate("alarms") },
                onOpenAbout = { navController.navigate("about") },
                onOpenReport = { navController.navigate("report") },
                homeViewModel = homeViewModel,
                playerViewModel = playerViewModel,
                accountOffered = accountOffered,
                accountSignedIn = accountSignedIn,
                onOpenAccount = { navController.navigate("account") },
            )
        }

        composable(route = "nowPlaying?groupId={groupId}&speakers={speakers}", arguments = roomArguments) {
            NowPlayingScreen(onBack = back, sharedNotice = sharedNotice)
        }

        composable("about") {
            AboutSystemScreen(onBack = back)
        }
        composable("account") {
            AccountScreen(onBack = back)
        }
        composable("report") {
            BetaReportScreen(onBack = back)
        }

        composable("alarms") {
            AlarmsScreen(onBack = back, sharedNotice = sharedNotice)
        }

        composable("preferredServices") {
            PreferredServicesScreen(onBack = back)
        }

        composable(route = "queue?groupId={groupId}&speakers={speakers}", arguments = roomArguments) {
            QueueScreen(
                room = room,
                onBack = back,
                playerViewModel = playerViewModel
            )
        }

        composable(route = "favorites?groupId={groupId}&speakers={speakers}", arguments = roomArguments) { entry ->
            FavoritesScreen(
                room = room,
                onBack = back,
                onOpenRadio = { navController.navigate("radio?${roomQuery(it)}") },
                onOpenServices = { navController.navigate("services?${roomQuery(it)}") },
                onOpenFavorite = { opened, serviceKey, containerId, favorite ->
                    // An artist is a place, not a whole to play: it opens as search opens one.
                    if (favorite.resource?.type == "ARTIST") openService(opened, serviceKey, containerId, favorite.name, kind = "artist")
                    else openService(opened, serviceKey, containerId, favorite.name, favorite.id)
                },
                onPlayed = { played(entry) },
                playerViewModel = playerViewModel
            )
        }

        composable(route = "search?groupId={groupId}&speakers={speakers}", arguments = roomArguments) { entry ->
            SearchScreen(
                room = room,
                onBack = back,
                onPlayed = { played(entry) },
                // The room's group as it is now, from the screen, not the route's: a regroup since
                // Search opened mints a new one, and a page opened on the old could not find the room.
                onOpen = { opened, serviceKey, item -> openService(opened, serviceKey, item.id, item.title, kind = item.itemType) },
                onSearchService = { opened, serviceKey, query ->
                    navController.navigate("services?${roomQuery(opened)}&service=${Uri.encode(serviceKey)}&query=${Uri.encode(query)}")
                },
                onOpenSettings = ::openSettings,
                sharedNotice = sharedNotice,
            )
        }

        composable(
            // Opened on the service list, or — from search — straight onto one of a service's
            // containers or onto its own search for the same term, which Back then leaves for the
            // search again.
            route = "services?groupId={groupId}&speakers={speakers}&service={service}&container={container}&title={title}&query={query}&favorite={favorite}&kind={kind}",
            arguments = roomArguments + listOf(
                navArgument("service") { type = NavType.StringType; defaultValue = "" },
                navArgument("container") { type = NavType.StringType; defaultValue = "" },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
                navArgument("query") { type = NavType.StringType; defaultValue = "" },
                navArgument("favorite") { type = NavType.StringType; defaultValue = "" },
                navArgument("kind") { type = NavType.StringType; defaultValue = "" },
            )
        ) { entry ->
            ServiceBrowseScreen(
                room = room,
                onOpenAppleMusic = { navController.navigate("applemusic?${roomQuery(it)}") },
                onBack = back,
                onPlayed = { played(entry) },
                onOpenSettings = ::openSettings,
                sharedNotice = sharedNotice,
            )
        }

        composable(route = "applemusic?groupId={groupId}&speakers={speakers}", arguments = roomArguments) { entry ->
            AppleMusicScreen(
                room = room,
                onBack = back,
                onPlayed = { played(entry) },
                sharedNotice = sharedNotice,
            )
        }

        composable(route = "radio?groupId={groupId}&speakers={speakers}", arguments = roomArguments) { entry ->
            RadioScreen(
                room = room,
                onBack = back,
                playerViewModel = playerViewModel,
                onPlayed = { played(entry) },
            )
        }
    }
}

/**
 * The shared player is the home screen's selection wherever the viewer is. Told only by the home
 * screen, it stayed on the old room while any other screen was in front: a preset started with the
 * Stay pin held left the bar, the media keys and the launcher's card on the room Browse was opened
 * from (P-100), and a room that left the household went on being advertised until Back. Collected
 * rather than composed: the list changes with every event in the house.
 */
@Composable
fun PlayerFollowsHome(homeViewModel: HomeViewModel, playerViewModel: PlayerViewModel) {
    LaunchedEffect(homeViewModel, playerViewModel) {
        // The groups alone, not the rooms' details, which move with every event in the house. Keyed on
        // them too: a deep link can select a room before they have loaded.
        val groups = homeViewModel.uiState.map { (it as? HomeViewModel.UiState.Success)?.groups }.distinctUntilChanged()
        combine(homeViewModel.selectedGroupId, groups) { id, groups ->
            val group = groups?.find { it.id == id }
            group?.let { Triple(it.id, it.name, homeViewModel.route(it.id)) }
        }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { (id, name, route) -> playerViewModel.selectGroup(id, name, route) }
    }
}
