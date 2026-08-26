package com.openwrtmgr.app.feature.router

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.openwrtmgr.app.domain.repository.RouterRepository
import com.openwrtmgr.app.feature.clients.ClientsScreen
import com.openwrtmgr.app.feature.dashboard.DashboardScreen
import com.openwrtmgr.app.feature.firewall.PortForwardingScreen
import com.openwrtmgr.app.feature.system.SystemScreen

/**
 * Section 20/55 — bottom navigation once a router is selected, instead of one giant menu.
 * "More" (section 55) isn't built yet: only the sections with real data go here so far.
 * Each tab keeps its own top app bar (with the back-to-routers action); this screen only owns
 * the bottom bar, to avoid stacking two app bars on top of each other.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouterHomeScreen(repository: RouterRepository, profileId: Long, onBack: () -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        Tab("Dashboard", Icons.Default.Dashboard),
        Tab("Devices", Icons.Default.Devices),
        Tab("Port Forwarding", Icons.Default.Router),
        Tab("System", Icons.Default.Settings),
    )

    Scaffold(
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
                    0 -> DashboardScreen(repository, profileId, onBack)
                    1 -> ClientsScreen(repository, profileId, onBack)
                    2 -> PortForwardingScreen(repository, profileId, onBack)
                    else -> SystemScreen(repository, profileId, onBack)
                }
            }
        }
    }
}

private data class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
