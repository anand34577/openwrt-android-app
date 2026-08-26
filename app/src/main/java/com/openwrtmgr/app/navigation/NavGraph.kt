package com.openwrtmgr.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.openwrtmgr.app.domain.repository.RouterRepository
import com.openwrtmgr.app.feature.onboarding.RouterListScreen
import com.openwrtmgr.app.feature.router.RouterHomeScreen

sealed class Screen(val route: String) {
    data object RouterList : Screen("routers")
    data object RouterHome : Screen("router/{profileId}") {
        fun routeFor(profileId: Long) = "router/$profileId"
    }
}

@Composable
fun OpenWrtNavGraph(repository: RouterRepository, navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Screen.RouterList.route) {
        composable(Screen.RouterList.route) {
            RouterListScreen(
                repository = repository,
                onRouterSelected = { id -> navController.navigate(Screen.RouterHome.routeFor(id)) },
            )
        }
        composable(
            route = Screen.RouterHome.route,
            arguments = listOf(navArgument("profileId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val profileId = backStackEntry.arguments?.getLong("profileId") ?: return@composable
            RouterHomeScreen(
                repository = repository,
                profileId = profileId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
