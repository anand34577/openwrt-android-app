package com.openwrtmgr.app.feature.system

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
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
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openwrtmgr.app.domain.model.LogEntry
import com.openwrtmgr.app.domain.model.LogSeverity
import com.openwrtmgr.app.domain.model.Package
import com.openwrtmgr.app.domain.model.ServiceStatus
import com.openwrtmgr.app.domain.repository.RouterRepository
import com.openwrtmgr.app.ui.components.EmptyState
import com.openwrtmgr.app.ui.components.ErrorState
import com.openwrtmgr.app.ui.components.SkeletonLoading
import com.openwrtmgr.app.ui.components.StatusDot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class SystemSubScreen { SERVICES, LOGS, PACKAGES }

/** Section 19/20/21 — services (read-only), packages (`apk`, over SSH), and the system log. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemScreen(repository: RouterRepository, profileId: Long, onBack: () -> Unit) {
    val viewModel: SystemViewModel = viewModel(
        factory = viewModelFactory { initializer { SystemViewModel(repository, profileId) } },
    )
    var sub by remember { mutableStateOf(SystemSubScreen.SERVICES) }

    when (sub) {
        SystemSubScreen.LOGS -> LogsScreen(viewModel, onBack = { sub = SystemSubScreen.SERVICES })
        SystemSubScreen.PACKAGES -> PackagesScreen(viewModel, onBack = { sub = SystemSubScreen.SERVICES })
        SystemSubScreen.SERVICES -> ServicesScreen(
            viewModel,
            onBack = onBack,
            onOpenLogs = { sub = SystemSubScreen.LOGS },
            onOpenPackages = { sub = SystemSubScreen.PACKAGES },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServicesScreen(
    viewModel: SystemViewModel,
    onBack: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenPackages: () -> Unit,
) {
    val state by viewModel.services.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Services") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back to routers") }
                },
                actions = {
                    IconButton(onClick = onOpenPackages) { Icon(Icons.Default.Inventory2, contentDescription = "Packages") }
                    IconButton(onClick = onOpenLogs) { Icon(Icons.Default.Article, contentDescription = "View logs") }
                    IconButton(onClick = viewModel::refreshServices) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                },
            )
        },
    ) { padding ->
        Crossfade(targetState = state, label = "services") { s ->
            when (s) {
                is ServicesUiState.Loading -> SkeletonLoading(padding)
                is ServicesUiState.Error -> ErrorState(padding, s.message, onRetry = viewModel::refreshServices)
                is ServicesUiState.Loaded -> LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
                    item { StartStopNotice() }
                    items(s.services, key = { it.name }) { ServiceRow(it) }
                }
            }
        }
    }
}

@Composable
private fun StartStopNotice() {
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            "Status only. Starting/stopping/restarting a service needs shell access " +
                "(/etc/init.d/<service> restart) — coming with the SSH terminal.",
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ServiceRow(service: ServiceStatus) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(service.name, style = MaterialTheme.typography.titleSmall)
            StatusDot(service.running, if (service.running) "Running" else "Stopped", modifier = Modifier.padding(top = 2.dp))
            val detail = buildString {
                service.pid?.let { append("pid $it") }
                if (service.instanceCount > 1) {
                    if (isNotEmpty()) append(" · ")
                    append("${service.instanceCount} instances")
                }
            }
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogsScreen(viewModel: SystemViewModel, onBack: () -> Unit) {
    val state by viewModel.logs.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshLogs() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("System Log") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back to services") }
                },
                actions = {
                    IconButton(onClick = viewModel::refreshLogs) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                },
            )
        },
    ) { padding ->
        Crossfade(targetState = state, label = "logs") { s ->
            when (s) {
                is LogsUiState.Loading -> SkeletonLoading(padding)
                is LogsUiState.Error -> ErrorState(padding, s.message, onRetry = viewModel::refreshLogs)
                is LogsUiState.Loaded -> if (s.entries.isEmpty()) {
                    EmptyState(padding, icon = Icons.Default.Article, title = "Log is empty", message = "Nothing has been logged recently.")
                } else {
                    LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
                        items(s.entries) { LogRow(it) }
                    }
                }
            }
        }
    }
}

/**
 * Section 20 — install/remove run `apk` over SSH, one at a time, and NEVER silently: every
 * action is confirmed first and its real output is shown afterward, success or failure.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PackagesScreen(viewModel: SystemViewModel, onBack: () -> Unit) {
    val state by viewModel.packages.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshPackages() }

    var showInstallDialog by remember { mutableStateOf(false) }
    var pendingRemoval by remember { mutableStateOf<Package?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Packages") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back to services") }
                },
                actions = {
                    IconButton(onClick = { showInstallDialog = true }) { Icon(Icons.Default.Add, contentDescription = "Install a package") }
                    IconButton(onClick = viewModel::refreshPackageLists) { Icon(Icons.Default.Refresh, contentDescription = "apk update") }
                },
            )
        },
    ) { padding ->
        Crossfade(targetState = state, label = "packages") { s ->
            when (s) {
                is PackagesUiState.Loading -> SkeletonLoading(padding)
                is PackagesUiState.Error -> ErrorState(padding, s.message, onRetry = viewModel::refreshPackages)
                is PackagesUiState.Loaded -> Column {
                    AnimatedVisibility(visible = s.busy != null) { s.busy?.let { BusyBanner(it) } }
                    AnimatedVisibility(visible = s.lastActionOutput != null) { s.lastActionOutput?.let { ActionOutputCard(it) } }
                    LazyColumn(contentPadding = padding, modifier = Modifier.weight(1f)) {
                        items(s.packages, key = { it.name }) { pkg ->
                            PackageRow(pkg, busy = s.busy != null, onRemove = { pendingRemoval = pkg })
                        }
                    }
                }
            }
        }
    }

    if (showInstallDialog) {
        InstallPackageDialog(
            onDismiss = { showInstallDialog = false },
            onInstall = { name ->
                showInstallDialog = false
                viewModel.installPackage(name)
            },
        )
    }
    pendingRemoval?.let { pkg ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Remove ${pkg.name}?") },
            text = { Text("This runs apk del ${pkg.name} on the router. Some packages are required by others — removal can fail safely, or break dependent functionality if forced.") },
            confirmButton = {
                TextButton(onClick = { viewModel.removePackage(pkg.name); pendingRemoval = null }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BusyBanner(label: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
        Text("Running: $label")
    }
}

/** Section 20 — "Show progress and errors clearly," the router's own output, not a summary. */
@Composable
private fun ActionOutputCard(output: String) {
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            output,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun PackageRow(pkg: Package, busy: Boolean, onRemove: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(pkg.name, style = MaterialTheme.typography.titleSmall)
                val detail = if (pkg.isUpgradable) {
                    "${pkg.installedVersion} → ${pkg.availableVersion}"
                } else {
                    pkg.installedVersion
                }
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onRemove, enabled = !busy) {
                Icon(Icons.Default.Delete, contentDescription = "Remove ${pkg.name}")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstallPackageDialog(onDismiss: () -> Unit, onInstall: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Install Package") },
        text = {
            OutlinedTextField(
                name,
                { name = it },
                label = { Text("Package name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onInstall(name.trim()) }, enabled = name.isNotBlank()) { Text("Install") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

/** Section 21 — terminal-ish aesthetic for raw log lines, color-coded by severity, monospace. */
@Composable
private fun LogRow(entry: LogEntry) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            timeFormatter.format(Date(entry.epochSeconds * 1000)),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            entry.message,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = severityColor(entry.severity),
        )
    }
}

private fun severityColor(severity: LogSeverity): Color = when (severity) {
    LogSeverity.EMERGENCY, LogSeverity.ALERT, LogSeverity.CRITICAL, LogSeverity.ERROR -> Color(0xFFC62828)
    LogSeverity.WARNING -> Color(0xFFEF6C00)
    LogSeverity.NOTICE, LogSeverity.INFO -> Color.Unspecified
    LogSeverity.DEBUG -> Color(0xFF9E9E9E)
}
