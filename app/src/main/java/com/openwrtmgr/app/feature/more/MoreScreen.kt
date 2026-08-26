package com.openwrtmgr.app.feature.more

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private data class ToolEntry(val title: String, val subtitle: String, val icon: ImageVector, val onClick: () -> Unit)

/** Entry point for the tools that don't fit in the main bottom-nav tabs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    onOpenDiagnostics: () -> Unit,
    onOpenUciEditor: () -> Unit,
    onOpenDnsManagement: () -> Unit,
    onOpenBackupRestore: () -> Unit,
) {
    val tools = listOf(
        ToolEntry("Network Diagnostics", "Ping and DNS lookup, run from your phone", Icons.Default.NetworkPing, onOpenDiagnostics),
        ToolEntry("DNS Management", "Static hostname → IP records (dnsmasq)", Icons.Default.Dns, onOpenDnsManagement),
        ToolEntry("Backup & Restore", "Save or apply a full router config backup", Icons.Default.SaveAlt, onOpenBackupRestore),
        ToolEntry("UCI Raw Editor", "Advanced: edit any config section directly", Icons.Default.Terminal, onOpenUciEditor),
    )

    Scaffold(topBar = { TopAppBar(title = { Text("More") }) }) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            items(tools) { tool ->
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), onClick = tool.onClick) {
                    ListItem(
                        headlineContent = { Text(tool.title) },
                        supportingContent = { Text(tool.subtitle) },
                        leadingContent = { Icon(tool.icon, contentDescription = null) },
                        trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                    )
                }
            }
        }
    }
}
