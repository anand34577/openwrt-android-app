package com.openwrtmgr.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openwrtmgr.app.data.UciSection
import com.openwrtmgr.app.data.parseUciConfig

// ======================================================================== Scheduled tasks (cron)

private val CRON_PRESETS = listOf(
    "Reboot weekly" to "0 4 * * 1 /sbin/reboot",
    "Reload Wi-Fi nightly" to "0 3 * * * /sbin/wifi reload",
    "Restart network daily" to "30 3 * * * /etc/init.d/network restart",
)

/** A cron line is blank, a comment, an `@reboot`-style shortcut, or 5 time fields plus a command. */
private fun badCronLine(line: String): Boolean {
    val l = line.trim()
    if (l.isEmpty() || l.startsWith("#")) return false
    if (Regex("^[A-Za-z_]+=").containsMatchIn(l)) return false
    return l.split(Regex("\\s+")).size < if (l.startsWith("@")) 2 else 6
}

@Composable
fun CronScreen(onBack: () -> Unit) {
    val data = rememberData("cron") { crontab() }
    val r = LocalRouter.current
    val ui = LocalUi.current
    DetailScreen("Scheduled tasks", data, onBack, subtitle = "/etc/crontabs/root") { text ->
        item {
            var t by remember(text) { mutableStateOf(text) }
            val bad = t.lines().indexOfFirst(::badCronLine)
            Panel {
                Text("Run commands on a schedule: minute · hour · day · month · weekday, then the command.", color = Ops.muted, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Caps("Quick add")
                Spacer(Modifier.height(6.dp))
                ChipPicker(CRON_PRESETS.map { it.second to it.first }, { false }, { line -> t = t.trimEnd() + (if (t.isBlank()) "" else "\n") + line + "\n" })
                Spacer(Modifier.height(12.dp))
                Field(t, { t = it }, "crontab", mono = true, singleLine = false, modifier = Modifier.heightIn(min = 200.dp),
                    isError = bad >= 0, supporting = if (bad >= 0) "Line ${bad + 1} needs 5 time fields and a command" else "Uses the router's clock and time zone")
                Spacer(Modifier.height(12.dp))
                GhostButton("Save", Icons.Rounded.Save, enabled = bad < 0) { ui.run("Saving…", "Schedule saved", data::refresh) { r.setCrontab(t.trimEnd() + "\n") } }
            }
        }
    }
}

// ======================================================================== LED configuration

private val LED_TRIGGERS = listOf("none", "default-on", "netdev", "timer", "heartbeat", "usbport", "phy0tpt", "phy1tpt")

@Composable
fun LedsScreen(onBack: () -> Unit) {
    val data = rememberData("leds") { parseUciConfig(uci("system")).filter { it.type == "led" } }
    var edit by remember { mutableStateOf<UciSection?>(null) }
    DetailScreen("LEDs", data, onBack, subtitle = "What each light on the router shows") { leds ->
        if (leds.isEmpty()) item { EmptyNote("This router has no configurable LEDs.") }
        items(leds, key = { it.name }) { s ->
            Panel(onClick = { edit = s }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Rounded.Lightbulb, Tints.amber)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.options["name"] ?: s.name, color = Ops.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(s.options["sysfs"].orEmpty(), color = Ops.muted, fontSize = 12.5.sp)
                    }
                    Tag(s.options["trigger"] ?: "none", Tints.amber)
                }
            }
        }
    }
    edit?.let { s -> LedSheet(s, onDismiss = { edit = null }) { data.refresh() } }
}

@Composable
private fun LedSheet(s: UciSection, onDismiss: () -> Unit, onDone: () -> Unit) {
    val r = LocalRouter.current
    val ui = LocalUi.current
    var trigger by remember { mutableStateOf(s.options["trigger"] ?: "none") }
    var dev by remember { mutableStateOf(s.options["dev"].orEmpty()) }
    var modes by remember { mutableStateOf(s.options["mode"].orEmpty().split(' ').filter { it.isNotBlank() }.toSet()) }
    var on by remember { mutableStateOf(s.options["delayon"] ?: "500") }
    var off by remember { mutableStateOf(s.options["delayoff"] ?: "500") }
    // The kernel lists the triggers this LED really supports; fall back to the common ones.
    val triggers by produceState(LED_TRIGGERS) {
        val sysfs = s.options["sysfs"]
        if (!sysfs.isNullOrBlank() && '/' !in sysfs) runCatching { r.readFile("/sys/class/leds/$sysfs/trigger") }.getOrNull()
            ?.split(Regex("\\s+"))?.map { it.trim('[', ']') }?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let { value = it }
    }
    EditSheet("${s.options["name"] ?: s.name}", onDismiss, onSave = {
        val values = mapOf(
            "trigger" to trigger,
            "dev" to if (trigger == "netdev") dev.ifBlank { null } else null,
            "mode" to if (trigger == "netdev") modes.joinToString(" ").ifBlank { null } else null,
            "delayon" to if (trigger == "timer") on else null,
            "delayoff" to if (trigger == "timer") off else null,
        )
        ui.run("Applying…", "LED updated", onDone) { r.saveUci("system", s.name, "led", values); onDismiss() }
    }) {
        Caps("Show")
        ChipPicker((triggers + trigger).distinct().map { it to it }, { it == trigger }, { trigger = it })
        if (trigger == "netdev") {
            Field(dev, { dev = it.trim() }, "Network device", mono = true, placeholder = "eth0")
            Caps("Blink on")
            ChipPicker(listOf("link" to "Link", "tx" to "Transmit", "rx" to "Receive"), { it in modes }, { m -> modes = if (m in modes) modes - m else modes + m })
        }
        if (trigger == "timer") Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(on, { on = it.filter(Char::isDigit) }, "On (ms)", Modifier.weight(1f), mono = true)
            Field(off, { off = it.filter(Char::isDigit) }, "Off (ms)", Modifier.weight(1f), mono = true)
        }
    }
}

// ======================================================================== Add-ons hub

private data class Addon(val config: String, val title: String, val desc: String, val icon: ImageVector, val tint: Color)

private val ADDONS = listOf(
    Addon("ddns", "Dynamic DNS", "Keep a hostname pointed at your changing IP", Icons.Rounded.Dns, Tints.cyan),
    Addon("sqm", "Smart Queue Management", "Fight bufferbloat with cake / fq_codel", Icons.Rounded.Speed, Tints.green),
    Addon("upnpd", "UPnP & NAT-PMP", "Let apps open ports automatically", Icons.Rounded.Bolt, Tints.amber),
    Addon("mwan3", "Multi-WAN", "Load-balance or fail over between uplinks", Icons.Rounded.AltRoute, Tints.violet),
    Addon("adblock", "Ad blocking", "DNS-level blocklists", Icons.Rounded.Block, Tints.red),
    Addon("simple-adblock", "Simple ad blocking", "DNS-level blocklists", Icons.Rounded.Block, Tints.red),
    Addon("banip", "IP banning", "Block abusive addresses and countries", Icons.Rounded.Security, Tints.red),
    Addon("https-dns-proxy", "DNS over HTTPS", "Encrypt your DNS lookups", Icons.Rounded.Language, Tints.blue),
    Addon("openvpn", "OpenVPN", "Tunnels and servers", Icons.Rounded.VpnKey, Tints.orange),
    Addon("pbr", "Policy-based routing", "Send chosen devices or sites through a VPN", Icons.Rounded.AltRoute, Tints.violet),
    Addon("vpn-policy-routing", "VPN policy routing", "Send chosen devices or sites through a VPN", Icons.Rounded.AltRoute, Tints.violet),
    Addon("nlbwmon", "Bandwidth monitor", "Per-device traffic history", Icons.Rounded.QueryStats, Tints.teal),
    Addon("statistics", "Statistics", "collectd graphs for CPU, traffic and more", Icons.Rounded.MonitorHeart, Tints.pink),
    Addon("watchcat", "Watchdog", "Reboot or restart when the connection drops", Icons.Rounded.Timer, Tints.orange),
    Addon("samba4", "Network shares", "Samba file sharing", Icons.Rounded.FolderShared, Tints.blue),
    Addon("minidlna", "Media server", "DLNA streaming to TVs", Icons.Rounded.Videocam, Tints.pink),
    Addon("transmission", "BitTorrent", "Transmission daemon", Icons.Rounded.Share, Tints.green),
    Addon("uhttpd", "Web server", "LuCI's web server and certificates", Icons.Rounded.Language, Tints.slate),
    Addon("acme", "HTTPS certificates", "Let's Encrypt via acme.sh", Icons.Rounded.Security, Tints.green),
    Addon("tailscale", "Tailscale", "Mesh VPN", Icons.Rounded.Hub, Tints.teal),
    Addon("zerotier", "ZeroTier", "Virtual network", Icons.Rounded.Hub, Tints.teal),
    Addon("unbound", "Unbound DNS", "Recursive DNS resolver", Icons.Rounded.Dns, Tints.blue),
    Addon("wol", "Wake on LAN", "Saved machines to wake", Icons.Rounded.Bolt, Tints.green),
    Addon("travelmate", "Travelmate", "Auto-join uplink Wi-Fi", Icons.Rounded.Sync, Tints.cyan),
    Addon("mosquitto", "MQTT broker", "Mosquitto", Icons.Rounded.CloudSync, Tints.violet),
    Addon("snmpd", "SNMP", "Monitoring agent", Icons.Rounded.MonitorHeart, Tints.slate),
    Addon("lldpd", "LLDP", "Neighbour discovery", Icons.Rounded.Hub, Tints.slate),
    Addon("ser2net", "Serial to network", "Expose serial ports over TCP", Icons.Rounded.Storage, Tints.slate),
)

@Composable
fun AddonsScreen(onBack: () -> Unit) {
    val data = rememberData("addons") { uciConfigsOrProbe(ADDONS.map { it.config }) }
    val nav = LocalNav()
    DetailScreen("Add-ons", data, onBack, subtitle = "Apps installed on this router") { configs ->
        val found = ADDONS.filter { it.config in configs }
        if (found.isEmpty()) item { EmptyNote("No extra apps detected (DDNS, SQM, UPnP, VPN, ad blocking…).") }
        else item {
            GroupCard(found.map { a -> { ListRow(a.title, a.desc, a.icon, a.tint, onClick = { nav("uci", a.config) }) } })
        }
        item {
            GroupCard(listOf { ListRow("Get more add-ons", "Search the package feeds for luci-app-… packages", Icons.Rounded.Extension, Tints.violet, onClick = { nav("packages", "") }) })
        }
    }
}
