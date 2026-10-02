package com.openwrtmgr.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiLock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.openwrtmgr.app.data.Radio
import com.openwrtmgr.app.data.Router
import com.openwrtmgr.app.data.Ssid
import com.openwrtmgr.app.data.encryptionLabel

@Composable
fun WifiScreen() {
    val data = rememberData("wireless") { wireless() }
    val r = LocalRouter.current
    val ui = LocalUi.current
    val nav = LocalNav()
    var editSsid by remember { mutableStateOf<Ssid?>(null) }
    var newOnRadio by remember { mutableStateOf<String?>(null) }
    var editRadio by remember { mutableStateOf<Radio?>(null) }
    var qr by remember { mutableStateOf<Ssid?>(null) }
    var toggleRadio by remember { mutableStateOf<Radio?>(null) }
    val clients = rememberData("clients") { clients() }
    val devices = (clients.state.collectAsState().value as? Load.Ok)?.data?.devices.orEmpty()

    var guest by remember { mutableStateOf(false) }
    var sched by remember { mutableStateOf(false) }
    TabScreen("Wi-Fi", data, subtitle = "Radios, networks & passwords") { w ->
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Guest network", Icons.Rounded.Add) { guest = true }
                GhostButton("Schedule", Icons.Rounded.Schedule, Ops.muted) { sched = true }
            }
        }
        w.radios.forEach { radio ->
            val ssids = w.ssids.filter { it.radio == radio.section }
            item(key = radio.section) {
                Panel(padding = PaddingValues(0.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(radio.band, color = Ops.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.width(4.dp))
                                    Dot(if (radio.up) Ops.ok else Ops.faint, pulse = radio.up, size = 6.dp)
                                }
                                Text(
                                    listOfNotNull(
                                        radio.section,
                                        "ch ${radio.liveChannel ?: radio.channel}",
                                        radio.htmode.ifBlank { null },
                                        radio.liveTxPower?.let { "$it dBm" },
                                        radio.noise?.takeIf { it != 0 }?.let { "noise $it" },
                                    ).joinToString(" · "),
                                    color = Ops.muted, fontSize = 13.sp, maxLines = 2,
                                )
                            }
                            IconButton(onClick = { editRadio = radio }) { Icon(Icons.Rounded.Settings, "Radio settings", tint = Ops.muted) }
                            OpsSwitch(!radio.disabled, { toggleRadio = radio })
                        }
                    }
                    Divider()
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        ssids.forEach { s ->
                            ListRow(
                                s.ssid.ifBlank { "(no name)" },
                                listOf(encryptionLabel(s.encryption), s.network.joinToString(",").ifBlank { "no network" }, s.mode.takeIf { it != "ap" })
                                    .filterNotNull().joinToString(" · ") + if (s.hidden) " · hidden" else "",
                                icon = if (s.encryption == "none") Icons.Rounded.Wifi else Icons.Rounded.WifiLock,
                                iconTint = if (s.disabled) Ops.faint else Ops.accent,
                                onClick = { editSsid = s },
                            ) {
                                val n = devices.count { d -> d.station != null && d.station.ifname == s.ifname }
                                if (n > 0) Tag("$n", Ops.ok)
                                if (s.disabled) Tag("off", Ops.faint)
                                else if (s.ifname == null && radio.up) Tag("down", Ops.warn)
                                if (s.mode == "ap" && s.encryption != "wpa" && !s.encryption.startsWith("wpa2") && !s.encryption.startsWith("wpa3")) {
                                    IconButton(onClick = { qr = s }) { Icon(Icons.Rounded.QrCode2, "Share", tint = Ops.muted) }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GhostButton("Add network", Icons.Rounded.Add) { newOnRadio = radio.section }
                            ssids.firstOrNull { it.ifname != null }?.let { s ->
                                GhostButton("Scan", Icons.Rounded.Radar, Ops.muted) { nav("scan", s.ifname!!) }
                            }
                        }
                    }
                }
            }
        }
        if (w.radios.isEmpty()) item { EmptyNote("No wireless radios on this router.") }
    }

    toggleRadio?.let { radio ->
        val enable = radio.disabled
        Confirm(
            if (enable) "Enable ${radio.band}?" else "Disable ${radio.band}?",
            if (enable) "Starts every network on this radio." else "Every network on this radio goes off. If this phone is connected through it, the change is rolled back automatically.",
            if (enable) "Enable" else "Disable", danger = !enable, onDismiss = { toggleRadio = null },
        ) { ui.run(if (enable) "Enabling radio…" else "Disabling radio…", "Applied", data::refresh) { r.saveRadio(radio.section, mapOf("disabled" to if (enable) null else "1")) } }
    }
    editRadio?.let { RadioSheet(r, it, { editRadio = null }) { data.refresh() } }
    if (editSsid != null || newOnRadio != null) {
        val nets = remember { mutableStateOf<List<String>>(emptyList()) }
        LaunchedEffect(Unit) { nets.value = runCatching { r.network().config.map { it.section }.filter { it != "loopback" } }.getOrDefault(listOf("lan")) }
        SsidSheet(r, editSsid, newOnRadio, nets.value, devices.filter { d -> editSsid?.ifname != null && d.station?.ifname == editSsid?.ifname },
            { editSsid = null; newOnRadio = null }) { data.refresh(); clients.refresh() }
    }
    qr?.let { QrDialog(it) { qr = null } }
    val radios = (data.state.collectAsState().value as? Load.Ok)?.data?.radios.orEmpty()
    if (guest) GuestSheet(r, radios, { guest = false }) { data.refresh() }
    if (sched) WifiScheduleSheet(r) { sched = false }
}

@Composable
private fun GuestSheet(r: Router, radios: List<Radio>, onDismiss: () -> Unit, onDone: () -> Unit) {
    val ui = LocalUi.current
    var ssid by remember { mutableStateOf("Guest") }
    var key by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(radios.filter { !it.disabled }.map { it.section }.toSet()) }
    EditSheet("New guest network", onDismiss, saveLabel = "Create & apply",
        saveEnabled = ssid.isNotBlank() && key.length in 8..63 && picked.isNotEmpty(),
        onSave = { ui.run("Creating guest network…", null, onDone) { val where = r.createGuestNetwork(ssid.trim(), key, picked.toList()); ui.toast("Guest network ready: $where"); onDismiss() } }) {
        Text("Creates its own subnet, DHCP and firewall zone. Guests reach the internet only: not your devices, not the router's admin pages. Clients are isolated from each other.", color = Ops.muted, fontSize = 13.sp)
        Field(ssid, { ssid = it }, "Network name")
        Field(key, { key = it }, "Password (WPA2/WPA3)", password = true, isError = key.isNotEmpty() && key.length < 8, supporting = "8–63 characters")
        Caps("Broadcast on")
        ChipPicker(radios.map { it.section to it.band }, { it in picked }, { s -> picked = if (s in picked) picked - s else picked + s })
        RollbackNote()
    }
}

@Composable
private fun WifiScheduleSheet(r: Router, onDismiss: () -> Unit) {
    val ui = LocalUi.current
    var loaded by remember { mutableStateOf(false) }
    var on by remember { mutableStateOf(false) }
    var off by remember { mutableStateOf("23:00") }
    var start by remember { mutableStateOf("06:30") }
    var days by remember { mutableStateOf("*") }
    LaunchedEffect(Unit) {
        runCatching { r.wifiSchedule() }.getOrNull()?.let { off = it.off; start = it.on.ifBlank { "06:30" }; days = it.days; on = true }
        loaded = true
    }
    val time = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
    EditSheet("Wi-Fi schedule", onDismiss, saveEnabled = loaded && (!on || (off.matches(time) && start.matches(time))), onSave = {
        ui.run("Saving schedule…", if (on) "Wi-Fi will turn off at $off and on at $start" else "Wi-Fi schedule removed") {
            r.setWifiSchedule(if (on) Router.WifiSchedule(off, start, days) else null); onDismiss()
        }
    }) {
        Text("Turns every radio off at night and back on in the morning. Wired devices are unaffected.", color = Ops.muted, fontSize = 13.sp)
        SwitchRow("Enable schedule", on) { on = it }
        if (on) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(off, { off = it.take(5) }, "Wi-Fi off", Modifier.weight(1f), mono = true, isError = !off.matches(time))
                Field(start, { start = it.take(5) }, "Wi-Fi on", Modifier.weight(1f), mono = true, isError = !start.matches(time))
            }
            Caps("Days")
            Segmented(listOf("*" to "Every night", "0-4" to "School nights", "1-5" to "Weeknights"), days, { days = it })
            Text("Uses the router's clock and time zone.", color = Ops.faint, fontSize = 12.sp)
        }
    }
}

@Composable
private fun RadioSheet(r: Router, radio: Radio, onDismiss: () -> Unit, onDone: () -> Unit) {
    val ui = LocalUi.current
    var channel by remember { mutableStateOf(radio.channel) }
    var htmode by remember { mutableStateOf(radio.htmode) }
    var txpower by remember { mutableStateOf(radio.txpower) }
    var country by remember { mutableStateOf(radio.country) }
    var channels by remember { mutableStateOf<List<Int>>(emptyList()) }
    LaunchedEffect(radio.section) {
        val ifn = runCatching { r.wireless().ssids.firstOrNull { it.radio == radio.section && it.ifname != null }?.ifname }.getOrNull()
        if (ifn != null) channels = runCatching { r.channels(ifn) }.getOrDefault(emptyList())
    }
    val widths = if (radio.band == "2.4 GHz") listOf("HT20", "HT40", "HE20", "HE40") else listOf("VHT20", "VHT40", "VHT80", "HE20", "HE40", "HE80", "HE160")
    EditSheet("${radio.band} radio", onDismiss, onSave = {
        ui.run("Applying radio settings…", "Radio updated", onDone) {
            r.saveRadio(radio.section, mapOf("channel" to channel, "htmode" to htmode, "txpower" to txpower.ifBlank { null }, "country" to country.uppercase().ifBlank { null }))
            onDismiss()
        }
    }) {
        Caps("Channel")
        ChipPicker(listOf("auto" to "auto") + (channels.ifEmpty { listOfNotNull(radio.channel.toIntOrNull()) }).map { it.toString() to it.toString() }, { it == channel }, { channel = it })
        Caps("Channel width")
        ChipPicker((widths + htmode).distinct().filter { it.isNotBlank() }.map { it to it }, { it == htmode }, { htmode = it })
        Field(txpower, { txpower = it.filter(Char::isDigit) }, "Transmit power (dBm)", mono = true, supporting = "Empty = driver default (maximum allowed)")
        Field(country, { country = it.take(2) }, "Country code", mono = true, supporting = "Sets legal channels and power, e.g. IN, US, DE")
        RollbackNote()
    }
}

private val encryptions = listOf(
    "sae-mixed" to "WPA2/WPA3", "sae" to "WPA3", "psk2" to "WPA2", "psk-mixed" to "WPA/WPA2", "owe" to "OWE", "none" to "Open",
)

@Composable
private fun SsidSheet(r: Router, s: Ssid?, radio: String?, networks: List<String>, connected: List<com.openwrtmgr.app.data.Device>, onDismiss: () -> Unit, onDone: () -> Unit) {
    val ui = LocalUi.current
    var ssid by remember { mutableStateOf(s?.ssid.orEmpty()) }
    var enc by remember { mutableStateOf(s?.encryption?.substringBefore('+') ?: "sae-mixed") }
    var key by remember { mutableStateOf(s?.key.orEmpty()) }
    var net by remember { mutableStateOf(s?.network ?: listOf("lan")) }
    var hidden by remember { mutableStateOf(s?.hidden ?: false) }
    var isolate by remember { mutableStateOf(s?.isolate ?: false) }
    var enabled by remember { mutableStateOf(!(s?.disabled ?: false)) }
    var askDelete by remember { mutableStateOf(false) }
    var macfilter by remember { mutableStateOf(s?.macfilter ?: "disable") }
    var maclist by remember { mutableStateOf(s?.maclist?.joinToString("\n").orEmpty()) }
    val needsKey = enc !in listOf("none", "owe")
    val keyOk = !needsKey || key.length in 8..63
    val enterprise = s != null && s.encryption.startsWith("wpa")

    EditSheet(
        if (s == null) "New network" else s.ssid, onDismiss,
        saveEnabled = ssid.isNotBlank() && ssid.toByteArray().size <= 32 && keyOk && !enterprise,
        onDelete = s?.let { { askDelete = true } },
        onSave = {
            val values = mapOf(
                "ssid" to ssid, "encryption" to enc, "key" to if (needsKey) key else null, "network" to net,
                "hidden" to if (hidden) "1" else null, "isolate" to if (isolate) "1" else null, "disabled" to if (enabled) null else "1",
                "macfilter" to macfilter.takeIf { it != "disable" }, "maclist" to maclist.split(Regex("[\\s,]+")).filter { it.isNotBlank() }.map { it.uppercase() },
            )
            ui.run("Applying Wi-Fi settings…", "Saved. Devices may need to reconnect.", onDone) {
                r.saveSsid(s?.section, if (s == null) values + mapOf("device" to radio, "mode" to "ap") else values); onDismiss()
            }
        },
    ) {
        if (enterprise) Text("This network uses WPA-Enterprise (RADIUS). Edit it in LuCI.", color = Ops.warn, fontSize = 13.sp)
        SwitchRow("Enabled", enabled) { enabled = it }
        Field(ssid, { ssid = it }, "Network name (SSID)")
        Caps("Security")
        ChipPicker(encryptions, { it == enc }, { enc = it })
        if (needsKey) Field(key, { key = it }, "Password", password = true, isError = !keyOk, supporting = "8–63 characters")
        Caps("Connect clients to")
        ChipPicker(networks.ifEmpty { net }.map { it to it }, { it in net }, { n -> net = listOf(n) })
        SwitchRow("Hide network name", hidden, "Clients must type the SSID to join") { hidden = it }
        SwitchRow("Client isolation", isolate, "Devices on this network can't see each other (good for guests)") { isolate = it }
        Caps("MAC filter")
        Segmented(listOf("disable" to "Off", "allow" to "Allow only", "deny" to "Block listed"), macfilter, { macfilter = it })
        if (macfilter != "disable") Field(maclist, { maclist = it }, "MAC addresses", mono = true, singleLine = false, supporting = "One per line")
        if (connected.isNotEmpty()) {
            Caps("Connected now (${connected.size})")
            connected.forEach { d ->
                ListRow(d.name, "${d.ipv4 ?: d.mac} · ${d.station?.signal ?: "?"} dBm", d.kind.icon(), subtitleMono = true) {
                    androidx.compose.material3.TextButton(onClick = {
                        ui.run("Disconnecting ${d.name}…", "Disconnected", onDone) { r.kick(d.station!!.ifname, d.mac, 60_000) }
                    }) { Text("Kick", color = Ops.warn) }
                }
            }
        }
        RollbackNote()
    }
    if (askDelete && s != null) Confirm("Delete ${s.ssid}?", "Removes this wireless network. Connected devices are disconnected.", "Delete", onDismiss = { askDelete = false }) {
        ui.run("Deleting network…", "Network deleted", onDone) { r.deleteSsid(s.section); onDismiss() }
    }
}

/** Standard WIFI: QR payload, as phones' cameras expect it. */
fun wifiQrPayload(ssid: String, encryption: String, key: String, hidden: Boolean): String {
    fun esc(v: String) = v.replace(Regex("([\\\\;,:\"])"), "\\\\$1")
    val type = when (encryption.substringBefore('+')) { "none", "owe" -> "nopass"; "sae" -> "SAE"; else -> "WPA" }
    return buildString {
        append("WIFI:T:$type;S:${esc(ssid)};")
        if (type != "nopass") append("P:${esc(key)};")
        if (hidden) append("H:true;")
        append(";")
    }
}

private fun qrBitmap(text: String, size: Int = 640): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8"))
    val px = IntArray(size * size) { if (m[it % size, it / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

@Composable
private fun QrDialog(s: Ssid, onDismiss: () -> Unit) {
    val bmp = remember(s) { qrBitmap(wifiQrPayload(s.ssid, s.encryption, s.key, s.hidden)).asImageBitmap() }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Ops.panel,
        title = { Text("Join ${s.ssid}", color = Ops.text) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(10.dp)) {
                    Image(bmp, "Wi-Fi QR code", Modifier.size(240.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text("Point a phone camera at this code to join.", color = Ops.muted, fontSize = 13.sp)
                if (s.key.isNotEmpty()) { Spacer(Modifier.height(6.dp)); MonoText(s.key, color = Ops.text, size = 14) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = Ops.accent) } },
    )
}

@Composable
fun ScanScreen(ifname: String, onBack: () -> Unit) {
    val data = rememberData("scan:$ifname") { scan(ifname) }
    DetailScreen("Nearby networks", data, onBack, subtitle = "scanned from $ifname") { list ->
        item {
            // Channel congestion at a glance: how many networks sit on each channel.
            val byCh = list.mapNotNull { it.channel }.groupingBy { it }.eachCount().toSortedMap()
            if (byCh.isNotEmpty()) Panel(title = "Channel usage") {
                val max = byCh.values.max().toFloat()
                Row(Modifier.fillMaxWidth().height(90.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    byCh.forEach { (ch, n) ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            MonoText("$n", color = Ops.muted, size = 10)
                            Box(Modifier.fillMaxWidth().height((60 * n / max).dp).clip(RoundedCornerShape(3.dp)).background(if (n == max.toInt()) Ops.warn else Ops.accent.copy(alpha = 0.6f)))
                            MonoText("$ch", color = Ops.faint, size = 10)
                        }
                    }
                }
            }
        }
        items(list, key = { it.bssid + it.ssid }) { n ->
            Panel(padding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                ListRow(n.ssid, "${n.bssid} · ch ${n.channel ?: "?"} · ${n.encryption}", subtitleMono = true) {
                    MonoText("${n.signal ?: "?"} dBm", color = Ops.muted, size = 12)
                    Spacer(Modifier.width(8.dp))
                    SignalBars(n.signal)
                }
            }
        }
        if (list.isEmpty()) item { EmptyNote("Nothing found. Some drivers can't scan while serving clients.") }
    }
}
