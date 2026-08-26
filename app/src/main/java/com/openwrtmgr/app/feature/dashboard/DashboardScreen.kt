package com.openwrtmgr.app.feature.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openwrtmgr.app.domain.model.NetworkInterfaceInfo
import com.openwrtmgr.app.domain.model.SystemInfo
import com.openwrtmgr.app.domain.model.WifiRadio
import com.openwrtmgr.app.domain.repository.RouterRepository
import com.openwrtmgr.app.ui.components.ConfirmDialog
import com.openwrtmgr.app.ui.components.ErrorState
import com.openwrtmgr.app.ui.components.InfoBanner
import com.openwrtmgr.app.ui.components.SkeletonLoading
import com.openwrtmgr.app.ui.components.StatusDot
import java.util.concurrent.TimeUnit

/** Dashboard: system, interfaces, and Wi-Fi (capability-gated — hidden on wired-only routers). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(repository: RouterRepository, profileId: Long) {
    val viewModel: DashboardViewModel = viewModel(
        factory = viewModelFactory { initializer { DashboardViewModel(repository, profileId) } },
    )
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // One confirmation gate for every action that can sever the connection.
    var pendingAction by remember { mutableStateOf<PendingAction?>(null) }
    var rebooted by remember { mutableStateOf(false) }

    val actionError = (state as? DashboardUiState.Loaded)?.actionError
    LaunchedEffect(actionError) { actionError?.let { snackbarHostState.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dashboard") },
                actions = {
                    IconButton(onClick = { pendingAction = PendingAction.Reboot }) {
                        Icon(Icons.Default.PowerSettingsNew, contentDescription = "Reboot router")
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Crossfade(targetState = state, label = "dashboard") { s ->
            when (s) {
                is DashboardUiState.Loading -> SkeletonLoading(padding)
                is DashboardUiState.Error -> ErrorState(padding, s.message, title = "Unable to connect", onRetry = viewModel::refresh)
                is DashboardUiState.Loaded -> Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                    AnimatedVisibility(visible = rebooted) {
                        InfoBanner("Reboot requested. The router will be unreachable for a minute or two.")
                    }
                    DashboardContent(
                        info = s.systemInfo,
                        interfaces = s.interfaces,
                        wifiRadios = s.wifiRadios,
                        onRequestInterfaceToggle = { name, up -> pendingAction = PendingAction.SetInterface(name, up) },
                        onRequestRadioToggle = { device, enabled -> pendingAction = PendingAction.SetRadio(device, enabled) },
                    )
                }
            }
        }
    }

    pendingAction?.let { action ->
        ConfirmActionDialog(
            action = action,
            onDismiss = { pendingAction = null },
            onConfirm = {
                pendingAction = null
                when (action) {
                    is PendingAction.Reboot -> viewModel.reboot(onAccepted = { rebooted = true })
                    is PendingAction.SetInterface -> if (action.up) viewModel.setInterfaceUp(action.name) else viewModel.setInterfaceDown(action.name)
                    is PendingAction.SetRadio -> viewModel.setRadioEnabled(action.device, action.enabled)
                }
            },
        )
    }
}

private sealed interface PendingAction {
    data object Reboot : PendingAction
    data class SetInterface(val name: String, val up: Boolean) : PendingAction
    data class SetRadio(val device: String, val enabled: Boolean) : PendingAction
}

/** Section 26/33/34's warning-dialog pattern, one dialog for every dangerous action on this screen. */
@Composable
private fun ConfirmActionDialog(action: PendingAction, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val (title, message) = when (action) {
        is PendingAction.Reboot -> "Reboot Router?" to
            "All connected devices will temporarily lose network connectivity."
        is PendingAction.SetInterface -> (if (action.up) "Bring ${action.name} Up?" else "Bring ${action.name} Down?") to
            "If your phone is connected through this interface, you'll lose the connection to this router."
        is PendingAction.SetRadio -> (if (action.enabled) "Enable ${action.device}?" else "Disable ${action.device}?") to
            "If your phone is connected over this radio, it will disconnect immediately."
    }
    ConfirmDialog(
        title = title,
        message = message,
        confirmLabel = if (action is PendingAction.Reboot) "Reboot" else "Continue",
        destructive = true,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

@Composable
private fun DashboardContent(
    info: SystemInfo,
    interfaces: List<NetworkInterfaceInfo>,
    wifiRadios: List<WifiRadio>,
    onRequestInterfaceToggle: (name: String, up: Boolean) -> Unit,
    onRequestRadioToggle: (device: String, enabled: Boolean) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item { RouterCard(info) }
        item { SystemCard(info) }
        // Capability-gated: no card at all when the router has no radios (section 43), not an empty/broken one.
        if (wifiRadios.isNotEmpty()) {
            item { WifiCard(wifiRadios, onRequestRadioToggle) }
        }
        items(interfaces) { InterfaceRow(it, onRequestInterfaceToggle) }
    }
}

@Composable
private fun WifiCard(radios: List<WifiRadio>, onRequestToggle: (device: String, enabled: Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Wi-Fi", style = MaterialTheme.typography.titleMedium)
            radios.forEach { radio ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).animateContentSize(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(radio.ssid ?: radio.device, style = MaterialTheme.typography.titleSmall)
                        StatusDot(radio.isActive, if (radio.isActive) "Active" else "Disabled", modifier = Modifier.padding(top = 2.dp))
                        val details = buildList {
                            radio.channel?.let { add("Ch $it") }
                            add(radio.encryption)
                            radio.signalQualityPercent?.let { add("$it% signal") }
                            radio.bitrateMbps?.let { add("%.0f Mbps".format(it)) }
                        }
                        Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = radio.isActive,
                        onCheckedChange = { onRequestToggle(radio.device, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RouterCard(info: SystemInfo) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(info.hostname, style = MaterialTheme.typography.titleLarge)
            Text(info.model, style = MaterialTheme.typography.bodyMedium)
            Text("OpenWrt ${info.openWrtVersion} · ${info.architecture}", style = MaterialTheme.typography.bodySmall)
            Text("Uptime: ${formatUptime(info.uptimeSeconds)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SystemCard(info: SystemInfo) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("System", style = MaterialTheme.typography.titleMedium)
            val usedPercent = if (info.memoryTotalBytes > 0) {
                100 - (info.memoryFreeBytes * 100 / info.memoryTotalBytes)
            } else 0
            Text("Memory used: $usedPercent%")
            Text("Load average: ${info.loadAverage.joinToString(", ") { "%.2f".format(it) }}")
        }
    }
}

@Composable
private fun InterfaceRow(iface: NetworkInterfaceInfo, onRequestToggle: (name: String, up: Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(iface.name, style = MaterialTheme.typography.titleSmall)
                    StatusDot(iface.isUp, if (iface.isUp) "Up" else "Down")
                }
                Text("${iface.protocol} · ${iface.device ?: "no device"}", style = MaterialTheme.typography.bodySmall)
                iface.ipv4Address?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            TextButton(onClick = { onRequestToggle(iface.name, !iface.isUp) }) {
                Text(if (iface.isUp) "Bring down" else "Bring up")
            }
        }
    }
}

private fun formatUptime(seconds: Long): String {
    val days = TimeUnit.SECONDS.toDays(seconds)
    val hours = TimeUnit.SECONDS.toHours(seconds) % 24
    val minutes = TimeUnit.SECONDS.toMinutes(seconds) % 60
    return buildString {
        if (days > 0) append("${days}d ")
        append("${hours}h ${minutes}m")
    }
}
