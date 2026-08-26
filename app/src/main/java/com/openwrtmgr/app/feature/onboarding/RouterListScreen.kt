package com.openwrtmgr.app.feature.onboarding

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.openwrtmgr.app.domain.model.RouterProfile
import com.openwrtmgr.app.domain.repository.RouterRepository
import com.openwrtmgr.app.ui.components.EmptyState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Section 6/7 — saved router profiles, add flow. No scanning/mDNS yet: manual entry only (Phase 2 adds discovery). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouterListScreen(
    repository: RouterRepository,
    onRouterSelected: (Long) -> Unit,
) {
    val profiles by repository.observeProfiles().collectAsState(initial = emptyList())
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Your Routers") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add router")
            }
        },
    ) { padding ->
        if (profiles.isEmpty()) {
            EmptyState(
                padding,
                icon = Icons.Default.Router,
                title = "No routers yet",
                message = "Add your OpenWrt router's address and credentials to get started.",
                actionLabel = "Add Router",
                onAction = { showAddDialog = true },
            )
        } else {
            LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
                items(profiles, key = { it.id }) { profile ->
                    RouterRow(profile, onClick = { onRouterSelected(profile.id) })
                }
            }
        }
    }

    if (showAddDialog) {
        AddRouterDialog(
            onDismiss = { showAddDialog = false },
            onSave = { _, _ -> showAddDialog = false },
            repository = repository,
        )
    }
}

private val lastConnectedFormatter = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

@Composable
private fun RouterRow(profile: RouterProfile, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), onClick = onClick) {
        ListItem(
            headlineContent = { Text(profile.name) },
            supportingContent = {
                val lastConnected = profile.lastConnectedEpochMillis
                    ?.let { "Last connected ${lastConnectedFormatter.format(Date(it))}" }
                    ?: "Never connected"
                Text("${profile.baseUrl} · $lastConnected")
            },
            leadingContent = { Icon(Icons.Default.Router, contentDescription = null) },
            trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddRouterDialog(
    repository: RouterRepository,
    onDismiss: () -> Unit,
    onSave: (RouterProfile, String) -> Unit,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf(detectGatewayAddress(context) ?: "192.168.1.1") }
    var port by remember { mutableStateOf("80") }
    var useHttps by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("root") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Router") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(host, { host = it }, label = { Text("Address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    port,
                    { port = it.filter(Char::isDigit) },
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Use HTTPS", style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = useHttps,
                        onCheckedChange = { enabled ->
                            useHttps = enabled
                            // A small courtesy: flip the usual default port along with the toggle,
                            // but leave it alone if the admin already typed something non-default.
                            if (port == "80" && enabled) port = "443"
                            if (port == "443" && !enabled) port = "80"
                        },
                    )
                }
                OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    password,
                    { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                AnimatedVisibility(visible = error != null) {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && host.isNotBlank() && !saving,
                onClick = {
                    saving = true
                    error = null
                    val profile = RouterProfile(
                        name = name,
                        host = host,
                        port = port.toIntOrNull() ?: 80,
                        useHttps = useHttps,
                        username = username,
                    )
                    scope.launch {
                        runCatching { repository.addProfile(profile, password) }
                            .onSuccess { onSave(profile, password) }
                            .onFailure {
                                saving = false
                                error = it.message ?: "Could not save router"
                            }
                    }
                },
            ) {
                if (saving) CircularProgressIndicator(modifier = Modifier.padding(4.dp)) else Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
