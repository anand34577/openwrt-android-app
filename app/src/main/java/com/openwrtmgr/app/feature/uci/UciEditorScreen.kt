package com.openwrtmgr.app.feature.uci

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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
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
import com.openwrtmgr.app.domain.model.COMMON_UCI_CONFIGS
import com.openwrtmgr.app.domain.model.UciSection
import com.openwrtmgr.app.domain.repository.RouterRepository
import com.openwrtmgr.app.ui.components.ConfirmDialog
import com.openwrtmgr.app.ui.components.EmptyState
import com.openwrtmgr.app.ui.components.ErrorState
import com.openwrtmgr.app.ui.components.InfoBanner
import com.openwrtmgr.app.ui.components.SkeletonLoading
import com.openwrtmgr.app.ui.components.UiState

/**
 * Generic UCI config editor — works on any config/section, unlike the purpose-built screens
 * (Firewall, DNS). This can misconfigure the router if used carelessly, hence the warning banner
 * and the confirm-before-delete gate; there's no undo beyond re-editing by hand.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UciEditorScreen(repository: RouterRepository, profileId: Long, onBack: () -> Unit) {
    val viewModel: UciEditorViewModel = viewModel(factory = viewModelFactory { initializer { UciEditorViewModel(repository, profileId) } })
    val config by viewModel.config.collectAsState()
    val state by viewModel.state.collectAsState()
    val actionMessage by viewModel.actionMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<UciSection?>(null) }
    var deleting by remember { mutableStateOf<UciSection?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    LaunchedEffect(actionMessage) {
        actionMessage?.let { snackbarHostState.showSnackbar(it); viewModel.consumeActionMessage() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("UCI Raw Editor") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) { Icon(Icons.Default.Add, contentDescription = "Add section") }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            InfoBanner("Editing raw UCI sections can misconfigure or lock you out of the router. Only change values you understand.")
            ConfigPicker(config, onSelect = viewModel::selectConfig)
            when (val s = state) {
                is UiState.Loading -> SkeletonLoading(PaddingValues(0.dp))
                is UiState.Error -> ErrorState(PaddingValues(0.dp), s.message, title = "Couldn't load $config", onRetry = viewModel::load)
                is UiState.Loaded -> if (s.data.isEmpty()) {
                    EmptyState(PaddingValues(0.dp), Icons.Default.Terminal, "No sections", "The \"$config\" config has no sections, or doesn't exist on this router.")
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(s.data, key = { it.id }) { section ->
                            SectionRow(section, onEdit = { editing = section }, onDelete = { deleting = section })
                        }
                    }
                }
            }
        }
    }

    editing?.let { section ->
        EditSectionDialog(section, onDismiss = { editing = null }) { values ->
            viewModel.setValues(section.id, values)
            editing = null
        }
    }
    deleting?.let { section ->
        ConfirmDialog(
            title = "Delete section \"${section.id}\"?",
            message = "This permanently removes the ${section.type} section and everything in it from $config.",
            confirmLabel = "Delete",
            destructive = true,
            onDismiss = { deleting = null },
            onConfirm = { viewModel.deleteSection(section.id); deleting = null },
        )
    }
    if (showAddDialog) {
        AddSectionDialog(onDismiss = { showAddDialog = false }) { type ->
            viewModel.addSection(type)
            showAddDialog = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigPicker(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = selected,
                onValueChange = {},
                readOnly = true,
                label = { Text("Config") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                COMMON_UCI_CONFIGS.forEach { name ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(name); expanded = false })
                }
            }
        }
    }
}

@Composable
private fun SectionRow(section: UciSection, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), onClick = onEdit) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(if (section.isAnonymous) section.type else section.id, style = MaterialTheme.typography.titleSmall)
                    AssistChip(onClick = {}, label = { Text(section.type) }, modifier = Modifier.padding(start = 8.dp))
                }
                Text(
                    "${section.options.size} option${if (section.options.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete section") }
        }
    }
}

@Composable
private fun EditSectionDialog(section: UciSection, onDismiss: () -> Unit, onSave: (Map<String, String>) -> Unit) {
    val fields = remember(section.id) {
        section.options.entries.map { (key, value) -> key to mutableStateOf(value) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(section.id) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(end = 8.dp))
                    Text("Editing UCI values directly — no validation.", style = MaterialTheme.typography.bodySmall)
                }
                fields.forEach { (key, state) ->
                    var value by state
                    OutlinedTextField(value, { value = it }, label = { Text(key) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(fields.associate { (key, state) -> key to state.value }) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AddSectionDialog(onDismiss: () -> Unit, onAdd: (type: String) -> Unit) {
    var type by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Section") },
        text = {
            OutlinedTextField(type, { type = it }, label = { Text("Section type (e.g. rule, interface)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(enabled = type.isNotBlank(), onClick = { onAdd(type) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
