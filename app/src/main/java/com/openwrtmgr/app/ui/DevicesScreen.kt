package com.openwrtmgr.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.DeviceUnknown
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SwapCalls
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openwrtmgr.app.data.Device
import com.openwrtmgr.app.data.conntrackDelta
import com.openwrtmgr.app.data.DeviceKind
import com.openwrtmgr.app.data.Presence
import com.openwrtmgr.app.data.Router
import kotlinx.coroutines.delay

fun DeviceKind.icon() = when (this) {
    DeviceKind.PHONE -> Icons.Rounded.PhoneAndroid
    DeviceKind.COMPUTER -> Icons.Rounded.Computer
    DeviceKind.TV -> Icons.Rounded.Tv
    DeviceKind.CONSOLE -> Icons.Rounded.SportsEsports
    DeviceKind.IOT -> Icons.Rounded.Lightbulb
    DeviceKind.NETWORK -> Icons.Rounded.Router
    DeviceKind.PRINTER -> Icons.Rounded.Print
    DeviceKind.VM -> Icons.Rounded.Dns
    DeviceKind.UNKNOWN -> Icons.Rounded.DeviceUnknown
}

@Composable
fun DevicesScreen() {
    val data = rememberData("clients") { clients() }
    val nav = LocalNav()
    var filter by remember { mutableStateOf("online") }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("status") }
    // Wi-Fi stations come and go; keep the list fresh while it's on screen.
    LaunchedEffect(data) { while (true) { delay(15_000); data.refresh() } }

    TabScreen("Devices", data, subtitle = (data.state.collectAsState().value as? Load.Ok)?.data?.devices?.let { d ->
        "${d.count { it.online }} online · ${d.count { it.wireless }} Wi-Fi · ${d.count { it.online && !it.wireless }} wired · ${d.count { it.staticIp && it.online }} static"
    }) { c ->
        item {
            Field(query, { query = it }, "Search name, IP or MAC", mono = false)
            Spacer(Modifier.height(8.dp))
            Segmented(listOf("online" to "Online", "wifi" to "Wi-Fi", "wired" to "Wired", "static" to "Static", "all" to "All"), filter, { filter = it })
            Spacer(Modifier.height(8.dp))
            Caps("Sort by", Modifier.padding(bottom = 6.dp))
            ChipPicker(listOf("status" to "Status", "name" to "Name", "ip" to "IP", "signal" to "Signal", "data" to "Data", "network" to "Network"), { it == sort }, { sort = it })
        }
        val list = c.devices.filter { d ->
            when (filter) {
                "online" -> d.online
                "wifi" -> d.wireless
                "wired" -> d.online && !d.wireless
                "static" -> d.staticIp || d.reservation?.ip?.isNotBlank() == true
                else -> true
            } && (query.isBlank() || listOfNotNull(d.name, d.ipv4, d.mac, d.network, d.ssid).any { it.contains(query.trim(), ignoreCase = true) })
        }.let { l ->
            when (sort) {
                "name" -> l.sortedBy { it.name.lowercase() }
                "ip" -> l.sortedBy { it.ipv4?.split('.')?.joinToString("") { p -> p.padStart(3, '0') } ?: "z" }
                "signal" -> l.sortedByDescending { it.station?.signal ?: -999 }
                "data" -> l.sortedByDescending { c.usage?.byMac?.get(it.mac)?.total ?: ((it.station?.rxBytes ?: 0) + (it.station?.txBytes ?: 0)) }
                "network" -> l.sortedWith(compareBy({ it.network ?: "~" }, { it.name.lowercase() }))
                else -> l
            }
        }
        if (list.isEmpty()) item { EmptyNote("No devices match.") }
        items(list, key = { it.mac }) { d -> DeviceRow(d, c.usage?.byMac?.get(d.mac)?.total) { nav("device", d.mac) } }
    }
}

@Composable
private fun DeviceRow(d: Device, periodTotal: Long?, onClick: () -> Unit) {
    Panel(onClick = onClick, padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
        ListRow(
            title = d.name,
            subtitle = listOfNotNull(
                d.ipv4?.let { if (d.staticIp) "$it (static)" else it },
                d.ssid ?: d.network,
                periodTotal?.takeIf { it > 0 }?.let { "${bytes(it)} used" }
                    ?: d.station?.let { s -> bytes((s.rxBytes ?: 0) + (s.txBytes ?: 0)).takeIf { (s.rxBytes ?: 0) > 0 } },
            ).joinToString(" · ").ifBlank { d.mac },
            icon = d.kind.icon(), avatarKey = d.mac, dim = !d.online,
        ) {
            if (d.blockRule?.startTime?.isNotBlank() == true) Tag("scheduled", Ops.warn)
            else if (d.blocked) Tag("blocked", Ops.bad)
            else if (d.wireless) SignalBars(d.station?.signal)
            else if (d.presence == Presence.ONLINE) Tag("wired", Ops.muted)
            else if (d.presence == Presence.IDLE) Tag("idle", Ops.faint)
            else Tag("offline", Ops.faint)
        }
    }
}

@Composable
fun DeviceScreen(mac: String, onBack: () -> Unit) {
    val data = rememberData("clients") { clients() }
    val r = LocalRouter.current
    val ui = LocalUi.current
    var edit by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(false) }
    var schedule by remember { mutableStateOf(false) }
    var forward by remember { mutableStateOf(false) }
    val nav = LocalNav()
    val state = data.state.collectAsState().value
    val dev = (state as? Load.Ok)?.data?.devices?.firstOrNull { it.mac == mac }

    DetailScreen(dev?.name ?: mac, data, onBack, subtitle = mac) { c ->
        val d = c.devices.firstOrNull { it.mac == mac }
        if (d == null) { item { EmptyNote("This device isn't known to the router anymore.") }; return@DetailScreen }
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(d.kind.icon(), if (d.blocked) Ops.bad else Ops.accent, 48.dp)
                    Spacer(Modifier.width(14.dp))
                    androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val (label, color) = when (d.presence) {
                                Presence.ONLINE -> "Online" to Ops.ok
                                Presence.IDLE -> "Idle (seen recently)" to Ops.warn
                                Presence.OFFLINE -> "Offline" to Ops.faint
                            }
                            Dot(color, pulse = d.presence == Presence.ONLINE)
                            Text(label, color = color, fontSize = 13.sp)
                        }
                        Text(
                            listOfNotNull(if (d.wireless) "Wi-Fi · ${d.ssid ?: d.station?.ifname}" else if (d.online) "Wired" else null, d.network?.let { "network $it" })
                                .joinToString(" · ").ifBlank { "Not connected now" },
                            color = Ops.muted, fontSize = 13.sp,
                        )
                    }
                    if (d.blocked) Tag("internet blocked", Ops.bad)
                }
            }
        }
        if (d.online && d.ipv4 != null) item { LiveUsage(d) }
        item { DataUsed(d, c.usage?.byMac?.get(d.mac), c.usage?.since, c.usage != null) }
        d.station?.let { s ->
            item {
                Panel(title = "Wireless link") {
                    Row {
                        Metric("Signal", s.signal?.toString() ?: "—", "dBm", modifier = Modifier.weight(1f))
                        Metric("Rx rate", kbps(s.rxRate).substringBefore(' '), kbps(s.rxRate).substringAfter(' ', ""), Ops.accent, modifier = Modifier.weight(1f))
                        Metric("Tx rate", kbps(s.txRate).substringBefore(' '), kbps(s.txRate).substringAfter(' ', ""), Ops.violet, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    s.connectedSec?.let { KV("Connected for", duration(it)) }
                    KV("Interface", s.ifname)
                }
            }
        }
        item {
            Panel(title = "Addresses") {
                KV("MAC", d.mac)
                KV("IPv4", d.ipv4?.let { if (d.staticIp) "$it · static" else "$it · DHCP" } ?: "—")
                d.network?.let { KV("Network", it) }
                d.ipv6.forEach { KV("IPv6", it) }
                d.lease?.expires?.let { KV("Lease expires", if (it <= 0) "never" else "in ${duration(it)}") }
                KV("Reserved IP", d.reservation?.ip?.ifBlank { null } ?: "no")
            }
        }
        item {
            Panel(title = "Actions") {
                ListRow(
                    if (d.blocked) "Allow internet" else "Block internet",
                    "Firewall rule rejecting this MAC towards WAN. LAN access is unaffected.",
                    if (d.blocked) Icons.Rounded.Lan else Icons.Rounded.Block, if (d.blocked) Tints.green else Tints.red,
                    onClick = { if (d.blocked) ui.run("Unblocking…", "Internet access restored", data::refresh) { r.setBlocked(d.mac, d.name, false) } else confirmBlock = true },
                )
                ListRow(
                    "Internet schedule",
                    d.blockRule?.takeIf { it.startTime.isNotBlank() || it.weekdays.isNotBlank() }?.let { "Paused ${it.startTime.ifBlank { "00:00" }}–${it.stopTime.ifBlank { "23:59" }} ${it.weekdays.ifBlank { "daily" }}" }
                        ?: "Pause internet at set times (bedtime, homework…)",
                    Icons.Rounded.Schedule, Tints.amber, onClick = { schedule = true },
                )
                if (d.ipv4 != null) ListRow("Live connections", "What ${d.name} is talking to right now", Icons.Rounded.SwapCalls, Tints.cyan, onClick = { nav("conns", d.ipv4) })
                if (d.ipv4 != null) ListRow("Open a port", "Forward a port from the internet to ${d.ipv4}", Icons.Rounded.Public, Tints.violet, onClick = { forward = true })
                ListRow("Name & IP reservation", d.reservation?.let { "${it.name.ifBlank { "unnamed" }} · ${it.ip.ifBlank { "dynamic" }}" } ?: "Give it a name and a fixed address",
                    Icons.Rounded.Edit, onClick = { edit = true })
                if (d.station != null) ListRow("Disconnect from Wi-Fi", "Kicks it off; it can reconnect after 1 minute", Icons.Rounded.LinkOff, Ops.warn,
                    onClick = { ui.run("Disconnecting…", "Disconnected", data::refresh) { r.kick(d.station.ifname, d.mac, 60_000) } })
                if (!d.online) ListRow("Wake on LAN", "Send a magic packet to switch ${d.name} on", Icons.Rounded.Bolt, Tints.green,
                    onClick = { ui.run("Sending wake packet…", "Wake packet sent") { r.wake(d.mac, d.network?.let { n -> r.interfaces().firstOrNull { it.name == n }?.device }) } })
                if (d.station == null && d.online) ListRow("Connected by cable", "Wired devices can be blocked but not kicked", Icons.Rounded.Cable, Ops.faint)
            }
        }
    }

    if (confirmBlock && dev != null) Confirm("Block ${dev.name}?", "It stays on your network but can't reach the internet until you allow it again.", "Block",
        onDismiss = { confirmBlock = false }) { ui.run("Blocking…", "Internet blocked for ${dev.name}", data::refresh) { r.setBlocked(dev.mac, dev.name, true) } }

    if (edit && dev != null) ReservationSheet(r, dev, onDismiss = { edit = false }) { data.refresh() }
    if (schedule && dev != null) ScheduleSheet(r, dev, onDismiss = { schedule = false }) { data.refresh() }
    if (forward && dev?.ipv4 != null) ForwardSheet(r, null, listOf("wan", "lan"), { forward = false }, initialIp = dev.ipv4, initialName = dev.name) { }
}

/** Live up/down rate for one device: Wi-Fi byte counters, or summed conntrack bytes for wired. */
@Composable
private fun LiveUsage(d: Device) {
    val r = LocalRouter.current
    var down by remember { mutableStateOf(listOf<Float>()) }
    var up by remember { mutableStateOf(listOf<Float>()) }
    LaunchedEffect(d.mac) {
        var last: Triple<Long, Long, Long>? = null
        var lastConns: Pair<List<com.openwrtmgr.app.data.Conn>, Long>? = null
        while (true) {
            val now = System.currentTimeMillis()
            if (d.wireless) {
                runCatching { r.stationBytes(d) }.getOrNull()?.let { (rx, tx) ->
                    last?.let { (rx0, tx0, t0) ->
                        val dt = (now - t0) / 1000f
                        if (dt > 0) {
                            down = (down + ((rx - rx0).coerceAtLeast(0) / dt)).takeLast(60)
                            up = (up + ((tx - tx0).coerceAtLeast(0) / dt)).takeLast(60)
                        }
                    }
                    last = Triple(rx, tx, now)
                }
            } else d.ipv4?.let { ip ->
                runCatching { r.connectionsOf(ip) }.getOrNull()?.let { conns ->
                    lastConns?.let { (prev, t0) ->
                        val dt = (now - t0) / 1000f
                        if (dt > 0) down = (down + conntrackDelta(prev, conns) / dt).takeLast(60)
                    }
                    lastConns = conns to now
                }
            }
            delay(2000)
        }
    }
    Panel(title = "Live usage", padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        Row {
            val (dv, du) = rate(down.lastOrNull() ?: 0f); val (uv, uu) = rate(up.lastOrNull() ?: 0f)
            if (d.wireless) {
                Metric("↓ Download", dv, du, Ops.accent, modifier = Modifier.weight(1f))
                Metric("↑ Upload", uv, uu, Ops.violet, modifier = Modifier.weight(1f))
            } else Metric("⇅ Traffic", dv, du, Ops.accent, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        TrafficChart(down, if (d.wireless) up else emptyList())
        Text(if (d.wireless) "From the Wi-Fi link counters." else "Wired: both directions combined, from this device's tracked connections.", color = Ops.faint, fontSize = 11.sp)
    }
}

/**
 * How much this device has used, by window: this accounting period (nlbwmon, survives reconnects
 * and covers wired devices) and the current Wi-Fi session (the station's link counters).
 */
@Composable
private fun DataUsed(d: Device, period: com.openwrtmgr.app.data.HostUsage?, since: String?, nlbw: Boolean) {
    Panel(title = "Data used") {
        if (period != null) {
            Caps(since?.let { "This period · since ${periodStart(it)}" } ?: "This period")
            Spacer(Modifier.height(4.dp))
            Row {
                Metric("↓ Downloaded", bytes(period.rx).substringBefore(' '), bytes(period.rx).substringAfter(' ', ""), Ops.accent, modifier = Modifier.weight(1f))
                Metric("↑ Uploaded", bytes(period.tx).substringBefore(' '), bytes(period.tx).substringAfter(' ', ""), Ops.violet, modifier = Modifier.weight(1f))
            }
            KV("Total", bytes(period.total))
            KV("Connections", period.conns.toString())
        }
        d.station?.let { s ->
            if (period != null) { Spacer(Modifier.height(6.dp)); Divider(); Spacer(Modifier.height(10.dp)) }
            Caps(s.connectedSec?.let { "This Wi-Fi session · ${duration(it)}" } ?: "This Wi-Fi session")
            Spacer(Modifier.height(4.dp))
            Row {
                Metric("↓ Downloaded", bytes(s.txBytes).substringBefore(' '), bytes(s.txBytes).substringAfter(' ', ""), Ops.accent, modifier = Modifier.weight(1f)) // router tx = device download
                Metric("↑ Uploaded", bytes(s.rxBytes).substringBefore(' '), bytes(s.rxBytes).substringAfter(' ', ""), Ops.violet, modifier = Modifier.weight(1f))
            }
        }
        if (period == null && d.station == null) Text("No totals for this device yet.", color = Ops.muted, fontSize = 13.sp)
        if (!nlbw) {
            Spacer(Modifier.height(8.dp))
            Text("Install luci-app-nlbwmon on the router to keep totals across reconnects and for wired devices.", color = Ops.faint, fontSize = 11.sp)
        }
    }
}

private val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

@Composable
private fun ScheduleSheet(r: Router, d: Device, onDismiss: () -> Unit, onDone: () -> Unit) {
    val ui = LocalUi.current
    val rule = d.blockRule
    var start by remember { mutableStateOf(rule?.startTime?.take(5)?.ifBlank { null } ?: "22:00") }
    var stop by remember { mutableStateOf(rule?.stopTime?.take(5)?.ifBlank { null } ?: "07:00") }
    var days by remember { mutableStateOf(rule?.weekdays?.split(' ')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()) }
    val time = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
    val ok = start.matches(time) && stop.matches(time)
    EditSheet(
        "Internet schedule · ${d.name}", onDismiss, saveEnabled = ok,
        onDelete = rule?.let { { ui.run("Removing schedule…", "Internet allowed again", onDone) { r.setBlocked(d.mac, d.name, false); onDismiss() } } },
        onSave = {
            ui.run("Applying schedule…", "Schedule saved", onDone) {
                r.saveBlockSchedule(d.mac, d.name, start, stop, DAYS.filter { it in days }); onDismiss()
            }
        },
    ) {
        Text("Internet is blocked between these times. A window past midnight (22:00–07:00) is fine.", color = Ops.muted, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(start, { start = it.take(5) }, "Block from", Modifier.weight(1f), mono = true, isError = !start.matches(time), placeholder = "22:00")
            Field(stop, { stop = it.take(5) }, "Until", Modifier.weight(1f), mono = true, isError = !stop.matches(time), placeholder = "07:00")
        }
        Caps("Days (none = every day)")
        ChipPicker(DAYS.map { it to it }, { it in days }, { day -> days = if (day in days) days - day else days + day })
        Text("Uses the router's clock and time zone.", color = Ops.faint, fontSize = 12.sp)
    }
}

@Composable
private fun ReservationSheet(r: Router, d: Device, onDismiss: () -> Unit, onDone: () -> Unit) {
    val ui = LocalUi.current
    var name by remember { mutableStateOf(d.reservation?.name ?: d.name.takeUnless { it == d.mac }.orEmpty()) }
    var ip by remember { mutableStateOf(d.reservation?.ip ?: d.ipv4.orEmpty()) }
    val ipOk = ip.isBlank() || ip.matches(Regex("^(\\d{1,3}\\.){3}\\d{1,3}$"))
    EditSheet(
        "Name & reservation", onDismiss,
        onSave = { ui.run("Saving…", "Saved. Takes effect at the device's next DHCP renewal.", onDone) { r.saveReservation(d.reservation?.section, d.mac, name.trim(), ip.trim()); onDismiss() } },
        saveEnabled = ipOk && (name.isNotBlank() || ip.isNotBlank()),
        onDelete = d.reservation?.let { res -> { ui.run("Removing…", "Reservation removed", onDone) { r.deleteReservation(res.section); onDismiss() } } },
    ) {
        Field(name, { name = it }, "Name", supporting = "Also answers DNS as <name>.lan")
        Field(ip, { ip = it }, "Fixed IPv4", mono = true, keyboard = KeyboardType.Decimal, isError = !ipOk, supporting = "Leave empty to keep a dynamic address")
        MonoText(d.mac, color = Ops.muted, size = 12)
    }
}
