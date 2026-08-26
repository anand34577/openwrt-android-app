package com.openwrtmgr.app.feature.firewall

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.openwrtmgr.app.domain.model.PortForward
import com.openwrtmgr.app.domain.repository.RouterRepository
import com.openwrtmgr.app.ui.components.EmptyState
import com.openwrtmgr.app.ui.components.ErrorState
import com.openwrtmgr.app.ui.components.InfoBanner
import com.openwrtmgr.app.ui.components.SkeletonLoading
import com.openwrtmgr.app.ui.components.StatusDot

/**
 * Section 17 — port forwarding via `config redirect` sections in `/etc/config/firewall`.
 * Rules save/delete for real; see the in-app banner re: applying to the live ruleset.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortForwardingScreen(repository: RouterRepository, profileId: Long, onBack: () -> Unit) {
    val viewModel: PortForwardingViewModel = viewModel(
        factory = viewModelFactory { initializer { PortForwardingViewModel(repository, profileId) } },
    )
    val state by viewModel.state.collectAsState()
    var editingRule by remember { mutableStateOf<PortForward?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Port Forwarding") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back to routers") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add rule")
            }
        },
    ) { padding ->
        Crossfade(targetState = state, label = "portForwarding") { s ->
            when (s) {
                is PortForwardingUiState.Loading -> SkeletonLoading(padding)
                is PortForwardingUiState.Error -> ErrorState(padding, s.message, title = "Couldn't load rules", onRetry = viewModel::refresh)
                is PortForwardingUiState.Loaded -> Column {
                    InfoBanner(
                        "Saved rules take effect after the firewall service is restarted on the router " +
                            "(SSH: /etc/init.d/firewall reload, or a reboot). This app doesn't trigger that yet.",
                    )
                    AnimatedVisibility(visible = s.savingError != null) {
                        s.savingError?.let { ErrorSnackbar(it) }
                    }
                    if (s.rules.isEmpty()) {
                        EmptyState(
                            padding,
                            icon = Icons.Default.Router,
                            title = "No port forwarding rules",
                            message = "Tap + to forward a port from WAN to a device on your network.",
                        )
                    } else {
                        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
                            items(s.rules, key = { it.uciSectionId ?: it.name }) { rule ->
                                PortForwardRow(rule, onEdit = { editingRule = rule }, onDelete = { viewModel.delete(rule) })
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        PortForwardDialog(existing = null, onDismiss = { showAddDialog = false }) { rule ->
            viewModel.save(rule)
            showAddDialog = false
        }
    }
    editingRule?.let { rule ->
        PortForwardDialog(existing = rule, onDismiss = { editingRule = null }) { updated ->
            viewModel.save(updated)
            editingRule = null
        }
    }
}

@Composable
private fun ErrorSnackbar(message: String) {
    Snackbar(modifier = Modifier.padding(horizontal = 16.dp)) { Text(message) }
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New Rule" else "Edit Rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    protocol,
                    { protocol = it.lowercase() },
                    label = { Text("Protocol (tcp/udp/tcpudp)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    externalPort,
                    { externalPort = it.filter(Char::isDigit) },
                    label = { Text("External port") },
                    singleLine = true,
                )
                OutlinedTextField(internalIp, { internalIp = it }, label = { Text("Internal IP") }, singleLine = true)
                OutlinedTextField(
                    internalPort,
                    { internalPort = it.filter(Char::isDigit) },
                    label = { Text("Internal port") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
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
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
