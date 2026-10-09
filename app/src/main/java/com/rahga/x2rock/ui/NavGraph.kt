package com.rahga.x2rock.ui

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rahga.x2rock.ui.screens.AppleMusicScreen
import com.rahga.x2rock.ui.screens.ServiceBrowseScreen
import com.rahga.x2rock.ui.screens.FavoritesScreen
import com.rahga.x2rock.ui.screens.HomeScreen
import com.rahga.x2rock.ui.screens.QueueScreen
import com.rahga.x2rock.ui.screens.PreferredServicesScreen
import com.rahga.x2rock.ui.screens.RadioScreen
import com.rahga.x2rock.ui.screens.SearchScreen
import com.rahga.x2rock.viewmodel.HomeViewModel
import com.rahga.x2rock.viewmodel.PlayerViewModel

@Composable
fun X2RockNavGraph() {
    val navController = rememberNavController()
    val playerViewModel: PlayerViewModel = hiltViewModel()
    val homeViewModel: HomeViewModel = hiltViewModel()

    // Connect once the UI is on screen. This used to gate two poll timers; there is no
    // timer now, so there is nothing to switch off when the TV moves to another input —
    // the subscriptions simply sit idle, and state is already current on the way back.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            homeViewModel.connect()
        }
    }

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
    fun openService(groupId: String, serviceKey: String, containerId: String, title: String, favorite: String = "", kind: String = "") =
        navController.navigate(
            "services?groupId=${Uri.encode(groupId)}&service=${Uri.encode(serviceKey)}" +
                "&container=${Uri.encode(containerId)}&title=${Uri.encode(title)}&favorite=${Uri.encode(favorite)}" +
                "&kind=${Uri.encode(kind)}"
        )

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onOpenQueue = { groupId ->
                    navController.navigate("queue?groupId=${Uri.encode(groupId)}")
                },
                onOpenFavorites = { groupId ->
                    navController.navigate("favorites?groupId=${Uri.encode(groupId)}")
                },
                onOpenSearch = { groupId ->
                    navController.navigate("search?groupId=${Uri.encode(groupId)}")
                },
                onOpenPreferredServices = { navController.navigate("preferredServices") },
                homeViewModel = homeViewModel,
                playerViewModel = playerViewModel
            )
        }

        composable("preferredServices") {
            PreferredServicesScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = "queue?groupId={groupId}",
            arguments = listOf(navArgument("groupId") { type = NavType.StringType })
        ) {
            QueueScreen(
                room = room,
                onBack = { navController.popBackStack() },
                playerViewModel = playerViewModel
            )
        }

        composable(
            route = "favorites?groupId={groupId}",
            arguments = listOf(navArgument("groupId") { type = NavType.StringType })
        ) {
            FavoritesScreen(
                room = room,
                onBack = { navController.popBackStack() },
                onOpenRadio = { groupId -> navController.navigate("radio?groupId=${Uri.encode(groupId)}") },
                onOpenServices = { groupId -> navController.navigate("services?groupId=${Uri.encode(groupId)}") },
                onOpenFavorite = { groupId, serviceKey, containerId, favorite ->
                    openService(groupId, serviceKey, containerId, favorite.name, favorite.id)
                },
                playerViewModel = playerViewModel
            )
        }

        composable(
            route = "search?groupId={groupId}",
            arguments = listOf(navArgument("groupId") { type = NavType.StringType })
        ) { entry ->
            val groupId = entry.arguments?.getString("groupId").orEmpty()
            SearchScreen(
                room = room,
                onBack = { navController.popBackStack() },
                onPlayed = { navController.popBackStack("home", inclusive = false) },
                onOpen = { serviceKey, item -> openService(groupId, serviceKey, item.id, item.title, kind = item.itemType) },
                onSearchService = { serviceKey, query ->
                    navController.navigate(
                        "services?groupId=${Uri.encode(groupId)}&service=${Uri.encode(serviceKey)}&query=${Uri.encode(query)}"
                    )
                },
            )
        }

        composable(
            // Opened on the service list, or — from search — straight onto one of a service's
            // containers or onto its own search for the same term, which Back then leaves for the
            // search again.
            route = "services?groupId={groupId}&service={service}&container={container}&title={title}&query={query}&favorite={favorite}&kind={kind}",
            arguments = listOf(
                navArgument("groupId") { type = NavType.StringType },
                navArgument("service") { type = NavType.StringType; defaultValue = "" },
                navArgument("container") { type = NavType.StringType; defaultValue = "" },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
                navArgument("query") { type = NavType.StringType; defaultValue = "" },
                navArgument("favorite") { type = NavType.StringType; defaultValue = "" },
                navArgument("kind") { type = NavType.StringType; defaultValue = "" },
            )
        ) { entry ->
            val groupId = entry.arguments?.getString("groupId").orEmpty()
            ServiceBrowseScreen(
                room = room,
                onOpenAppleMusic = { navController.navigate("applemusic?groupId=${Uri.encode(groupId)}") },
                onBack = { navController.popBackStack() },
                // Back to the room, past Favorites, as Radio and Apple Music do.
                onPlayed = { navController.popBackStack("home", inclusive = false) },
            )
        }

        composable(
            route = "applemusic?groupId={groupId}",
            arguments = listOf(navArgument("groupId") { type = NavType.StringType })
        ) {
            AppleMusicScreen(
                room = room,
                onBack = { navController.popBackStack() },
                // Back to the room, past Favorites, as a station played from Radio does.
                onPlayed = { navController.popBackStack("home", inclusive = false) },
            )
        }

        composable(
            route = "radio?groupId={groupId}",
            arguments = listOf(navArgument("groupId") { type = NavType.StringType })
        ) {
            RadioScreen(
                room = room,
                onBack = { navController.popBackStack() },
                // Back to the room, past Favorites: the station is what it now plays.
                onPlayed = { navController.popBackStack("home", inclusive = false) },
            )
        }
    }
}
