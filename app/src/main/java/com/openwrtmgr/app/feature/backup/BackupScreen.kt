package com.openwrtmgr.app.feature.backup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openwrtmgr.app.domain.repository.RouterRepository
import com.openwrtmgr.app.ui.components.ConfirmDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * `sysupgrade -b`/`-r` over the SSH+SFTP transport, with the user picking where the archive
 * goes/comes from via the system file picker (Storage Access Framework) — no app-private storage
 * to manage, no extra permissions needed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(repository: RouterRepository, profileId: Long, onBack: () -> Unit) {
    val viewModel: BackupViewModel = viewModel(factory = viewModelFactory { initializer { BackupViewModel(repository, profileId) } })
    val status by viewModel.status.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(status) {
        (status as? BackupStatus.Message)?.let { snackbarHostState.showSnackbar(it.text); viewModel.consumeMessage() }
    }

    val backupFilename = remember {
        "openwrt-backup-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.tar.gz"
    }
    val createBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.backup { archive ->
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(archive) }
            }
        }
    }
    val openRestoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingRestoreUri = uri
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup & Restore") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val working = status is BackupStatus.Working
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Backup", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Runs `sysupgrade -b` on the router and saves the resulting archive wherever you choose.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = { createBackupLauncher.launch(backupFilename) }, enabled = !working) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Create Backup")
                    }
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Restore", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Uploads a previously-saved archive and applies it with `sysupgrade -r`. The router reboots afterward.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = { openRestoreLauncher.launch(arrayOf("*/*")) }, enabled = !working) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Choose Backup File…")
                    }
                }
            }
            if (working) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                    Text("Working — this can take a minute…")
                }
            }
        }
    }

    pendingRestoreUri?.let { uri ->
        ConfirmDialog(
            title = "Restore this backup?",
            message = "This overwrites the router's current configuration and reboots it. Make sure this is the right file.",
            confirmLabel = "Restore",
            destructive = true,
            onDismiss = { pendingRestoreUri = null },
            onConfirm = {
                pendingRestoreUri = null
                scope.launch {
                    val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                    if (bytes != null) viewModel.restore(bytes)
                }
            },
        )
    }
}
