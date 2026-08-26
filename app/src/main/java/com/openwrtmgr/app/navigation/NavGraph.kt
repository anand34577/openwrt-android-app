package com.openwrtmgr.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.openwrtmgr.app.domain.repository.RouterRepository
import com.openwrtmgr.app.feature.backup.BackupScreen
import com.openwrtmgr.app.feature.diagnostics.DiagnosticsScreen
import com.openwrtmgr.app.feature.dns.DnsScreen
import com.openwrtmgr.app.feature.onboarding.RouterListScreen
import com.openwrtmgr.app.feature.router.RouterHomeScreen
import com.openwrtmgr.app.feature.uci.UciEditorScreen

sealed class Screen(val route: String) {
    data object RouterList : Screen("routers")
    data object RouterHome : Screen("router/{profileId}") {
        fun routeFor(profileId: Long) = "router/$profileId"
    }
    data object Diagnostics : Screen("router/{profileId}/diagnostics") {
        fun routeFor(profileId: Long) = "router/$profileId/diagnostics"
    }
    data object UciEditor : Screen("router/{profileId}/uci") {
        fun routeFor(profileId: Long) = "router/$profileId/uci"
    }
    data object Dns : Screen("router/{profileId}/dns") {
        fun routeFor(profileId: Long) = "router/$profileId/dns"
    }
    data object Backup : Screen("router/{profileId}/backup") {
        fun routeFor(profileId: Long) = "router/$profileId/backup"
    }
}

private val PROFILE_ID_ARG = navArgument("profileId") { type = NavType.LongType }

@Composable
fun OpenWrtNavGraph(repository: RouterRepository, navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Screen.RouterList.route) {
        composable(Screen.RouterList.route) {
            RouterListScreen(
                repository = repository,
                onRouterSelected = { id -> navController.navigate(Screen.RouterHome.routeFor(id)) },
            )
        }
        composable(route = Screen.RouterHome.route, arguments = listOf(PROFILE_ID_ARG)) { backStackEntry ->
            val profileId = backStackEntry.arguments?.getLong("profileId") ?: return@composable
            RouterHomeScreen(
                repository = repository,
                profileId = profileId,
                onOpenDiagnostics = { navController.navigate(Screen.Diagnostics.routeFor(profileId)) },
                onOpenUciEditor = { navController.navigate(Screen.UciEditor.routeFor(profileId)) },
                onOpenDnsManagement = { navController.navigate(Screen.Dns.routeFor(profileId)) },
                onOpenBackupRestore = { navController.navigate(Screen.Backup.routeFor(profileId)) },
                onSwitchRouter = {
                    navController.navigate(Screen.RouterList.route) {
                        popUpTo(Screen.RouterList.route) { inclusive = true }
                    }
                },
            )
        }
        composable(route = Screen.Diagnostics.route, arguments = listOf(PROFILE_ID_ARG)) {
            DiagnosticsScreen(onBack = { navController.popBackStack() })
        }
        composable(route = Screen.UciEditor.route, arguments = listOf(PROFILE_ID_ARG)) { backStackEntry ->
            val profileId = backStackEntry.arguments?.getLong("profileId") ?: return@composable
            UciEditorScreen(repository = repository, profileId = profileId, onBack = { navController.popBackStack() })
        }
        composable(route = Screen.Dns.route, arguments = listOf(PROFILE_ID_ARG)) { backStackEntry ->
            val profileId = backStackEntry.arguments?.getLong("profileId") ?: return@composable
            DnsScreen(repository = repository, profileId = profileId, onBack = { navController.popBackStack() })
        }
        composable(route = Screen.Backup.route, arguments = listOf(PROFILE_ID_ARG)) { backStackEntry ->
            val profileId = backStackEntry.arguments?.getLong("profileId") ?: return@composable
            BackupScreen(repository = repository, profileId = profileId, onBack = { navController.popBackStack() })
        }
    }
}
