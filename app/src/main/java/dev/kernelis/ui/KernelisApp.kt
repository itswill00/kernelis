package dev.kernelis.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.kernelis.ui.dashboard.DashboardScreen
import dev.kernelis.ui.diagnostics.DiagnosticsScreen
import dev.kernelis.ui.profiles.ProfilesScreen
import dev.kernelis.ui.history.HistoryScreen
import dev.kernelis.ui.tuning.TuningScreen

/**
 * Top-level navigation.
 *
 * Five destinations cover the product surface: monitor (dashboard), act (tuning),
 * re-use state (profiles), audit (history) and investigate (diagnostics). Profiles
 * lives inside the tuning flow for now — it gets its own tab when profile automation
 * ships — so the bottom bar stays at four items.
 */
public enum class Destination(
    public val route: String,
    public val label: String,
) {
    DASHBOARD("dashboard", "Monitor"),
    TUNING("tuning", "Tuning"),
    PROFILES("profiles", "Profiles"),
    HISTORY("history", "History"),
    DIAGNOSTICS("diagnostics", "Diagnostics"),
}

@Composable
public fun KernelisApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { DestinationIcon(destination) },
                        label = {
                            Text(
                                destination.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (currentRoute == destination.route) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.DASHBOARD.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Destination.DASHBOARD.route) {
                DashboardScreen(
                    onOpenTuning = { navController.navigate(Destination.TUNING.route) },
                    onOpenDiagnostics = { navController.navigate(Destination.DIAGNOSTICS.route) },
                )
            }
            composable(Destination.TUNING.route) { TuningScreen() }
            composable(Destination.PROFILES.route) { ProfilesScreen() }
            composable(Destination.HISTORY.route) { HistoryScreen() }
            composable(Destination.DIAGNOSTICS.route) { DiagnosticsScreen() }
        }
    }
}

@Composable
private fun DestinationIcon(destination: Destination) {
    val icon = when (destination) {
        Destination.DASHBOARD -> Icons.Outlined.Home
        Destination.TUNING -> Icons.Outlined.Build
        Destination.PROFILES -> Icons.Outlined.Star
        Destination.HISTORY -> Icons.Outlined.Refresh
        Destination.DIAGNOSTICS -> Icons.Outlined.Info
    }
    Icon(imageVector = icon, contentDescription = destination.label)
}
