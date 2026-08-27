package com.rahga.x2rock.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rahga.x2rock.ui.screens.FavoritesScreen
import com.rahga.x2rock.ui.screens.HomeScreen
import com.rahga.x2rock.ui.screens.LoginScreen
import com.rahga.x2rock.ui.screens.QueueScreen
import com.rahga.x2rock.ui.screens.SonosAuthWebViewScreen
import com.rahga.x2rock.viewmodel.HomeViewModel
import com.rahga.x2rock.viewmodel.LoginViewModel
import com.rahga.x2rock.viewmodel.PlayerViewModel
import kotlinx.coroutines.awaitCancellation

@Composable
fun X2RockNavGraph(startDestination: String) {
    val navController = rememberNavController()
    val playerViewModel: PlayerViewModel = hiltViewModel()
    val homeViewModel: HomeViewModel = hiltViewModel()

    // Both view models poll the Sonos cloud API on a timer. Gate them here, once, so nothing
    // keeps hitting the network while the TV is on another input — and so PlayerViewModel keeps
    // polling on the queue and favorites screens, which show its now-playing bar.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            homeViewModel.setPollingActive(true)
            playerViewModel.setPollingActive(true)
            try {
                awaitCancellation()
            } finally {
                homeViewModel.setPollingActive(false)
                playerViewModel.setPollingActive(false)
            }
        }
    }

    val navigateToRoom by homeViewModel.navigateToRoom.collectAsState()
    LaunchedEffect(navigateToRoom) {
        if (navigateToRoom) {
            navController.navigate("home") { launchSingleTop = true }
            homeViewModel.clearNavigateToRoom()
        }
    }

    // The refresh token was rejected — drop back to login rather than looping on errors.
    val sessionExpired by homeViewModel.sessionExpired.collectAsState()
    LaunchedEffect(sessionExpired) {
        if (sessionExpired) {
            homeViewModel.signOut()
            navController.navigate("login") { popUpTo(0) { inclusive = true } }
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable("login") { backStackEntry ->
            val viewModel: LoginViewModel = hiltViewModel()

            val savedStateHandle = backStackEntry.savedStateHandle
            val code by savedStateHandle.getStateFlow<String?>("code", null).collectAsState()
            val returnedState by savedStateHandle.getStateFlow<String?>("state", null).collectAsState()

            LaunchedEffect(code, returnedState) {
                val c = code
                val s = returnedState
                if (c != null && s != null) {
                    savedStateHandle.remove<String>("code")
                    savedStateHandle.remove<String>("state")
                    viewModel.handleCallback(c, s)
                }
            }

            LoginScreen(
                onAuthenticated = {
                    navController.navigate("home") {
                        popUpTo("login") { inclusive = true }
                    }
                },
                onConnect = { authUrl ->
                    navController.navigate("auth-webview/${Uri.encode(authUrl)}")
                },
                viewModel = viewModel
            )
        }

        composable(
            route = "auth-webview/{authUrl}",
            arguments = listOf(navArgument("authUrl") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedUrl = backStackEntry.arguments?.getString("authUrl") ?: ""
            SonosAuthWebViewScreen(
                authUrl = Uri.decode(encodedUrl),
                onCodeReceived = { code, state ->
                    navController.previousBackStackEntry?.savedStateHandle?.apply {
                        set("code", code)
                        set("state", state)
                    }
                    navController.popBackStack()
                },
                onCancel = { navController.popBackStack() }
            )
        }

        composable("home") {
            HomeScreen(
                onOpenQueue = { groupId ->
                    navController.navigate("queue?groupId=${Uri.encode(groupId)}")
                },
                onOpenFavorites = { groupId ->
                    navController.navigate("favorites?groupId=${Uri.encode(groupId)}")
                },
                onSignedOut = {
                    navController.navigate("login") {
                        popUpTo(0) { inclusive = true }
                    }
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
                onBack = { navController.popBackStack() },
                playerViewModel = playerViewModel
            )
        }

        composable(
            route = "favorites?groupId={groupId}",
            arguments = listOf(navArgument("groupId") { type = NavType.StringType })
        ) {
            FavoritesScreen(
                onBack = { navController.popBackStack() },
                playerViewModel = playerViewModel
            )
        }
    }
}
