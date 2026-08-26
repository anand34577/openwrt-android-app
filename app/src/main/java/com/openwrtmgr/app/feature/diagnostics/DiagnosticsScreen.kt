package com.openwrtmgr.app.feature.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.openwrtmgr.app.domain.model.DiagnosticResult
import kotlinx.coroutines.launch

private val TABS = listOf("Ping", "DNS Lookup", "Traceroute")

/** Diagnostics run from the phone itself (see [NetworkDiagnostics]) — useful for "can I reach X" troubleshooting. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var host by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<DiagnosticResult?>(null) }
    val scope = rememberCoroutineScope()

    fun run() {
        if (host.isBlank() || running) return
        running = true
        result = null
        scope.launch {
            result = when (tab) {
                0 -> NetworkDiagnostics.ping(host)
                1 -> NetworkDiagnostics.dnsLookup(host)
                else -> NetworkDiagnostics.traceroute(host)
            }
            running = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network Diagnostics") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                TABS.forEachIndexed { index, label ->
                    Tab(selected = tab == index, onClick = { tab = index; result = null }, text = { Text(label) })
                }
            }
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("Hostname or IP") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = ::run, enabled = host.isNotBlank() && !running) {
                        if (running) CircularProgressIndicator(modifier = Modifier.padding(2.dp)) else Text("Run")
                    }
                }
                result?.let { r ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                if (r.success) "Success" else "Failed",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (r.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                            Text(
                                r.output,
                                modifier = Modifier.padding(top = 8.dp),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                    }
                }
            }
        }
    }
}
