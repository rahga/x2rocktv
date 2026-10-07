package com.rahga.x2rock.ui

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
            navController.navigate("home") { launchSingleTop = true }
            homeViewModel.clearNavigateToRoom()
        }
    }

    // Every screen opened from a room names it: the selected room is the player's, and every route
    // below is opened on it.
    val room = playerViewModel.uiState.collectAsState().value.groupName
    fun openService(groupId: String, serviceKey: String, containerId: String, title: String) =
        navController.navigate(
            "services?groupId=${Uri.encode(groupId)}&service=${Uri.encode(serviceKey)}" +
                "&container=${Uri.encode(containerId)}&title=${Uri.encode(title)}"
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
                homeViewModel = homeViewModel,
                playerViewModel = playerViewModel
            )
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
                onOpen = { serviceKey, item -> openService(groupId, serviceKey, item.id, item.title) },
            )
        }

        composable(
            // Opened on the service list, or — from search — straight onto one of a service's
            // containers, which Back then leaves for the search again.
            route = "services?groupId={groupId}&service={service}&container={container}&title={title}",
            arguments = listOf(
                navArgument("groupId") { type = NavType.StringType },
                navArgument("service") { type = NavType.StringType; defaultValue = "" },
                navArgument("container") { type = NavType.StringType; defaultValue = "" },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
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
