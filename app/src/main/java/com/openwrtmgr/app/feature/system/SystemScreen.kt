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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.openwrtmgr.app.ui.components.ConfirmDialog
import com.openwrtmgr.app.ui.components.EmptyState
import com.openwrtmgr.app.ui.components.ErrorState
import com.openwrtmgr.app.ui.components.SkeletonLoading
import com.openwrtmgr.app.ui.components.StatusDot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class SystemSubScreen { SERVICES, LOGS, PACKAGES }
private enum class ServiceActionType { START, STOP, RESTART }
private data class PendingServiceAction(val service: ServiceStatus, val type: ServiceActionType)

/** Services (real start/stop/restart over SSH), packages (`apk`, over SSH), and the system log. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemScreen(repository: RouterRepository, profileId: Long) {
    val viewModel: SystemViewModel = viewModel(
        factory = viewModelFactory { initializer { SystemViewModel(repository, profileId) } },
    )
    var sub by remember { mutableStateOf(SystemSubScreen.SERVICES) }

    when (sub) {
        SystemSubScreen.LOGS -> LogsScreen(viewModel, onBack = { sub = SystemSubScreen.SERVICES })
        SystemSubScreen.PACKAGES -> PackagesScreen(viewModel, onBack = { sub = SystemSubScreen.SERVICES })
        SystemSubScreen.SERVICES -> ServicesScreen(
            viewModel,
            onOpenLogs = { sub = SystemSubScreen.LOGS },
            onOpenPackages = { sub = SystemSubScreen.PACKAGES },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServicesScreen(
    viewModel: SystemViewModel,
    onOpenLogs: () -> Unit,
    onOpenPackages: () -> Unit,
) {
    val state by viewModel.services.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingAction by remember { mutableStateOf<PendingServiceAction?>(null) }

    val actionMessage = (state as? ServicesUiState.Loaded)?.actionMessage
    LaunchedEffect(actionMessage) {
        actionMessage?.let { snackbarHostState.showSnackbar(it); viewModel.consumeServiceActionMessage() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Services") },
                actions = {
                    IconButton(onClick = onOpenPackages) { Icon(Icons.Default.Inventory2, contentDescription = "Packages") }
                    IconButton(onClick = onOpenLogs) { Icon(Icons.Default.Article, contentDescription = "View logs") }
                    IconButton(onClick = viewModel::refreshServices) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Crossfade(targetState = state, label = "services") { s ->
            when (s) {
                is ServicesUiState.Loading -> SkeletonLoading(padding)
                is ServicesUiState.Error -> ErrorState(padding, s.message, onRetry = viewModel::refreshServices)
                is ServicesUiState.Loaded -> LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
                    items(s.services, key = { it.name }) { service ->
                        ServiceRow(
                            service,
                            busy = s.busy == service.name,
                            onStart = { pendingAction = PendingServiceAction(service, ServiceActionType.START) },
                            onStop = { pendingAction = PendingServiceAction(service, ServiceActionType.STOP) },
                            onRestart = { pendingAction = PendingServiceAction(service, ServiceActionType.RESTART) },
                        )
                    }
                }
            }
        }
    }

    pendingAction?.let { action ->
        val verb = when (action.type) { ServiceActionType.START -> "start"; ServiceActionType.STOP -> "stop"; ServiceActionType.RESTART -> "restart" }
        ConfirmDialog(
            title = "${verb.replaceFirstChar { it.uppercase() }} ${action.service.name}?",
            message = "This runs /etc/init.d/${action.service.name} $verb on the router over SSH.",
            confirmLabel = verb.replaceFirstChar { it.uppercase() },
            destructive = action.type == ServiceActionType.STOP,
            onDismiss = { pendingAction = null },
            onConfirm = {
                when (action.type) {
                    ServiceActionType.START -> viewModel.startService(action.service.name)
                    ServiceActionType.STOP -> viewModel.stopService(action.service.name)
                    ServiceActionType.RESTART -> viewModel.restartService(action.service.name)
                }
                pendingAction = null
            },
        )
    }
}

@Composable
private fun ServiceRow(service: ServiceStatus, busy: Boolean, onStart: () -> Unit, onStop: () -> Unit, onRestart: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
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
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.padding(8.dp))
            } else {
                if (service.running) {
                    IconButton(onClick = onRestart) { Icon(Icons.Default.Replay, contentDescription = "Restart ${service.name}") }
                    IconButton(onClick = onStop) { Icon(Icons.Default.Stop, contentDescription = "Stop ${service.name}") }
                } else {
                    IconButton(onClick = onStart) { Icon(Icons.Default.PlayArrow, contentDescription = "Start ${service.name}") }
                }
            }
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
    var pendingInstall by remember { mutableStateOf<String?>(null) }
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
                pendingInstall = name
            },
        )
    }
    pendingInstall?.let { name ->
        ConfirmDialog(
            title = "Install $name?",
            message = "This runs apk add $name on the router.",
            confirmLabel = "Install",
            onDismiss = { pendingInstall = null },
            onConfirm = { viewModel.installPackage(name); pendingInstall = null },
        )
    }
    pendingRemoval?.let { pkg ->
        ConfirmDialog(
            title = "Remove ${pkg.name}?",
            message = "This runs apk del ${pkg.name} on the router. Some packages are required by others — removal can fail safely, or break dependent functionality if forced.",
            confirmLabel = "Remove",
            destructive = true,
            onDismiss = { pendingRemoval = null },
            onConfirm = { viewModel.removePackage(pkg.name); pendingRemoval = null },
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

/** Terminal-ish aesthetic for raw log lines, color-coded by severity, monospace. */
@Composable
private fun LogRow(entry: LogEntry) {
    val colors = com.openwrtmgr.app.ui.theme.LocalStatusColors.current
    val severityColor = when (entry.severity) {
        LogSeverity.EMERGENCY, LogSeverity.ALERT, LogSeverity.CRITICAL, LogSeverity.ERROR -> colors.danger
        LogSeverity.WARNING -> colors.warning
        LogSeverity.NOTICE, LogSeverity.INFO -> MaterialTheme.colorScheme.onSurface
        LogSeverity.DEBUG -> colors.neutral
    }
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
            color = severityColor,
        )
    }
}
