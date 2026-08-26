package com.openwrtmgr.app.feature.clients

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.SignalWifi4Bar
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openwrtmgr.app.domain.model.Client
import com.openwrtmgr.app.domain.model.ConnectionType
import com.openwrtmgr.app.domain.repository.RouterRepository
import com.openwrtmgr.app.ui.components.EmptyState
import com.openwrtmgr.app.ui.components.ErrorState
import com.openwrtmgr.app.ui.components.SectionHeader
import com.openwrtmgr.app.ui.components.SkeletonLoading

/** Merged DHCP + Wi-Fi client list, grouped by connection type. Empty state and error state are real, not TODOs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientsScreen(repository: RouterRepository, profileId: Long) {
    val viewModel: ClientsViewModel = viewModel(
        factory = viewModelFactory { initializer { ClientsViewModel(repository, profileId) } },
    )
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Devices") },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        Crossfade(targetState = state, label = "clients") { s ->
            when (s) {
                is ClientsUiState.Loading -> SkeletonLoading(padding)
                is ClientsUiState.Error -> ErrorState(padding, s.message, title = "Couldn't load devices", onRetry = viewModel::refresh)
                is ClientsUiState.Loaded -> if (s.clients.isEmpty()) {
                    EmptyState(
                        padding,
                        icon = Icons.Default.Devices,
                        title = "No devices found",
                        message = "No DHCP leases or associated Wi-Fi clients right now. A device with a static IP that never requested a lease won't appear here.",
                    )
                } else {
                    val wireless = s.clients.filter { it.connectionType == ConnectionType.WIRELESS }
                    val wired = s.clients.filter { it.connectionType != ConnectionType.WIRELESS }
                    LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
                        if (wireless.isNotEmpty()) {
                            item { SectionHeader("Wireless · ${wireless.size}") }
                            items(wireless, key = { it.macAddress }) { ClientRow(it) }
                        }
                        if (wired.isNotEmpty()) {
                            item { SectionHeader("Wired · ${wired.size}") }
                            items(wired, key = { it.macAddress }) { ClientRow(it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClientRow(client: Client) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Card(
                shape = androidx.compose.foundation.shape.CircleShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.size(40.dp),
            ) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (client.connectionType == ConnectionType.WIRELESS) Icons.Default.SignalWifi4Bar else Icons.Default.SettingsEthernet,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp)) {
                Text(client.hostname ?: client.ipAddress ?: client.macAddress, style = MaterialTheme.typography.titleSmall)
                val details = buildList {
                    client.ipAddress?.let { add(it) }
                    add(client.macAddress)
                    client.wifi?.signalDbm?.let { add(signalLabel(it)) }
                }
                Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** A short human label instead of a bare dBm number — most users don't know what -67 dBm means. */
private fun signalLabel(dbm: Int): String {
    val quality = when {
        dbm >= -50 -> "Excellent"
        dbm >= -60 -> "Good"
        dbm >= -70 -> "Fair"
        else -> "Weak"
    }
    return "$quality ($dbm dBm)"
}
