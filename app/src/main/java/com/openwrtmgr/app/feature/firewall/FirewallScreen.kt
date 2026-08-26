package com.openwrtmgr.app.feature.firewall

import androidx.compose.animation.Crossfade
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openwrtmgr.app.domain.model.FirewallZone
import com.openwrtmgr.app.domain.model.PortForward
import com.openwrtmgr.app.domain.model.TrafficRule
import com.openwrtmgr.app.domain.repository.RouterRepository
import com.openwrtmgr.app.ui.components.ConfirmDialog
import com.openwrtmgr.app.ui.components.EmptyState
import com.openwrtmgr.app.ui.components.ErrorState
import com.openwrtmgr.app.ui.components.InfoBanner
import com.openwrtmgr.app.ui.components.SkeletonLoading
import com.openwrtmgr.app.ui.components.StatusDot
import com.openwrtmgr.app.ui.components.UiState

private val PROTOCOL_OPTIONS = listOf("tcp", "udp", "tcpudp")
private val TARGET_OPTIONS = listOf("ACCEPT", "REJECT", "DROP")

/**
 * Firewall: Port Forwards (`config redirect`), Traffic Rules (`config rule`), and Zones
 * (`config zone`) — the three `/etc/config/firewall` section types this app edits. All three
 * only persist to UCI until "Apply changes" reloads the live firewall (see [FirewallViewModel]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirewallScreen(repository: RouterRepository, profileId: Long) {
    val viewModel: FirewallViewModel = viewModel(
        factory = viewModelFactory { initializer { FirewallViewModel(repository, profileId) } },
    )
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Port Forwards", "Traffic Rules", "Zones")
    val snackbarHostState = remember { SnackbarHostState() }
    val actionMessage by viewModel.actionMessage.collectAsState()
    val reloading by viewModel.reloading.collectAsState()

    LaunchedEffect(actionMessage) {
        actionMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeActionMessage()
        }
    }

    Scaffold(
        topBar = {
            Column {
                androidx.compose.material3.TopAppBar(title = { Text("Firewall") })
                PrimaryTabRow(selectedTabIndex = tab) {
                    tabs.forEachIndexed { index, label ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
                    }
                }
            }
        },
        floatingActionButton = { FirewallFab(tab = tab, viewModel = viewModel) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            InfoBanner(
                "Changes here save to the router's configuration but don't take effect on the live " +
                    "firewall until it's reloaded.",
                actionLabel = if (reloading) "Reloading…" else "Apply changes",
                onAction = if (reloading) null else viewModel::reloadFirewall,
            )
            Crossfade(targetState = tab, label = "firewallTab") { index ->
                when (index) {
                    0 -> PortForwardsTab(viewModel, PaddingValues(0.dp))
                    1 -> TrafficRulesTab(viewModel, PaddingValues(0.dp))
                    else -> ZonesTab(viewModel, PaddingValues(0.dp))
                }
            }
        }
    }
}

@Composable
private fun FirewallFab(tab: Int, viewModel: FirewallViewModel) {
    var showDialog by remember(tab) { mutableStateOf(false) }
    FloatingActionButton(onClick = { showDialog = true }) {
        Icon(Icons.Default.Add, contentDescription = "Add")
    }
    if (showDialog) {
        when (tab) {
            0 -> PortForwardDialog(existing = null, onDismiss = { showDialog = false }) {
                viewModel.savePortForward(it)
                showDialog = false
            }
            1 -> TrafficRuleDialog(existing = null, onDismiss = { showDialog = false }) {
                viewModel.saveTrafficRule(it)
                showDialog = false
            }
            else -> ZoneDialog(existing = null, onDismiss = { showDialog = false }) {
                viewModel.saveZone(it)
                showDialog = false
            }
        }
    }
}

// ---------- Port Forwards ----------

@Composable
private fun PortForwardsTab(viewModel: FirewallViewModel, padding: PaddingValues) {
    val state by viewModel.portForwards.collectAsState()
    var editing by remember { mutableStateOf<PortForward?>(null) }
    var deleting by remember { mutableStateOf<PortForward?>(null) }

    when (val s = state) {
        is UiState.Loading -> SkeletonLoading(padding)
        is UiState.Error -> ErrorState(padding, s.message, title = "Couldn't load rules", onRetry = viewModel::refreshPortForwards)
        is UiState.Loaded -> if (s.data.isEmpty()) {
            EmptyState(padding, Icons.Default.Router, "No port forwarding rules", "Tap + to forward a port from WAN to a device on your network.")
        } else {
            LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
                items(s.data, key = { it.uciSectionId ?: it.name }) { rule ->
                    PortForwardRow(rule, onEdit = { editing = rule }, onDelete = { deleting = rule })
                }
            }
        }
    }

    editing?.let { rule ->
        PortForwardDialog(existing = rule, onDismiss = { editing = null }) {
            viewModel.savePortForward(it)
            editing = null
        }
    }
    deleting?.let { rule ->
        ConfirmDialog(
            title = "Delete \"${rule.name}\"?",
            message = "This removes the port forwarding rule from the router's configuration.",
            confirmLabel = "Delete",
            destructive = true,
            onDismiss = { deleting = null },
            onConfirm = { viewModel.deletePortForward(rule); deleting = null },
        )
    }
}

@Composable
private fun PortForwardRow(rule: PortForward, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), onClick = onEdit) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(rule.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${rule.protocol.uppercase()} · WAN:${rule.externalPort} → ${rule.internalIp}:${rule.internalPort}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!rule.enabled) StatusDot(active = false, label = "Disabled")
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete rule") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortForwardDialog(existing: PortForward?, onDismiss: () -> Unit, onSave: (PortForward) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var protocol by remember { mutableStateOf(existing?.protocol ?: "tcp") }
    var externalPort by remember { mutableStateOf(existing?.externalPort ?: "") }
    var internalIp by remember { mutableStateOf(existing?.internalIp ?: "") }
    var internalPort by remember { mutableStateOf(existing?.internalPort ?: "") }

    val valid = name.isNotBlank() && externalPort.isNotBlank() && internalIp.isNotBlank() && internalPort.isNotBlank()

    FormDialog(
        title = if (existing == null) "New Rule" else "Edit Rule",
        valid = valid,
        onDismiss = onDismiss,
        onSave = {
            onSave(
                PortForward(
                    uciSectionId = existing?.uciSectionId,
                    name = name,
                    enabled = existing?.enabled ?: true,
                    protocol = protocol,
                    externalPort = externalPort,
                    internalIp = internalIp,
                    internalPort = internalPort,
                ),
            )
        },
    ) {
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        ProtocolDropdown(protocol) { protocol = it }
        OutlinedTextField(externalPort, { externalPort = it.filter(Char::isDigit) }, label = { Text("External port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(internalIp, { internalIp = it }, label = { Text("Internal IP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(internalPort, { internalPort = it.filter(Char::isDigit) }, label = { Text("Internal port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}

// ---------- Traffic Rules ----------

@Composable
private fun TrafficRulesTab(viewModel: FirewallViewModel, padding: PaddingValues) {
    val state by viewModel.trafficRules.collectAsState()
    var editing by remember { mutableStateOf<TrafficRule?>(null) }
    var deleting by remember { mutableStateOf<TrafficRule?>(null) }

    when (val s = state) {
        is UiState.Loading -> SkeletonLoading(padding)
        is UiState.Error -> ErrorState(padding, s.message, title = "Couldn't load rules", onRetry = viewModel::refreshTrafficRules)
        is UiState.Loaded -> if (s.data.isEmpty()) {
            EmptyState(padding, Icons.Default.Shield, "No traffic rules", "Tap + to allow or block traffic between zones, independent of port forwarding.")
        } else {
            LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
                items(s.data, key = { it.uciSectionId ?: it.name }) { rule ->
                    TrafficRuleRow(rule, onEdit = { editing = rule }, onDelete = { deleting = rule })
                }
            }
        }
    }

    editing?.let { rule ->
        TrafficRuleDialog(existing = rule, onDismiss = { editing = null }) {
            viewModel.saveTrafficRule(it)
            editing = null
        }
    }
    deleting?.let { rule ->
        ConfirmDialog(
            title = "Delete \"${rule.name}\"?",
            message = "This removes the traffic rule from the router's configuration.",
            confirmLabel = "Delete",
            destructive = true,
            onDismiss = { deleting = null },
            onConfirm = { viewModel.deleteTrafficRule(rule); deleting = null },
        )
    }
}

@Composable
private fun TrafficRuleRow(rule: TrafficRule, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), onClick = onEdit) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(rule.name, style = MaterialTheme.typography.titleSmall)
                val dest = rule.destZone ?: "this router"
                Text(
                    "${rule.sourceZone} → $dest · ${rule.protocol.uppercase()}${if (rule.destPort.isNotBlank()) ":${rule.destPort}" else ""} · ${rule.target}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!rule.enabled) StatusDot(active = false, label = "Disabled")
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete rule") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrafficRuleDialog(existing: TrafficRule?, onDismiss: () -> Unit, onSave: (TrafficRule) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var sourceZone by remember { mutableStateOf(existing?.sourceZone ?: "wan") }
    var destZone by remember { mutableStateOf(existing?.destZone ?: "") }
    var protocol by remember { mutableStateOf(existing?.protocol ?: "tcp") }
    var destPort by remember { mutableStateOf(existing?.destPort ?: "") }
    var target by remember { mutableStateOf(existing?.target ?: "ACCEPT") }

    FormDialog(
        title = if (existing == null) "New Traffic Rule" else "Edit Traffic Rule",
        valid = name.isNotBlank() && sourceZone.isNotBlank(),
        onDismiss = onDismiss,
        onSave = {
            onSave(
                TrafficRule(
                    uciSectionId = existing?.uciSectionId,
                    name = name,
                    enabled = existing?.enabled ?: true,
                    sourceZone = sourceZone,
                    destZone = destZone.takeIf { it.isNotBlank() },
                    protocol = protocol,
                    destPort = destPort,
                    target = target,
                ),
            )
        },
    ) {
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(sourceZone, { sourceZone = it }, label = { Text("Source zone (e.g. wan)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(destZone, { destZone = it }, label = { Text("Dest zone (blank = this router)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        ProtocolDropdown(protocol) { protocol = it }
        OutlinedTextField(destPort, { destPort = it.filter(Char::isDigit) }, label = { Text("Dest port (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        SimpleDropdown("Action", TARGET_OPTIONS, target) { target = it }
    }
}

// ---------- Zones ----------

@Composable
private fun ZonesTab(viewModel: FirewallViewModel, padding: PaddingValues) {
    val state by viewModel.zones.collectAsState()
    val vlans by viewModel.vlans.collectAsState()
    var editing by remember { mutableStateOf<FirewallZone?>(null) }
    var deleting by remember { mutableStateOf<FirewallZone?>(null) }

    when (val s = state) {
        is UiState.Loading -> SkeletonLoading(padding)
        is UiState.Error -> ErrorState(padding, s.message, title = "Couldn't load zones", onRetry = viewModel::refreshZones)
        is UiState.Loaded -> if (s.data.isEmpty() && vlans.isEmpty()) {
            EmptyState(padding, Icons.Default.Shield, "No firewall zones", "Zones group interfaces (lan, wan, guest...) for traffic rules to reference.")
        } else {
            LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
                items(s.data, key = { it.uciSectionId ?: it.name }) { zone ->
                    ZoneRow(zone, onEdit = { editing = zone }, onDelete = { deleting = zone })
                }
                if (vlans.isNotEmpty()) {
                    item { VlanSectionHeader() }
                    items(vlans, key = { it.uciSectionId }) { VlanRow(it) }
                }
            }
        }
    }

    editing?.let { zone ->
        ZoneDialog(existing = zone, onDismiss = { editing = null }) {
            viewModel.saveZone(it)
            editing = null
        }
    }
    deleting?.let { zone ->
        ConfirmDialog(
            title = "Delete zone \"${zone.name}\"?",
            message = "Traffic rules and port forwards referencing this zone will stop working correctly.",
            confirmLabel = "Delete",
            destructive = true,
            onDismiss = { deleting = null },
            onConfirm = { viewModel.deleteZone(zone); deleting = null },
        )
    }
}

@Composable
private fun ZoneRow(zone: FirewallZone, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), onClick = onEdit) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(zone.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "In: ${zone.input} · Out: ${zone.output} · Fwd: ${zone.forward}" + if (zone.masq) " · NAT" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (zone.networks.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                        zone.networks.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                    }
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete zone") }
        }
    }
}

@Composable
private fun VlanSectionHeader() {
    com.openwrtmgr.app.ui.components.SectionHeader("VLAN devices (read-only)")
}

/** Read-only: full VLAN authoring (creating/editing 802.1q devices) isn't built yet — see the app's README. */
@Composable
private fun VlanRow(vlan: com.openwrtmgr.app.domain.model.VlanDevice) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(vlan.name, style = MaterialTheme.typography.titleSmall)
            Text(
                "VLAN ${vlan.vlanId ?: "?"} on ${vlan.baseDevice ?: "?"} · ${vlan.type}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZoneDialog(existing: FirewallZone?, onDismiss: () -> Unit, onSave: (FirewallZone) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var input by remember { mutableStateOf(existing?.input ?: "REJECT") }
    var output by remember { mutableStateOf(existing?.output ?: "ACCEPT") }
    var forward by remember { mutableStateOf(existing?.forward ?: "REJECT") }
    var masq by remember { mutableStateOf(existing?.masq ?: false) }
    var networks by remember { mutableStateOf(existing?.networks?.joinToString(" ") ?: "") }

    FormDialog(
        title = if (existing == null) "New Zone" else "Edit Zone",
        valid = name.isNotBlank(),
        onDismiss = onDismiss,
        onSave = {
            onSave(
                FirewallZone(
                    uciSectionId = existing?.uciSectionId,
                    name = name,
                    input = input,
                    output = output,
                    forward = forward,
                    masq = masq,
                    networks = networks.split(Regex("\\s+")).filter { it.isNotBlank() },
                ),
            )
        },
    ) {
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        SimpleDropdown("Input", TARGET_OPTIONS, input) { input = it }
        SimpleDropdown("Output", TARGET_OPTIONS, output) { output = it }
        SimpleDropdown("Forward", TARGET_OPTIONS, forward) { forward = it }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Masquerade (NAT)")
            Switch(checked = masq, onCheckedChange = { masq = it })
        }
        OutlinedTextField(networks, { networks = it }, label = { Text("Networks (space-separated)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}

// ---------- Shared form pieces ----------

@Composable
private fun FormDialog(
    title: String,
    valid: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content) },
        confirmButton = { TextButton(enabled = valid, onClick = onSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProtocolDropdown(value: String, onChange: (String) -> Unit) {
    SimpleDropdown("Protocol", PROTOCOL_OPTIONS, value, onChange)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleDropdown(label: String, options: List<String>, value: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onChange(option); expanded = false })
            }
        }
    }
}
