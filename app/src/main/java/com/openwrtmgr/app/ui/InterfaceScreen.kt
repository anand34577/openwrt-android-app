package com.openwrtmgr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openwrtmgr.app.data.Conn
import com.openwrtmgr.app.data.DevStats
import com.openwrtmgr.app.data.Device
import com.openwrtmgr.app.data.Iface
import com.openwrtmgr.app.data.Router
import com.openwrtmgr.app.data.conntrackDeltaByIp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** What one device is moving right now, in bytes/s. Wired devices only have a combined figure. */
data class LiveRate(val down: Float, val up: Float?) { val total get() = down + (up ?: 0f) }

/**
 * One interface, live: its own traffic graph and counters, plus every device behind it with
 * what each is using right now. Uplinks list every online device, since all their internet
 * traffic passes through it.
 */
class IfaceVM(private val r: Router, val name: String) : ViewModel() {
    var iface by mutableStateOf<Iface?>(null)
    var stats by mutableStateOf<DevStats?>(null)
    var down by mutableStateOf(listOf<Float>()); private set
    var up by mutableStateOf(listOf<Float>()); private set
    var clients by mutableStateOf<Router.Clients?>(null)
    var rates by mutableStateOf<Map<String, LiveRate>>(emptyMap()); private set
    /** Busiest-first MACs, re-ranked every 10 s so rows don't jump under a finger every sample. */
    var order by mutableStateOf<List<String>>(emptyList()); private set
    var error by mutableStateOf<String?>(null)

    private var lastDev: Triple<Long, Long, Long>? = null
    private var lastSta: Pair<Map<String, Pair<Long, Long>>, Long>? = null
    private var lastConns: Pair<List<Conn>, Long>? = null

    /** Devices this interface serves: everyone online for an uplink, else those on this network. */
    val members: List<Device>
        get() {
            val all = clients?.devices.orEmpty().filter { it.online }
            return if (iface?.uplink == true) all else all.filter { it.network == name }
        }

    suspend fun poll() {
        var tick = 0
        while (true) {
            try {
                if (tick % 10 == 0) { iface = r.interfaces().firstOrNull { it.name == name }; clients = r.clients() }
                sampleInterface()
                sampleDevices()
                if (tick % 5 == 1 || order.isEmpty()) order = members.sortedWith(
                    compareByDescending<Device> { rates[it.mac]?.total ?: -1f }.thenByDescending { clients?.usage?.byMac?.get(it.mac)?.total ?: 0 },
                ).map { it.mac }
                error = null
            } catch (e: CancellationException) { throw e } catch (e: Throwable) { error = e.friendly() }
            tick++
            delay(2000)
        }
    }

    private suspend fun sampleInterface() {
        val i = iface ?: return
        val s = r.deviceStats()[i.device ?: return] ?: return
        stats = s
        val now = System.currentTimeMillis()
        lastDev?.let { (rx0, tx0, t0) ->
            val dt = (now - t0) / 1000f
            if (dt > 0 && s.rx >= rx0 && s.tx >= tx0) {
                val (d, u) = directional(i, (s.rx - rx0) / dt, (s.tx - tx0) / dt)
                down = (down + d).takeLast(OverviewVM.HISTORY); up = (up + u).takeLast(OverviewVM.HISTORY)
            }
        }
        lastDev = Triple(s.rx, s.tx, now)
    }

    private suspend fun sampleDevices() {
        val devs = members
        if (devs.isEmpty()) { rates = emptyMap(); return }
        val now = System.currentTimeMillis()
        val out = HashMap<String, LiveRate>()

        // Wi-Fi: the station's link counters (router tx = device download).
        val aps = devs.mapNotNull { it.station?.ifname }.distinct()
        if (aps.isNotEmpty()) {
            val sta = aps.flatMap { r.stations(it) }.associate { it.mac to ((it.txBytes ?: 0) to (it.rxBytes ?: 0)) }
            lastSta?.let { (prev, t0) ->
                val dt = (now - t0) / 1000f
                if (dt > 0) for ((mac, b) in sta) {
                    val a = prev[mac] ?: continue
                    out[mac] = LiveRate((b.first - a.first).coerceAtLeast(0) / dt, (b.second - a.second).coerceAtLeast(0) / dt)
                }
            }
            lastSta = sta to now
        }

        // Wired: growth of each tracked connection, both directions together.
        val wired = devs.filter { !it.wireless && it.ipv4 != null }.associateBy { it.ipv4!! }
        if (wired.isNotEmpty()) {
            val conns = runCatching { r.connections() }.getOrNull()
            if (conns != null) {
                lastConns?.let { (prev, t0) ->
                    val dt = (now - t0) / 1000f
                    if (dt > 0) for ((ip, bytes) in conntrackDeltaByIp(prev, conns, wired.keys)) out[wired.getValue(ip).mac] = LiveRate(bytes / dt, null)
                    for (d in wired.values) out.putIfAbsent(d.mac, LiveRate(0f, null))
                }
                lastConns = conns to now
            }
        }
        rates = out
    }
}

@Composable
fun InterfaceScreen(name: String, onBack: () -> Unit) {
    val r = LocalRouter.current
    val vm: IfaceVM = viewModel(key = "iface:${r.profile.id}:$name") { IfaceVM(r, name) }
    val nav = LocalNav()
    LaunchedEffect(vm) { vm.poll() }
    val i = vm.iface

    LazyColumn(
        Modifier.fillMaxSize().background(Ops.bg),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header(name, i?.let { listOfNotNull(it.proto, it.device).joinToString(" · ") } ?: "Interface", onBack = onBack) }
        vm.error?.let { item { Panel { Row(verticalAlignment = Alignment.CenterVertically) { Dot(Ops.bad); Text(it, color = Ops.muted, fontSize = 13.sp) } } } }
        if (i == null) { if (vm.error == null) item { LoadingBlock() }; return@LazyColumn }

        item { TrafficPanel(vm, i) }
        item { DetailsPanel(i, vm.stats) }

        val members = vm.members
        val usage = vm.clients?.usage
        val rank = vm.order.withIndex().associate { (i, mac) -> mac to i }
        val ranked = members.sortedBy { rank[it.mac] ?: Int.MAX_VALUE }
        val busiest = ranked.firstNotNullOfOrNull { vm.rates[it.mac]?.total }?.takeIf { it > 0f }
        section(if (i.uplink) "Who's using the internet" else "Devices on $name", trailing = { Caps("${members.size} online") })
        if (vm.clients == null) item { LoadingBlock() }
        else if (ranked.isEmpty()) item { EmptyNote(if (i.uplink) "No devices online." else "No devices are online on $name right now.") }
        else items(ranked, key = { it.mac }) { d ->
            Box(Modifier.animateItem()) { ClientUsageRow(d, vm.rates[d.mac], usage?.byMac?.get(d.mac), busiest) { nav("device", d.mac) } }
        }
        item {
            Text(
                listOfNotNull(
                    "Right now is measured every 2 seconds: Wi-Fi from the link counters, wired from the device's tracked connections (download and upload combined).",
                    usage?.let { "Totals are nlbwmon's count since ${it.since?.let(::periodStart) ?: "its period began"}." }
                        ?: "Install luci-app-nlbwmon on the router to see each device's total use, including wired devices.",
                ).joinToString(" "),
                color = Ops.faint, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun TrafficPanel(vm: IfaceVM, i: Iface) {
    Panel(title = "Traffic", action = { Dot(if (i.up) Ops.ok else Ops.faint, pulse = i.up) }) {
        Row {
            val (dv, du) = rate(vm.down.lastOrNull() ?: 0f); val (uv, uu) = rate(vm.up.lastOrNull() ?: 0f)
            Metric("↓ Download", dv, du, Ops.accent, modifier = Modifier.weight(1f))
            Metric("↑ Upload", uv, uu, Ops.violet, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        TrafficChart(vm.down, vm.up, capacity = OverviewVM.HISTORY)
        Spacer(Modifier.height(10.dp))
        vm.stats?.let { s ->
            val (down, up) = if (i.uplink) s.rx to s.tx else s.tx to s.rx
            Row {
                Metric("Downloaded", bytes(down).substringBefore(' '), bytes(down).substringAfter(' ', ""), modifier = Modifier.weight(1f))
                Metric("Uploaded", bytes(up).substringBefore(' '), bytes(up).substringAfter(' ', ""), modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
        }
        Text(
            if (i.uplink) "Internet traffic on ${i.device}. Totals are ${i.device}'s counters since it last came up."
            else "Download is what the router sends to devices on ${i.name}; upload is what they send. Totals are ${i.device}'s counters since it last came up.",
            color = Ops.faint, fontSize = 11.sp,
        )
    }
}

@Composable
private fun DetailsPanel(i: Iface, s: DevStats?) {
    Panel(title = "Details") {
        KV("Status", if (i.up) "Up for ${duration(i.uptime)}" else "Down", valueColor = if (i.up) Ops.ok else Ops.bad)
        KV("Protocol", i.proto)
        KV("Device", i.device ?: "—", mono = true)
        s?.speed?.takeUnless { it.startsWith("-") }?.let { KV("Link speed", it.replace("F", " Mbps full duplex").replace("H", " Mbps half duplex")) }
        s?.mac?.let { KV("MAC", it.uppercase(), mono = true) }
        i.ipv4.forEach { KV("IPv4", it, mono = true) }
        i.ipv6.take(3).forEach { KV("IPv6", it, mono = true) }
        i.gateway?.let { KV("Gateway", it, mono = true) }
        if (i.dns.isNotEmpty()) KV("DNS", i.dns.joinToString(", "), mono = true)
        i.error?.let { KV("Error", it, valueColor = Ops.bad) }
    }
}

/** One device: how much it moves right now (with its share of the busiest), and its totals. */
@Composable
private fun ClientUsageRow(d: Device, live: LiveRate?, total: com.openwrtmgr.app.data.HostUsage?, busiest: Float?, onClick: () -> Unit) {
    Panel(onClick = onClick, padding = PaddingValues(start = 12.dp, end = 14.dp, top = 10.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(d.kind.icon(), d.mac, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name, color = Ops.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(d.ipv4, if (d.wireless) d.ssid ?: "Wi-Fi" else "Wired").joinToString(" · "), color = Ops.muted, fontSize = 12.sp, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                when {
                    live == null -> MonoText("measuring…", color = Ops.faint, size = 12)
                    live.up == null -> MonoText("⇅ ${rateText(live.down)}", color = Ops.accent, size = 12)
                    else -> {
                        MonoText("↓ ${rateText(live.down)}", color = Ops.accent, size = 12)
                        MonoText("↑ ${rateText(live.up)}", color = Ops.violet, size = 12)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        UsageBar(if (busiest != null && live != null) live.total / busiest * 100f else 0f, color = Ops.accent)
        val totals = when {
            total != null -> "Used ↓ ${bytes(total.rx)} · ↑ ${bytes(total.tx)} this period"
            d.station?.txBytes != null -> "Used ↓ ${bytes(d.station.txBytes)} · ↑ ${bytes(d.station.rxBytes)} since it connected"
            else -> null
        }
        if (totals != null) { Spacer(Modifier.height(6.dp)); Text(totals, color = Ops.faint, fontSize = 12.sp) }
    }
}
