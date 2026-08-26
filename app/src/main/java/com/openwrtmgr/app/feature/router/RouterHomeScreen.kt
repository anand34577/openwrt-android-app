package com.openwrtmgr.app.feature.router

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.openwrtmgr.app.domain.repository.RouterRepository
import com.openwrtmgr.app.feature.clients.ClientsScreen
import com.openwrtmgr.app.feature.dashboard.DashboardScreen
import com.openwrtmgr.app.feature.firewall.FirewallScreen
import com.openwrtmgr.app.feature.more.MoreScreen
import com.openwrtmgr.app.feature.system.SystemScreen

/** Bottom navigation once a router is selected. "More" hosts the tools that don't fit a tab of their own. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouterHomeScreen(
    repository: RouterRepository,
    profileId: Long,
    onOpenDiagnostics: () -> Unit,
    onOpenUciEditor: () -> Unit,
    onOpenDnsManagement: () -> Unit,
    onOpenBackupRestore: () -> Unit,
    onSwitchRouter: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val profiles by repository.observeProfiles().collectAsState(initial = emptyList())
    val routerName = profiles.firstOrNull { it.id == profileId }?.name ?: "this router"
    val tabs = listOf(
        Tab("Dashboard", Icons.Default.Dashboard),
        Tab("Devices", Icons.Default.Devices),
        Tab("Firewall", Icons.Default.Security),
        Tab("System", Icons.Default.Settings),
        Tab("More", Icons.Default.MoreHoriz),
    )

    Scaffold(
        // Every tab below owns its own Scaffold+TopAppBar (which already reserves the status-bar
        // inset). Scaffold's default contentWindowInsets is WindowInsets.systemBars regardless of
        // whether a topBar is provided — without zeroing it here, the status-bar gap gets reserved
        // twice (once here, once by each tab), showing as a big empty band under the status bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            Crossfade(targetState = selectedTab, label = "routerTab") { tab ->
                when (tab) {
                    0 -> DashboardScreen(repository, profileId)
                    1 -> ClientsScreen(repository, profileId)
                    2 -> FirewallScreen(repository, profileId)
                    3 -> SystemScreen(repository, profileId)
                    else -> MoreScreen(
                        routerName = routerName,
                        onOpenDiagnostics = onOpenDiagnostics,
                        onOpenUciEditor = onOpenUciEditor,
                        onOpenDnsManagement = onOpenDnsManagement,
                        onOpenBackupRestore = onOpenBackupRestore,
                        onSwitchRouter = onSwitchRouter,
                    )
                }
            }
        }
    }
}

private data class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
