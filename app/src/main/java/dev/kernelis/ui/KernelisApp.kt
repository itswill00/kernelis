package dev.kernelis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.kernelis.ui.dashboard.DashboardScreen
import dev.kernelis.ui.diagnostics.DiagnosticsScreen
import dev.kernelis.ui.history.HistoryScreen
import dev.kernelis.ui.profiles.ProfilesScreen
import dev.kernelis.ui.theme.LocalKernelisColors
import dev.kernelis.ui.theme.Spacing
import dev.kernelis.ui.tuning.TuningScreen
import kotlinx.coroutines.launch

/**
 * Top-level navigation.
 *
 * A navigation drawer rather than a bottom bar, for three measured reasons:
 *
 *  1. Five bottom-bar destinations is one past the Material 3 comfortable maximum
 *     (3–5, and 5 is only tolerable with short labels). Four of these screens are
 *     things a user visits *occasionally* — a persistent bar spent 56 dp of a phone
 *     screen on them at all times.
 *  2. These are tool destinations, not a social feed: the FK Manager / SmartPack
 *     family all use a drawer or a grid launcher, and so does this.
 *  3. The drawer has room for a section grouping and per-item descriptions, so
 *     "what does History mean" is answered before the tap, not after.
 *
 * The app bar carries the screen title plus one contextual action, so a screen never
 * has to invent its own header and vertical space is spent once, not twice.
 */
public enum class Destination(
    public val route: String,
    public val label: String,
    public val description: String,
    public val group: DestinationGroup,
) {
    DASHBOARD(
        route = "dashboard",
        label = "Monitor",
        description = "Live CPU, memory, thermal",
        group = DestinationGroup.LIVE,
    ),
    TUNING(
        route = "tuning",
        label = "Tuning",
        description = "Kernel controls by domain",
        group = DestinationGroup.CONTROL,
    ),
    PROFILES(
        route = "profiles",
        label = "Profiles",
        description = "Saved and preset configurations",
        group = DestinationGroup.CONTROL,
    ),
    HISTORY(
        route = "history",
        label = "History",
        description = "Every change, with rollback",
        group = DestinationGroup.RECORD,
    ),
    DIAGNOSTICS(
        route = "diagnostics",
        label = "Diagnostics",
        description = "Capability report and console",
        group = DestinationGroup.RECORD,
    ),
}

public enum class DestinationGroup(public val label: String) {
    LIVE("Live"),
    CONTROL("Control"),
    RECORD("Record"),
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
public fun KernelisApp() {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val current = Destination.entries.firstOrNull { it.route == currentRoute } ?: Destination.DASHBOARD

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            KernelisDrawer(
                current = current,
                onSelect = { destination ->
                    scope.launch { drawerState.close() }
                    navController.navigateTo(destination)
                },
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                current.label,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                current.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalKernelisColors.current.textTertiary,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Outlined.Menu, contentDescription = "Open navigation")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Destination.DASHBOARD.route,
                modifier = Modifier.padding(innerPadding),
            ) {
                composable(Destination.DASHBOARD.route) {
                    DashboardScreen(
                        onOpenTuning = { navController.navigateTo(Destination.TUNING) },
                        onOpenDiagnostics = { navController.navigateTo(Destination.DIAGNOSTICS) },
                    )
                }
                composable(Destination.TUNING.route) { TuningScreen() }
                composable(Destination.PROFILES.route) { ProfilesScreen() }
                composable(Destination.HISTORY.route) { HistoryScreen() }
                composable(Destination.DIAGNOSTICS.route) { DiagnosticsScreen() }
            }
        }
    }
}

/**
 * Drawer content: a header that states what the app is, then the destinations grouped
 * by what the user is trying to do (observe / change / review), not alphabetically.
 */
@Composable
internal fun KernelisDrawer(
    current: Destination,
    onSelect: (Destination) -> Unit,
) {
    val colors = LocalKernelisColors.current
    ModalDrawerSheet {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = Spacing.lg),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.xl),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Build,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(28.dp),
                )
                Column {
                    Text(
                        "KERNELIS",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "kernel & system manager",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textTertiary,
                    )
                }
            }
            HorizontalDivider(color = colors.surfaceHigh)

            DestinationGroup.entries.forEach { group ->
                Text(
                    group.label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(
                        start = Spacing.lg,
                        end = Spacing.lg,
                        top = Spacing.lg,
                        bottom = Spacing.xs,
                    ),
                )
                Destination.entries.filter { it.group == group }.forEach { destination ->
                    NavigationDrawerItem(
                        label = {
                            Column {
                                Text(
                                    destination.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (destination == current) FontWeight.SemiBold else FontWeight.Normal,
                                )
                                Text(
                                    destination.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textTertiary,
                                )
                            }
                        },
                        icon = { Icon(destination.icon(), contentDescription = null) },
                        selected = destination == current,
                        onClick = { onSelect(destination) },
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = colors.accentSoft,
                            selectedIconColor = colors.accent,
                            selectedTextColor = colors.accent,
                        ),
                        modifier = Modifier.padding(horizontal = Spacing.sm),
                    )
                }
            }

            Spacer(Modifier.height(Spacing.lg))
            HorizontalDivider(color = colors.surfaceHigh)
            Text(
                "Runtime tuning only — a reboot restores every value.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            )
        }
    }
}

/**
 * Test-only entry point: renders the drawer sheet directly so a JVM smoke test can
 * verify every destination is reachable without driving navigation.
 */
@Composable
internal fun NavigationDrawerForTest() {
    KernelisDrawer(current = Destination.DASHBOARD, onSelect = {})
}

/** Shared by the drawer and any screen that wants the same glyph. */
@Composable
internal fun Destination.icon(): ImageVector = when (this) {
    Destination.DASHBOARD -> Icons.Outlined.Home
    Destination.TUNING -> Icons.Outlined.Build
    Destination.PROFILES -> Icons.Outlined.Star
    Destination.HISTORY -> Icons.AutoMirrored.Outlined.List
    Destination.DIAGNOSTICS -> Icons.Outlined.Info
}

/**
 * Single place that knows how to move between destinations.
 *
 * `saveState`/`restoreState` keep scroll position and staged values when the user
 * hops between screens and comes back — losing a half-configured tuning session
 * because the drawer was opened would be a real defect.
 */
private fun NavHostController.navigateTo(destination: Destination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
