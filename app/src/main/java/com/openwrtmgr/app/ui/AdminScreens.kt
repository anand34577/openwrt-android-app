package com.openwrtmgr.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openwrtmgr.app.data.LogLine
import com.openwrtmgr.app.data.Pkg
import com.openwrtmgr.app.data.Proc
import com.openwrtmgr.app.data.Router
import com.openwrtmgr.app.data.Service
import com.openwrtmgr.app.data.arr
import com.openwrtmgr.app.data.bool
import com.openwrtmgr.app.data.obj
import com.openwrtmgr.app.data.str
import com.openwrtmgr.app.data.strList
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ======================================================================== Settings

private data class Settings(
    val sysSection: String, val hostname: String, val zonename: String, val timezone: String,
    val ntpSection: String?, val ntpEnabled: Boolean, val ntpServer: Boolean, val ntpServers: List<String>,
    val routerTime: Long?, val crontab: String, val zones: Map<String, String>,
)

private val REBOOT_LINE = Regex("^(\\d{1,2}) (\\d{1,2}) \\* \\* ([*0-6]) (/sbin/)?reboot\\s*$", RegexOption.MULTILINE)

private suspend fun Router.loadSettings(): Settings {
    val sys = uci("system")
    val (sid, s) = sys.entries.first { it.value.obj()[".type"].str() == "system" }.let { it.key to it.value.obj() }
    val ntp = sys.entries.firstOrNull { it.value.obj()[".type"].str() == "timeserver" }
    val zones = ubus.callOrNull("luci", "getTimezones")?.mapValues { it.value.obj()["tzstring"].str().orEmpty() }.orEmpty()
    return Settings(
        sid, s["hostname"].str().orEmpty(), s["zonename"].str() ?: "UTC", s["timezone"].str() ?: "UTC0",
        ntp?.key, ntp?.value?.obj()?.get("enabled").bool() ?: true, ntp?.value?.obj()?.get("enable_server").bool() ?: false,
        ntp?.value?.obj()?.get("server").strList().orEmpty(), routerTime(), crontab(), zones,
    )
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val data = rememberData("settings") { loadSettings() }
    val r = LocalRouter.current
    val ui = LocalUi.current
    var sheet by remember { mutableStateOf<String?>(null) }

    DetailScreen("General settings", data, onBack) { s ->
        item {
            Panel(title = "Identity", onClick = { sheet = "host" }) { KV("Hostname", s.hostname) }
        }
        item {
            Panel(title = "Time") {
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                KV("Router clock (UTC)", s.routerTime?.let { fmt.apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(Date(it * 1000)) } ?: "—")
                val drift = s.routerTime?.let { it - System.currentTimeMillis() / 1000 }
                if (drift != null) KV("Drift vs phone", "${drift}s", valueColor = if (kotlin.math.abs(drift) > 30) Ops.warn else Ops.ok)
                KV("Time zone", s.zonename, onClick = { sheet = "tz" })
                KV("NTP sync", if (s.ntpEnabled) s.ntpServers.firstOrNull() ?: "on" else "off", onClick = { sheet = "ntp" })
                Spacer(Modifier.height(8.dp))
                GhostButton("Sync clock from phone", Icons.Rounded.Sync) {
                    ui.run("Setting clock…", "Router clock set", data::refresh) { r.setRouterTime(System.currentTimeMillis() / 1000) }
                }
            }
        }
        item {
            val m = REBOOT_LINE.find(s.crontab)
            Panel(title = "Scheduled reboot", onClick = { sheet = "cron" }) {
                KV("Schedule", m?.let { "${if (it.groupValues[3] == "*") "Daily" else dayName(it.groupValues[3])} at %02d:%02d".format(it.groupValues[2].toInt(), it.groupValues[1].toInt()) } ?: "Off")
            }
        }
        item {
            Panel(title = "Security", onClick = { sheet = "pw" }) { KV("${r.profile.username} password", "Change…", mono = false, valueColor = Ops.accent) }
        }
    }

    val s = (data.state.value as? Load.Ok)?.data ?: return
    when (sheet) {
        "host" -> {
            var host by remember { mutableStateOf(s.hostname) }
            EditSheet("Hostname", { sheet = null }, saveEnabled = host.matches(Regex("^[A-Za-z0-9][A-Za-z0-9-]{0,62}$")), onSave = {
                ui.run("Saving…", "Hostname changed", data::refresh) { r.saveUci("system", s.sysSection, "system", mapOf("hostname" to host)); sheet = null }
            }) { Field(host, { host = it }, "Hostname", mono = true, supporting = "Letters, digits and dashes") }
        }
        "tz" -> {
            var q by remember { mutableStateOf("") }
            EditSheet("Time zone", { sheet = null }, onSave = null) {
                Field(q, { q = it }, "Search", placeholder = "Asia/Kolkata")
                s.zones.keys.filter { q.isBlank() || it.contains(q, true) }.take(40).forEach { z ->
                    ListRow(z, s.zones[z], onClick = {
                        ui.run("Saving…", "Time zone set to $z", data::refresh) { r.saveUci("system", s.sysSection, "system", mapOf("zonename" to z, "timezone" to s.zones[z])); sheet = null }
                    }) { if (z == s.zonename) Tag("current", Ops.accent) }
                }
            }
        }
        "ntp" -> {
            var on by remember { mutableStateOf(s.ntpEnabled) }
            var server by remember { mutableStateOf(s.ntpServer) }
            var list by remember { mutableStateOf(s.ntpServers.joinToString(" ")) }
            EditSheet("NTP", { sheet = null }, onSave = {
                ui.run("Saving…", "NTP settings saved", data::refresh) {
                    val v = mapOf("enabled" to if (on) "1" else "0", "enable_server" to if (server) "1" else "0", "server" to list.split(Regex("[\\s,]+")).filter { it.isNotBlank() })
                    if (s.ntpSection != null) r.saveUci("system", s.ntpSection, "timeserver", v) else { r.uciAdd("system", "timeserver", v, name = "ntp"); r.apply() }
                    sheet = null
                }
            }) {
                SwitchRow("Sync time from the internet", on) { on = it }
                SwitchRow("Serve time to LAN (NTP server)", server) { server = it }
                Field(list, { list = it }, "Servers", mono = true, singleLine = false, placeholder = "0.openwrt.pool.ntp.org")
            }
        }
        "cron" -> {
            val m = REBOOT_LINE.find(s.crontab)
            var on by remember { mutableStateOf(m != null) }
            var hour by remember { mutableStateOf(m?.groupValues?.get(2) ?: "4") }
            var min by remember { mutableStateOf(m?.groupValues?.get(1) ?: "0") }
            var day by remember { mutableStateOf(m?.groupValues?.get(3) ?: "*") }
            val ok = hour.toIntOrNull() in 0..23 && min.toIntOrNull() in 0..59
            EditSheet("Scheduled reboot", { sheet = null }, saveEnabled = ok || !on, onSave = {
                ui.run("Saving…", if (on) "Reboot scheduled" else "Scheduled reboot removed", data::refresh) {
                    val others = s.crontab.lines().filterNot { REBOOT_LINE.matches(it) }.filter { it.isNotBlank() }
                    val line = if (on) listOf("${min.toInt()} ${hour.toInt()} * * $day reboot") else emptyList()
                    r.setCrontab((others + line).joinToString("\n", postfix = "\n"))
                    sheet = null
                }
            }) {
                SwitchRow("Reboot automatically", on) { on = it }
                if (on) {
                    Caps("Day"); ChipPicker(listOf("*" to "Every day") + (0..6).map { "$it" to dayName("$it") }, { it == day }, { day = it })
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Field(hour, { hour = it.filter(Char::isDigit).take(2) }, "Hour (0–23)", Modifier.weight(1f), mono = true, keyboard = KeyboardType.Number)
                        Field(min, { min = it.filter(Char::isDigit).take(2) }, "Minute", Modifier.weight(1f), mono = true, keyboard = KeyboardType.Number)
                    }
                    Text("Uses the router's time zone (${s.zonename}).", color = Ops.muted, fontSize = 12.sp)
                }
            }
        }
        "pw" -> {
            var p1 by remember { mutableStateOf("") }
            var p2 by remember { mutableStateOf("") }
            EditSheet("Change password", { sheet = null }, saveLabel = "Change password", saveEnabled = p1.length >= 6 && p1 == p2, onSave = {
                ui.run("Changing password…", "Password changed. The app now uses the new one.") {
                    r.setPassword(p1)
                    sheet = null
                }
            }) {
                Field(p1, { p1 = it }, "New password", password = true, supporting = "Also the SSH and LuCI password for ${r.profile.username}")
                Field(p2, { p2 = it }, "Repeat", password = true, isError = p2.isNotEmpty() && p1 != p2)
            }
        }
    }
}

private fun dayName(d: String) = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").getOrElse(d.toIntOrNull() ?: -1) { "Every day" }

// ======================================================================== Services

@Composable
fun ServicesScreen(onBack: () -> Unit) {
    val data = rememberData("services") { services() }
    val r = LocalRouter.current
    val ui = LocalUi.current
    var q by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf<Service?>(null) }
    DetailScreen("Services", data, onBack, subtitle = "/etc/init.d") { list ->
        item { Field(q, { q = it }, "Filter") }
        items(list.filter { q.isBlank() || it.name.contains(q.trim(), true) }, key = { it.name }) { s ->
            Panel(onClick = { menu = s }, padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(if (s.running) Ops.ok else Ops.faint)
                    Spacer(Modifier.width(4.dp))
                    MonoText(s.name, Modifier.weight(1f), size = 14, weight = FontWeight.Medium)
                    if (s.running) Tag("running", Ops.ok)
                    Spacer(Modifier.width(6.dp))
                    Tag(if (s.enabled) "autostart" else "disabled", if (s.enabled) Ops.accent else Ops.faint)
                }
            }
        }
    }
    menu?.let { s ->
        EditSheet(s.name, { menu = null }, onSave = null) {
            KV("State", if (s.running) "running" else "not running (or no daemon)")
            KV("Start at boot", if (s.enabled) "yes (priority ${s.startPriority ?: "?"})" else "no")
            listOf("restart" to "Restart", "start" to "Start", "stop" to "Stop", "reload" to "Reload", if (s.enabled) "disable" to "Disable autostart" else "enable" to "Enable autostart").forEach { (a, label) ->
                ListRow(label, onClick = {
                    ui.run("$label ${s.name}…", "${s.name}: $a done", data::refresh) { r.service(s.name, a); menu = null }
                })
            }
            if (s.name in listOf("network", "firewall", "dnsmasq", "uhttpd", "rpcd", "dropbear")) Text("Careful: ${s.name} is needed to manage the router.", color = Ops.warn, fontSize = 12.sp)
        }
    }
}

// ======================================================================== Packages

private data class PkgState(val installed: List<Pkg>, val available: List<Pkg>?)

@Composable
fun PackagesScreen(onBack: () -> Unit) {
    val data = rememberData("packages") { PkgState(installedPackages(), null) }
    val r = LocalRouter.current
    val ui = LocalUi.current
    var tab by remember { mutableStateOf("installed") }
    var q by remember { mutableStateOf("") }
    var available by remember { mutableStateOf<List<Pkg>?>(null) }
    var output by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Pkg?>(null) }

    DetailScreen("Packages", data, onBack, actions = {
        IconButton(onClick = { ui.run("Updating package lists…", "Package lists updated") { output = r.packageAction("update"); available = null } }) {
            Icon(Icons.Rounded.Refresh, "Update lists", tint = Ops.muted)
        }
    }) { st ->
        val installedNames = st.installed.associateBy { it.name }
        item {
            Segmented(listOf("installed" to "Installed (${st.installed.size})", "available" to "Available", "updates" to "Updates"), tab, { t ->
                tab = t
                if (t != "installed" && available == null) ui.run("Loading package index…") { available = r.availablePackages() }
            })
            Spacer(Modifier.height(8.dp))
            Field(q, { q = it }, "Search packages")
        }
        val list = when (tab) {
            "installed" -> st.installed
            "available" -> available.orEmpty()
            else -> available.orEmpty().filter { a -> installedNames[a.name]?.let { it.version != a.version } == true }
        }.filter { q.length < 2 || it.name.contains(q.trim(), true) || it.description.contains(q.trim(), true) }
        if (tab != "installed" && available == null) item { EmptyNote("Loading index… if this stays empty, tap ↻ to update lists first.") }
        if (tab == "available" && q.length < 2) item { EmptyNote("Type at least 2 characters to search ${available?.size ?: 0} packages.") }
        else items(list.take(200), key = { tab + it.name }) { p ->
            Panel(onClick = { selected = p }, padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        MonoText(p.name, size = 14, weight = FontWeight.Medium)
                        if (p.description.isNotBlank()) Text(p.description, color = Ops.muted, fontSize = 12.sp, maxLines = 1)
                    }
                    MonoText(if (tab == "updates") "${installedNames[p.name]?.version} → ${p.version}" else p.version, color = Ops.faint, size = 11)
                    if (tab == "available" && p.name in installedNames) { Spacer(Modifier.width(6.dp)); Tag("installed", Ops.ok) }
                }
            }
        }
    }
    selected?.let { p ->
        val isInstalled = (data.state.value as? Load.Ok)?.data?.installed?.any { it.name == p.name } == true
        EditSheet(p.name, { selected = null }, onSave = null) {
            Text(p.description.ifBlank { "No description." }, color = Ops.muted, fontSize = 13.sp)
            KV("Version", p.version)
            if (p.size > 0) KV("Size", bytes(p.size))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isInstalled || tab == "updates") GhostButton(if (isInstalled) "Upgrade" else "Install", Icons.Rounded.Download) {
                    ui.run("Installing ${p.name}…", "${p.name} installed", data::refresh) { output = r.packageAction("install", p.name); selected = null }
                }
                if (isInstalled) GhostButton("Remove", color = Ops.bad) {
                    ui.run("Removing ${p.name}…", "${p.name} removed", data::refresh) { output = r.packageAction("remove", p.name); selected = null }
                }
            }
        }
    }
    output?.takeIf { it.isNotBlank() }?.let { text ->
        EditSheet("Package manager output", { output = null }, onSave = null) {
            Row(Modifier.horizontalScroll(rememberScrollState())) { MonoText(text, color = Ops.muted, size = 11, maxLines = 400) }
        }
    }
}

// ======================================================================== Logs

@Composable
fun LogsScreen(onBack: () -> Unit) {
    var kernel by remember { mutableStateOf(false) }
    val data = rememberData("logs:$kernel") { if (kernel) dmesg().lines().takeLast(400).reversed().map { LogLine(0, 6, "kernel", it) } else log(400).reversed() }
    var q by remember { mutableStateOf("") }
    var errorsOnly by remember { mutableStateOf(false) }
    var follow by remember { mutableStateOf(false) }
    LaunchedEffect(follow, data) { while (follow) { delay(3000); data.refresh() } }
    val fmt = remember { SimpleDateFormat("MMM d HH:mm:ss", Locale.US) }
    DetailScreen("Logs", data, onBack, actions = {
        IconButton(onClick = { follow = !follow }) { Icon(Icons.Rounded.Sync, "Follow", tint = if (follow) Ops.accent else Ops.muted) }
        ShareButton(if (kernel) "Kernel log" else "System log") {
            (data.state.value as? Load.Ok)?.data.orEmpty().reversed().joinToString("\n") { l ->
                (if (l.time > 0) fmt.format(Date(l.time)) + " " else "") + "${l.source}: ${l.message}"
            }
        }
    }) { lines ->
        item {
            Segmented(listOf(false to "System", true to "Kernel"), kernel, { kernel = it })
            Spacer(Modifier.height(8.dp))
            Field(q, { q = it }, "Filter")
            SwitchRow("Warnings & errors only", errorsOnly) { errorsOnly = it }
        }
        val shown = lines.filter { (!errorsOnly || it.priority <= 4) && (q.isBlank() || it.message.contains(q, true) || it.source.contains(q, true)) }
        items(shown.take(400)) { l ->
            val c = when { l.priority <= 3 -> Ops.bad; l.priority == 4 -> Ops.warn; else -> Ops.muted }
            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Row {
                    if (l.time > 0) MonoText(fmt.format(Date(l.time)), color = Ops.faint, size = 10)
                    Spacer(Modifier.width(6.dp))
                    MonoText(l.source, color = c, size = 10)
                }
                MonoText(l.message, color = if (l.priority <= 4) c else Ops.text, size = 12, maxLines = 6)
            }
        }
    }
}

// ======================================================================== Processes

@Composable
fun ProcessesScreen(onBack: () -> Unit) {
    val data = rememberData("procs") { processes() }
    val r = LocalRouter.current
    val ui = LocalUi.current
    var kill by remember { mutableStateOf<Proc?>(null) }
    LaunchedEffect(data) { while (true) { delay(5000); data.refresh() } }
    DetailScreen("Processes", data, onBack, subtitle = "sorted by CPU · refreshes every 5s") { list ->
        items(list, key = { it.pid }) { p ->
            Panel(onClick = { kill = p }, padding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MonoText("${p.pid}", Modifier.width(52.dp), color = Ops.faint, size = 11)
                    MonoText(p.command, Modifier.weight(1f), size = 12)
                    MonoText(String.format(Locale.US, "%4.1f%%", p.cpu), color = if (p.cpu > 20) Ops.warn else Ops.accent, size = 12)
                    Spacer(Modifier.width(8.dp))
                    MonoText(bytes(p.vsz * 1024), color = Ops.muted, size = 11)
                }
            }
        }
    }
    kill?.let { p ->
        Confirm("Stop process ${p.pid}?", "${p.command}\n\nSends SIGTERM. procd restarts managed services automatically.", "Stop", onDismiss = { kill = null }) {
            ui.run("Stopping ${p.pid}…", "Signal sent", data::refresh) { r.kill(p.pid) }
        }
    }
}
