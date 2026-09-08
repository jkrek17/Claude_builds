package com.survivor.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsFootball
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.survivor.app.data.RefreshStatus
import com.survivor.app.ui.screens.AboutScreen
import com.survivor.app.ui.screens.DashboardScreen
import com.survivor.app.ui.screens.GuideScreen
import com.survivor.app.ui.screens.InputsScreen
import com.survivor.app.ui.screens.MoreScreen
import com.survivor.app.ui.screens.PicksScreen
import com.survivor.app.ui.screens.RankingsScreen
import com.survivor.app.ui.screens.SeasonScreen
import com.survivor.app.ui.screens.SettingsScreen
import com.survivor.app.ui.screens.SimulationScreen

object Routes {
    const val DASHBOARD = "dashboard"
    const val RANKINGS = "rankings"
    const val SEASON = "season"
    const val MORE = "more"
    const val PICKS = "picks"
    const val INPUTS = "inputs"
    const val SETTINGS = "settings"
    const val SIMULATION = "simulation"
    const val GUIDE = "guide"
    const val ABOUT = "about"
}

private val moreDestinations = setOf(Routes.INPUTS, Routes.SETTINGS, Routes.SIMULATION, Routes.GUIDE, Routes.ABOUT)

private data class Tab(val route: String, val label: String, val icon: ImageVector)

/** At most 5 bottom-bar destinations: Home, Rankings, Season (Planner + Grid tabs), Picks, More. */
private val tabs = listOf(
    Tab(Routes.DASHBOARD, "Home", Icons.Filled.SportsFootball),
    Tab(Routes.RANKINGS, "Rankings", Icons.Filled.FormatListNumbered),
    Tab(Routes.SEASON, "Season", Icons.Filled.CalendarMonth),
    Tab(Routes.PICKS, "Picks", Icons.Filled.TouchApp),
    Tab(Routes.MORE, "More", Icons.Filled.MoreHoriz),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurvivorNavHost(vm: AppViewModel) {
    val nav: NavHostController = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route ?: Routes.DASHBOARD
    val refresh by vm.refresh.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var resetDialog by remember { mutableStateOf(false) }

    fun go(route: String) {
        nav.navigate(route) { launchSingleTop = true; popUpTo(Routes.DASHBOARD) { saveState = true }; restoreState = true }
    }

    LaunchedEffect(refresh) {
        when (val r = refresh) {
            is RefreshStatus.Done -> { snackbar.showSnackbar(r.message); vm.dismissRefresh() }
            is RefreshStatus.Failed -> { snackbar.showSnackbar("Refresh failed: ${r.message}"); vm.dismissRefresh() }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(titleFor(current)) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    actions = {
                        if (refresh is RefreshStatus.Running) CircularProgressIndicator(Modifier.padding(end = 12.dp).size(20.dp), strokeWidth = 2.dp)
                        else IconButton(onClick = { vm.refreshOdds() }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh odds") }
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Survivor Tools") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Refresh NFL Data (schedule, scores, FPI)") }, onClick = { menuOpen = false; vm.refreshNflData() })
                            DropdownMenuItem(text = { Text("Refresh Odds") }, onClick = { menuOpen = false; vm.refreshOdds() })
                            DropdownMenuItem(text = { Text("Update Rankings") }, onClick = { menuOpen = false; vm.recomputeNow(); go(Routes.RANKINGS) })
                            DropdownMenuItem(text = { Text("Optimize Season Path") }, onClick = { menuOpen = false; vm.recomputeNow(); go(Routes.SEASON) })
                            DropdownMenuItem(text = { Text("Record Weekly Pick") }, onClick = { menuOpen = false; go(Routes.PICKS) })
                            DropdownMenuItem(text = { Text("Run Monte Carlo") }, onClick = { menuOpen = false; vm.runMonteCarlo(); go(Routes.SIMULATION) })
                            DropdownMenuItem(text = { Text("Reset Model") }, onClick = { menuOpen = false; resetDialog = true })
                        }
                    },
                )
                (refresh as? RefreshStatus.Running)?.let { r ->
                    Text(r.message, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        bottomBar = {
            NavigationBar {
                tabs.forEach { t ->
                    val selected = current == t.route || (t.route == Routes.MORE && current in moreDestinations)
                    NavigationBarItem(selected = selected, onClick = { go(t.route) }, icon = { Icon(t.icon, contentDescription = t.label) }, label = { Text(t.label) })
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) { data -> Snackbar(snackbarData = data) } },
    ) { padding ->
        NavHost(nav, startDestination = Routes.DASHBOARD, Modifier.padding(padding)) {
            composable(Routes.DASHBOARD) { DashboardScreen(vm, ::go) }
            composable(Routes.RANKINGS) { RankingsScreen(vm) }
            composable(Routes.SEASON) { SeasonScreen(vm) }
            composable(Routes.MORE) { MoreScreen(vm, ::go) }
            composable(Routes.PICKS) { PicksScreen(vm) }
            composable(Routes.INPUTS) { InputsScreen(vm) }
            composable(Routes.SETTINGS) { SettingsScreen(vm) }
            composable(Routes.SIMULATION) { SimulationScreen(vm) }
            composable(Routes.GUIDE) { GuideScreen() }
            composable(Routes.ABOUT) { AboutScreen(vm) }
        }
    }

    if (resetDialog) {
        AlertDialog(
            onDismissRequest = { resetDialog = false },
            title = { Text("Reset model?") },
            text = { Text("Clears recorded picks, adjustments and settings. Downloaded schedule and lines are kept unless you choose \"Reset everything\".") },
            confirmButton = { TextButton(onClick = { resetDialog = false; vm.reset(includeData = false) }) { Text("Reset picks & settings") } },
            dismissButton = {
                TextButton(onClick = { resetDialog = false; vm.reset(includeData = true) }) { Text("Reset everything") }
            },
        )
    }
}

private fun titleFor(route: String) = when (route) {
    Routes.DASHBOARD -> "Survivor Optimizer"
    Routes.RANKINGS -> "Rankings"
    Routes.SEASON -> "Season"
    Routes.PICKS -> "Picks & Used Teams"
    Routes.MORE -> "More"
    Routes.INPUTS -> "Weekly Inputs"
    Routes.SETTINGS -> "Model Settings"
    Routes.SIMULATION -> "Simulation"
    Routes.GUIDE -> "How to use"
    Routes.ABOUT -> "About"
    else -> "Survivor Optimizer"
}
