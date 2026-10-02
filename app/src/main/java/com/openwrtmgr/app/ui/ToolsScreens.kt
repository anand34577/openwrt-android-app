package com.openwrtmgr.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openwrtmgr.app.data.Router
import com.openwrtmgr.app.data.RouterException
import com.openwrtmgr.app.data.UciSection
import com.openwrtmgr.app.data.parseUciConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ======================================================================== Backup / restore / firmware

@Composable
fun MaintenanceScreen(onBack: () -> Unit) {
    val data = rememberData("maint") { board() }
    val r = LocalRouter.current
    val ui = LocalUi.current
    val context = LocalContext.current
    var restoreList by remember { mutableStateOf<String?>(null) }
    var fw by remember { mutableStateOf<Router.FirmwareCheck?>(null) }
    var keep by remember { mutableStateOf(true) }
    var force by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }

    suspend fun readUri(uri: android.net.Uri): ByteArray = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw RouterException("Couldn't read that file")
    }

    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) { uri ->
        if (uri != null) ui.run("Creating backup…", "Backup saved") {
            val bytes = r.backup()
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
        }
    }
    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) ui.run("Uploading backup…") { restoreList = r.uploadBackup(readUri(uri)) }
    }
    val pickFirmware = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) ui.run("Uploading & verifying firmware…") {
            val bytes = readUri(uri)
            if (bytes.size < 1_000_000) throw RouterException("That file is too small to be a firmware image.")
            fw = r.uploadFirmware(bytes).also { force = false; keep = it.allowBackup }
        }
    }

    DetailScreen("Backup & firmware", data, onBack) { b ->
        item {
            Panel(title = "Configuration backup") {
                Text("A .tar.gz of /etc/config and everything sysupgrade keeps. Same file LuCI makes.", color = Ops.muted, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("Download", Icons.Rounded.Download) {
                        saveBackup.launch("backup-${b.hostname}-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.tar.gz")
                    }
                    GhostButton("Restore…", Icons.Rounded.Upload, Ops.warn) { pickBackup.launch(arrayOf("*/*")) }
                }
            }
        }
        item {
            Panel(title = "Firmware upgrade") {
                KV("Running", b.release)
                KV("Target", b.target)
                Spacer(Modifier.height(8.dp))
                Text("Pick a sysupgrade image for ${b.model}. It's checked on the router before anything is flashed.", color = Ops.muted, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                GhostButton("Choose image…", Icons.Rounded.Memory) { pickFirmware.launch(arrayOf("*/*")) }
            }
        }
        item {
            Panel(title = "Danger zone") {
                ListRow("Factory reset", "Erase all settings and reboot to defaults (squashfs images only)", Icons.Rounded.RestartAlt, Ops.bad, onClick = { reset = true })
            }
        }
    }

    restoreList?.let { files ->
        EditSheet("Restore this backup?", { ui.run("Discarding…") { r.ubus.callOrNull("file", "remove", com.openwrtmgr.app.data.args("path" to "/tmp/backup.tar.gz")) }; restoreList = null },
            saveLabel = "Restore & reboot", onSave = {
                ui.run("Restoring…", "Restored. The router is rebooting; if its LAN IP changed, reconnect to it.") { r.restoreBackup(); restoreList = null }
            }) {
            Text("The router reboots after restoring. These files will be replaced:", color = Ops.muted, fontSize = 13.sp)
            Row(Modifier.horizontalScroll(rememberScrollState())) { MonoText(files.trim(), color = Ops.text, size = 11, maxLines = 300) }
        }
    }
    fw?.let { c ->
        EditSheet("Flash firmware?", { ui.run("Discarding image…") { r.discardFirmware() }; fw = null },
            saveLabel = "Flash now", saveEnabled = c.valid || force, onSave = {
                ui.run("Starting flash…", "Flashing. Don't power off. The router comes back in a few minutes.") { r.flashFirmware(keep, force && !c.valid); fw = null }
            }) {
            KV("Image check", if (c.valid) "valid for this device" else "FAILED", valueColor = if (c.valid) Ops.ok else Ops.bad)
            if (c.message.isNotBlank()) MonoText(c.message, color = Ops.warn, size = 11, maxLines = 12)
            SwitchRow("Keep settings", keep && c.allowBackup, if (c.allowBackup) "Recommended" else "This image can't keep settings") { if (c.allowBackup) keep = it }
            if (!c.valid && c.forceable) SwitchRow("Force flash anyway", force, "Only if you're sure the image is right for this device") { force = it }
            Text("Do not power off the router while flashing.", color = Ops.warn, fontSize = 13.sp)
        }
    }
    if (reset) Confirm("Erase everything?", "All settings, passwords and installed packages are removed and the router reboots with defaults (usually 192.168.1.1, no password).", "Factory reset",
        onDismiss = { reset = false }) { ui.run("Resetting…", "Reset started. The router reboots with default settings.") { r.factoryReset() } }
}

// ======================================================================== Diagnostics

@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val data = rememberData("diag") { "" }
    val r = LocalRouter.current
    var host by remember { mutableStateOf("openwrt.org") }
    var output by remember { mutableStateOf("") }
    var running by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun run(tool: String, block: suspend () -> String) {
        if (running != null) return
        running = tool; output = ""
        scope.launch {
            output = try { block().ifBlank { "(no output)" } } catch (e: Throwable) { "Error: ${e.friendly()}" }
            running = null
        }
    }

    DetailScreen("Diagnostics", data, onBack, subtitle = "runs on the router", actions = { ShareButton("Diagnostics: $host") { output } }) { _ ->
        item {
            Panel {
                Field(host, { host = it }, "Host or IP", mono = true)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("Ping", enabled = running == null) { run("ping") { r.ping(host) } }
                    GhostButton("Traceroute", enabled = running == null) { run("traceroute") { r.traceroute(host) } }
                    GhostButton("Lookup", enabled = running == null) { run("nslookup") { r.nslookup(host) } }
                }
            }
        }
        item {
            Panel(title = running?.let { "$it running…" } ?: "Output") {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    MonoText(output.ifBlank { if (running != null) "…" else "Results appear here." }, color = if (output.startsWith("Error")) Ops.bad else Ops.text, size = 12, maxLines = 200)
                }
            }
        }
    }
}

// ======================================================================== UCI editor

@Composable
fun UciScreen(config: String, onBack: () -> Unit) {
    val nav = LocalNav()
    if (config.isBlank()) {
        val data = rememberData("uci-configs") { uciConfigsOrProbe() }
        DetailScreen("UCI editor", data, onBack, subtitle = "/etc/config") { list ->
            item {
                Panel(padding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    list.forEach { c -> ListRow(c, icon = Icons.Rounded.Description, onClick = { nav("uci", c) }) }
                }
            }
        }
        return
    }
    val data = rememberData("uci:$config") { parseUciConfig(uci(config)) }
    val r = LocalRouter.current
    val ui = LocalUi.current
    var edit by remember { mutableStateOf<UciSection?>(null) }
    var add by remember { mutableStateOf(false) }
    DetailScreen(config, data, onBack, subtitle = "/etc/config/$config") { sections ->
        item { GhostButton("Add section", Icons.Rounded.Add) { add = true } }
        items(sections, key = { it.name }) { s ->
            Panel(onClick = { edit = s }, title = "${s.type} ${if (s.anonymous) "" else s.name}".trim()) {
                s.options.entries.take(8).forEach { (k, v) -> KV(k, v) }
                if (s.options.size > 8) Text("+${s.options.size - 8} more", color = Ops.faint, fontSize = 12.sp)
            }
        }
    }
    edit?.let { s ->
        // Edit as "key=value" lines; lists are space-separated (same as `uci show` style).
        var text by remember(s) { mutableStateOf(s.options.entries.joinToString("\n") { "${it.key}=${it.value}" }) }
        var del by remember { mutableStateOf(false) }
        EditSheet("${s.type} · ${s.name}", { edit = null }, onDelete = { del = true }, onSave = {
            val parsed = text.lines().filter { '=' in it }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
            val removed = s.options.keys - parsed.keys
            ui.run("Applying $config…", "Saved", data::refresh) {
                r.saveUci(config, s.name, s.type, parsed.mapValues { (k, v) -> if (s.options[k]?.contains(' ') == true && ' ' in v) v.split(' ') else v } + removed.associateWith { null })
                edit = null
            }
        }) {
            Field(text, { text = it }, "options (key=value per line)", mono = true, singleLine = false)
            Text("Changes are applied with rollback protection.", color = Ops.muted, fontSize = 12.sp)
        }
        if (del) Confirm("Delete section?", "${s.type} ${s.name} is removed from $config.", "Delete", onDismiss = { del = false }) {
            ui.run("Deleting…", "Section deleted", data::refresh) { r.deleteUci(config, s.name); edit = null }
        }
    }
    if (add) {
        var type by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        EditSheet("New section in $config", { add = false }, saveEnabled = type.matches(Regex("^[a-z_][a-z0-9_-]*$")), onSave = {
            ui.run("Adding…", "Section added", data::refresh) { r.uciAdd(config, type, emptyMap(), name.ifBlank { null }); r.apply(); add = false }
        }) {
            Field(type, { type = it.trim() }, "Section type", mono = true, placeholder = "rule")
            Field(name, { name = it.trim() }, "Name (optional)", mono = true, supporting = "Empty = anonymous section")
        }
    }
}
