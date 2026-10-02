package com.openwrtmgr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openwrtmgr.app.data.Board
import com.openwrtmgr.app.data.Iface
import com.openwrtmgr.app.data.Router
import com.openwrtmgr.app.data.SysInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.Locale

/** Live state for the overview; lives in a ViewModel so history survives tab switches. */
class OverviewVM(private val r: Router) : ViewModel() {
    var board by mutableStateOf<Board?>(null)
    var info by mutableStateOf<SysInfo?>(null)
    var ifaces by mutableStateOf<List<Iface>>(emptyList())
    var wifi by mutableStateOf<Router.Wireless?>(null)
    var online by mutableStateOf<Int?>(null)
    var wirelessOnline by mutableStateOf(0)
    var error by mutableStateOf<String?>(null)

    var rx by mutableStateOf(listOf<Float>()); private set
    var tx by mutableStateOf(listOf<Float>()); private set
    var cpu by mutableStateOf(listOf<Float>()); private set
    var mem by mutableStateOf(listOf<Float>()); private set
    var cpuAvailable by mutableStateOf(true)
    var ports by mutableStateOf<List<com.openwrtmgr.app.data.Port>>(emptyList())
    /** Interface whose traffic the chart shows; null = the WAN. */
    var chartIface by mutableStateOf<String?>(null)
        private set

    fun selectChart(name: String?) { chartIface = name; rx = emptyList(); tx = emptyList(); lastBytes = null }

    private var lastBytes: Triple<Long, Long, Long>? = null // rx, tx, timeMs
    private var lastJiffies: Pair<Long, Long>? = null
    private var tick = 0

    val wan get() = ifaces.firstOrNull { it.gateway != null && it.up } ?: ifaces.firstOrNull { it.name == "wan" }

    suspend fun poll() {
        while (true) {
            try {
                if (tick % 15 == 0) { board = r.board(); ifaces = r.interfaces() }
                if (tick % 15 == 0) runCatching { wifi = r.wireless() }
                if (tick % 15 == 0) runCatching { ports = r.ports() }
                if (tick % 8 == 0) runCatching { r.clients().devices.let { d -> online = d.count { it.online }; wirelessOnline = d.count { it.wireless } } }
                info = r.sysInfo().also { mem = (mem + it.memUsedPct).takeLast(HISTORY) }
                sampleCpu()
                sampleTraffic()
                error = null
            } catch (e: CancellationException) { throw e } catch (e: Throwable) { error = e.friendly() }
            tick++
            delay(2000)
        }
    }

    private suspend fun sampleCpu() {
        if (!cpuAvailable) return // /proc/stat not readable under this ACL: load average is shown instead
        val j = r.cpuJiffies()
        if (j == null) { cpuAvailable = false; return }
        lastJiffies?.let { (b0, t0) ->
            val dt = j.second - t0
            if (dt > 0) cpu = (cpu + (j.first - b0) * 100f / dt).takeLast(HISTORY)
        }
        lastJiffies = j
    }

    private suspend fun sampleTraffic() {
        val dev = (chartIface?.let { n -> ifaces.firstOrNull { it.name == n } } ?: wan)?.device ?: return
        val s = r.deviceStats()[dev] ?: return
        val now = System.currentTimeMillis()
        lastBytes?.let { (rx0, tx0, t0) ->
            val dt = (now - t0) / 1000f
            if (dt > 0 && s.rx >= rx0 && s.tx >= tx0) {
                rx = (rx + (s.rx - rx0) / dt).takeLast(HISTORY)
                tx = (tx + (s.tx - tx0) / dt).takeLast(HISTORY)
            }
        }
        lastBytes = Triple(s.rx, s.tx, now)
    }

    companion object { const val HISTORY = 60 }
}

@Composable
fun OverviewScreen(onSwitchRouter: () -> Unit) {
    val r = LocalRouter.current
    val vm: OverviewVM = viewModel(key = "overview:${r.profile.id}") { OverviewVM(r) }
    val ui = LocalUi.current
    var confirmReboot by remember { mutableStateOf(false) }
    LaunchedEffect(vm) { vm.poll() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = NavBarSpace),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Header(
                vm.board?.hostname ?: r.profile.name,
                vm.board?.model ?: r.profile.baseUrl, // firmware version lives in the System panel below
                actions = {
                    IconButton(onClick = { confirmReboot = true }) { Icon(Icons.Rounded.PowerSettingsNew, "Reboot", tint = Ops.muted) }
                    IconButton(onClick = onSwitchRouter) { Icon(Icons.Rounded.SwapHoriz, "Switch router", tint = Ops.muted) }
                },
            )
        }
        vm.error?.let { item { Panel { Row(verticalAlignment = Alignment.CenterVertically) { Dot(Ops.bad); Text(it, color = Ops.muted, fontSize = 13.sp) } } } }
        if (vm.info == null && vm.error == null) item { LoadingBlock() }
        vm.info?.let { info ->
            item { InternetPanel(vm) }
            item {
                Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Panel(Modifier.weight(1f).fillMaxHeight(), padding = PaddingValues(14.dp)) {
                        if (vm.cpuAvailable) {
                            Metric("CPU", String.format(Locale.US, "%.0f", vm.cpu.lastOrNull() ?: 0f), "%", usageColor(vm.cpu.lastOrNull() ?: 0f))
                            Spacer(Modifier.height(10.dp))
                            Sparkline(vm.cpu, Ops.accent, max = 100f)
                        } else {
                            Metric("Load", String.format(Locale.US, "%.2f", info.load.firstOrNull() ?: 0.0), sub = "1 · 5 · 15 min")
                            Spacer(Modifier.height(10.dp))
                            Text(info.load.joinToString("   ") { String.format(Locale.US, "%.2f", it) }, color = Ops.muted, fontSize = 13.sp)
                        }
                    }
                    Panel(Modifier.weight(1f).fillMaxHeight(), padding = PaddingValues(14.dp)) {
                        Metric("Memory", String.format(Locale.US, "%.0f", info.memUsedPct), "%", usageColor(info.memUsedPct), sub = "${bytes(info.memTotal - info.memAvailable)} of ${bytes(info.memTotal)}")
                        Spacer(Modifier.height(10.dp))
                        Sparkline(vm.mem, Ops.violet, max = 100f)
                    }
                }
            }
            item {
                Panel(title = "System") {
                    KV("Uptime", duration(info.uptime))
                    KV("Load average", info.load.joinToString(" / ") { String.format(Locale.US, "%.2f", it) })
                    Spacer(Modifier.height(6.dp))
                    StorageRow("Flash (overlay)", info.rootUsed, info.rootTotal)
                    StorageRow("RAM disk (/tmp)", info.tmpUsed, info.tmpTotal)
                    vm.board?.let { b ->
                        Spacer(Modifier.height(6.dp)); Divider(); Spacer(Modifier.height(6.dp))
                        KV("Firmware", b.release)
                        KV("Kernel", b.kernel)
                        KV("Target", b.target)
                        KV("CPU", b.cpu)
                    }
                }
            }
            item { WifiSummary(vm) }
            if (vm.ports.isNotEmpty()) item {
                Panel(title = "Ethernet ports") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        vm.ports.forEach { p ->
                            Column(
                                Modifier.weight(1f).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                                    .background(if (p.up) Ops.ok.copy(alpha = 0.14f) else Ops.raised)
                                    .padding(vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                MonoText(p.device.uppercase(), color = if (p.up) Ops.ok else Ops.faint, size = 11, weight = FontWeight.SemiBold)
                                MonoText(if (p.up) p.speed?.replace("F", "").orEmpty().let { if (it.isNotBlank()) "${it}M" else "up" } else "—", color = Ops.muted, size = 10)
                            }
                        }
                    }
                }
            }
            item {
                Panel(title = "Interfaces", action = { Caps("Tap to graph") }) {
                    vm.ifaces.filter { it.name != "loopback" }.forEach { i ->
                        val charted = i.name == (vm.chartIface ?: vm.wan?.name)
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.selectChart(i.name.takeUnless { it == vm.wan?.name }) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Dot(if (i.up) Ops.ok else Ops.faint, pulse = charted)
                            Spacer(Modifier.width(6.dp))
                            MonoText(i.name, Modifier.weight(0.3f), weight = FontWeight.Medium, color = if (charted) Ops.accent else Ops.text)
                            MonoText(i.ipv4.firstOrNull() ?: i.proto, Modifier.weight(0.5f), color = Ops.muted, size = 12)
                            Text(if (i.up) duration(i.uptime) else "down", color = Ops.faint, fontSize = 12.sp, fontFamily = Mono)
                        }
                    }
                }
            }
        }
    }

    if (confirmReboot) Confirm("Reboot router?", "Every device loses connectivity for a minute or two.", "Reboot", onDismiss = { confirmReboot = false }) {
        ui.run("Rebooting…", "Reboot started. The router will be back in 1–2 minutes.") { r.reboot() }
    }
}

@Composable
private fun InternetPanel(vm: OverviewVM) {
    val wan = vm.wan
    val up = wan?.up == true && wan.gateway != null
    val white = Color.White
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(BrandGradient),
    ) {
        Box {
            // soft decorative circles, same motif as the Ferry hero
            Box(Modifier.align(Alignment.TopEnd).offset(x = 60.dp, y = (-70).dp).size(170.dp).clip(CircleShape).background(white.copy(alpha = 0.10f)))
            Box(Modifier.align(Alignment.BottomStart).offset(x = (-40).dp, y = 50.dp).size(120.dp).clip(CircleShape).background(white.copy(alpha = 0.07f)))
            Column(Modifier.padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.clip(CircleShape).background(white.copy(alpha = 0.18f)).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (up) Color(0xFF7CF0B8) else Color(0xFFFF9A9E)))
                        Spacer(Modifier.width(8.dp))
                        Text(if (up) "Online" else "No internet", color = white, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(vm.chartIface?.let { "Graph: $it" } ?: wan?.let { "${it.name} · ${it.proto}" } ?: "no wan", color = white.copy(alpha = 0.8f), fontSize = 13.sp)
                }
                Spacer(Modifier.height(22.dp))
                Row {
                    val (rv, ru) = rate(vm.rx.lastOrNull() ?: 0f)
                    val (tv, tu) = rate(vm.tx.lastOrNull() ?: 0f)
                    Metric("↓ Download", rv, ru, white, modifier = Modifier.weight(1f), labelColor = white.copy(alpha = 0.8f), unitColor = white.copy(alpha = 0.8f))
                    Metric("↑ Upload", tv, tu, white, modifier = Modifier.weight(1f), labelColor = white.copy(alpha = 0.8f), unitColor = white.copy(alpha = 0.8f))
                }
                Spacer(Modifier.height(14.dp))
                TrafficChart(vm.rx, vm.tx, capacity = OverviewVM.HISTORY, rxColor = white, txColor = white.copy(alpha = 0.5f), gridColor = white.copy(alpha = 0.18f))
            }
        }
    }
    if (wan != null) {
        Spacer(Modifier.height(12.dp))
        Panel(title = "Connection") {
            KV("WAN IP", wan.ipv4.firstOrNull()?.substringBefore('/') ?: "—")
            KV("Gateway", wan.gateway ?: "—")
            KV("DNS", wan.dns.take(2).joinToString(", ").ifBlank { "—" })
            if (wan.up) KV("Connected for", duration(wan.uptime))
        }
    }
}

@Composable
private fun WifiSummary(vm: OverviewVM) {
    val nav = LocalNav()
    Panel(title = "Wi-Fi & devices") {
        Row {
            Metric("Online devices", vm.online?.toString() ?: "…", modifier = Modifier.weight(1f), sub = "${vm.wirelessOnline} on Wi-Fi")
            Metric("Networks", vm.wifi?.ssids?.count { !it.disabled }?.toString() ?: "…", modifier = Modifier.weight(1f),
                sub = vm.wifi?.radios?.joinToString(" · ") { it.band } ?: "")
        }
        vm.wifi?.let { w ->
            Spacer(Modifier.height(10.dp))
            w.radios.forEach { radio ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Dot(if (radio.up) Ops.ok else Ops.faint)
                    Spacer(Modifier.width(6.dp))
                    Text(radio.band, color = Ops.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    MonoText(
                        if (radio.up) "ch ${radio.liveChannel ?: radio.channel} · ${radio.htmode}" else if (radio.disabled) "disabled" else "down",
                        color = Ops.muted, size = 12,
                    )
                }
            }
        }
    }
}

@Composable
private fun StorageRow(label: String, used: Long, total: Long) {
    if (total <= 0) return
    val pct = used * 100f / total
    Column(Modifier.padding(vertical = 6.dp)) {
        Row {
            Text(label, color = Ops.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            MonoText("${bytes(used)} / ${bytes(total)}", color = Ops.text, size = 12)
        }
        Spacer(Modifier.height(6.dp))
        UsageBar(pct)
    }
}

/** Shorthand so composables can grab the navigator without the import dance. */
@Composable
fun LocalNav(): (String, String) -> Unit = com.openwrtmgr.app.LocalNav.current
