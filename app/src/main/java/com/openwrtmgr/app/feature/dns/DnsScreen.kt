package com.openwrtmgr.app.feature.dns

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
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openwrtmgr.app.domain.model.DnsRecord
import com.openwrtmgr.app.domain.repository.RouterRepository
import com.openwrtmgr.app.ui.components.ConfirmDialog
import com.openwrtmgr.app.ui.components.EmptyState
import com.openwrtmgr.app.ui.components.ErrorState
import com.openwrtmgr.app.ui.components.InfoBanner
import com.openwrtmgr.app.ui.components.SkeletonLoading
import com.openwrtmgr.app.ui.components.UiState

/** Static dnsmasq hostname -> IP records (`config domain` sections in the `dhcp` UCI config). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsScreen(repository: RouterRepository, profileId: Long, onBack: () -> Unit) {
    val viewModel: DnsViewModel = viewModel(factory = viewModelFactory { initializer { DnsViewModel(repository, profileId) } })
    val state by viewModel.state.collectAsState()
    val actionMessage by viewModel.actionMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showAddDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<DnsRecord?>(null) }
    var deleting by remember { mutableStateOf<DnsRecord?>(null) }

    LaunchedEffect(actionMessage) {
        actionMessage?.let { snackbarHostState.showSnackbar(it); viewModel.consumeActionMessage() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DNS Management") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) { Icon(Icons.Default.Add, contentDescription = "Add record") }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            InfoBanner("Records here take effect after dnsmasq restarts on the router (a reboot, or SSH: /etc/init.d/dnsmasq restart).")
            when (val s = state) {
                is UiState.Loading -> SkeletonLoading(androidx.compose.foundation.layout.PaddingValues(0.dp))
                is UiState.Error -> ErrorState(androidx.compose.foundation.layout.PaddingValues(0.dp), s.message, title = "Couldn't load DNS records", onRetry = viewModel::refresh)
                is UiState.Loaded -> if (s.data.isEmpty()) {
                    EmptyState(androidx.compose.foundation.layout.PaddingValues(0.dp), Icons.Default.Dns, "No DNS records", "Tap + to give a device a fixed hostname on your network.")
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(s.data, key = { it.uciSectionId ?: it.hostname }) { record ->
                            DnsRow(record, onEdit = { editing = record }, onDelete = { deleting = record })
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        DnsDialog(existing = null, onDismiss = { showAddDialog = false }) { viewModel.save(it); showAddDialog = false }
    }
    editing?.let { record ->
        DnsDialog(existing = record, onDismiss = { editing = null }) { viewModel.save(it); editing = null }
    }
    deleting?.let { record ->
        ConfirmDialog(
            title = "Delete \"${record.hostname}\"?",
            message = "This removes the static DNS record from the router's configuration.",
            confirmLabel = "Delete",
            destructive = true,
            onDismiss = { deleting = null },
            onConfirm = { viewModel.delete(record); deleting = null },
        )
    }
}

@Composable
private fun DnsRow(record: DnsRecord, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), onClick = onEdit) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(record.hostname, style = MaterialTheme.typography.titleSmall)
                Text(record.ipAddress, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete record") }
        }
    }
}

@Composable
private fun DnsDialog(existing: DnsRecord?, onDismiss: () -> Unit, onSave: (DnsRecord) -> Unit) {
    var hostname by remember { mutableStateOf(existing?.hostname ?: "") }
    var ip by remember { mutableStateOf(existing?.ipAddress ?: "") }
    val valid = hostname.isNotBlank() && ip.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New DNS Record" else "Edit DNS Record") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(hostname, { hostname = it }, label = { Text("Hostname") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ip, { ip = it }, label = { Text("IP address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(DnsRecord(existing?.uciSectionId, hostname, ip)) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
