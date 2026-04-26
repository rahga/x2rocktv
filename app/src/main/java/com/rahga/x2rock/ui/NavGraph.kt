package com.rahga.x2rock.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rahga.x2rock.ui.screens.FavoritesScreen
import com.rahga.x2rock.ui.screens.HomeScreen
import com.rahga.x2rock.ui.screens.LoginScreen
import com.rahga.x2rock.ui.screens.PlayerScreen
import com.rahga.x2rock.ui.screens.QueueScreen
import com.rahga.x2rock.ui.screens.SonosAuthWebViewScreen
import com.rahga.x2rock.viewmodel.LoginUiState
import com.rahga.x2rock.viewmodel.LoginViewModel

@Composable
fun X2RockNavGraph() {
    val navController = rememberNavController()
    // Read initial auth state synchronously — LoginViewModel sets it without a coroutine
    val loginViewModel: LoginViewModel = hiltViewModel()
    val startDestination = remember {
        if (loginViewModel.state.value is LoginUiState.Authenticated) "home" else "login"
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
                onGroupSelected = { group ->
                    navController.navigate(
                        "player?groupId=${Uri.encode(group.id)}&groupName=${Uri.encode(group.name)}"
                    )
                },
                onSignedOut = {
                    navController.navigate("login") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = "player?groupId={groupId}&groupName={groupName}",
            arguments = listOf(
                navArgument("groupId") { type = NavType.StringType },
                navArgument("groupName") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val groupId = backStackEntry.arguments?.getString("groupId") ?: ""
            PlayerScreen(
                onBack = { navController.popBackStack() },
                onOpenQueue = {
                    navController.navigate("queue?groupId=${Uri.encode(groupId)}")
                },
                onOpenFavorites = {
                    navController.navigate("favorites?groupId=${Uri.encode(groupId)}")
                }
            )
        }

        composable(
            route = "queue?groupId={groupId}",
            arguments = listOf(navArgument("groupId") { type = NavType.StringType })
        ) {
            QueueScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = "favorites?groupId={groupId}",
            arguments = listOf(navArgument("groupId") { type = NavType.StringType })
        ) {
            FavoritesScreen(onBack = { navController.popBackStack() })
        }
    }
}
