package com.huyang.luciddream.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.huyang.luciddream.ui.history.HistoryScreen
import com.huyang.luciddream.ui.home.HomeScreen
import com.huyang.luciddream.ui.inspector.InspectorScreen
import com.huyang.luciddream.ui.navigation.Destination
import com.huyang.luciddream.ui.settings.SettingsScreen

@Composable
fun LucidDreamRoot(
    requestedRoute: String? = null,
    onRequestedRouteConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

    LaunchedEffect(requestedRoute) {
        val route = requestedRoute?.takeIf { requested ->
            Destination.entries.any { it.route == requested }
        }
        if (route != null && currentRoute != route) {
            navController.navigate(route) {
                launchSingleTop = true
            }
        }
        if (requestedRoute != null) onRequestedRouteConsumed()
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Text(destination.glyph) },
                        label = { Text(destination.label) },
                        colors = NavigationBarItemDefaults.colors(),
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            NavHost(
                navController = navController,
                startDestination = Destination.Home.route,
            ) {
                composable(Destination.Home.route) { HomeScreen() }
                composable(Destination.Inspector.route) { InspectorScreen() }
                composable(Destination.History.route) { HistoryScreen() }
                composable(Destination.Settings.route) { SettingsScreen() }
            }
        }
    }
}
