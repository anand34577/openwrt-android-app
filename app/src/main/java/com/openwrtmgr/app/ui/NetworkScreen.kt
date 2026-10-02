package com.openwrtmgr.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openwrtmgr.app.data.DhcpPool
import com.openwrtmgr.app.data.DnsRecord
import com.openwrtmgr.app.data.Forwarding
import com.openwrtmgr.app.data.Iface
import com.openwrtmgr.app.data.IfaceConfig
import com.openwrtmgr.app.data.Redirect
import com.openwrtmgr.app.data.Router
import com.openwrtmgr.app.data.Rule
import com.openwrtmgr.app.data.StaticLease
import com.openwrtmgr.app.data.StaticRoute
import com.openwrtmgr.app.data.Zone

/** Well-known resolvers; AdGuard and Cloudflare Family also filter ads / adult content network-wide. */
private val DNS_PRESETS = listOf(
    "ISP default" to "", "Cloudflare" to "1.1.1.1 1.0.0.1", "Google" to "8.8.8.8 8.8.4.4", "Quad9 (malware block)" to "9.9.9.9 149.112.112.112",
    "AdGuard (ad block)" to "94.140.14.14 94.140.15.15", "Cloudflare Family" to "1.1.1.3 1.0.0.3", "OpenDNS" to "208.67.222.222 208.67.220.220",
)

private val IPV4 = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")
private fun ipOk(s: String) = s.isBlank() || s.matches(IPV4)

@Composable
fun NetworkScreen() {
    val data = rememberData("network") { network() }
    val nav = LocalNav()
    val r = LocalRouter.current
    val ui = LocalUi.current
    var edit by remember { mutableStateOf<Pair<IfaceConfig, Iface?>?>(null) }
    var addIface by remember { mutableStateOf(false) }

    TabScreen("Network", data, subtitle = "Interfaces, firewall, DHCP & routing") { n ->
        item {
            GroupCard(listOf(
                { ListRow("Firewall", "${n.firewall.zones.size} zones · ${n.firewall.redirects.size} port forwards · ${n.firewall.rules.size} rules", Icons.Rounded.Security, Tints.red, onClick = { nav("firewall", "") }) },
                { ListRow("DHCP & DNS", "${n.dhcp.pools.count { !it.ignore }} pools · ${n.dhcp.hosts.size} reservations · ${n.dhcp.records.size} DNS records", Icons.Rounded.Dns, Tints.blue, onClick = { nav("dhcp", "") }) },
                { ListRow("Routing", "${n.live.sumOf { it.routes.size }} active routes · ${n.routes.size} static", Icons.Rounded.AltRoute, Tints.violet, onClick = { nav("routes", "") }) },
            ))
        }
        item { GroupCard(listOf { ListRow("Custom firewall rules", "Your own nftables / iptables rules (/etc/firewall.user)", Icons.Rounded.Code, Tints.orange, onClick = { nav("fwuser", "") }) }) }
        section("Interfaces") { GhostButton("Add", Icons.Rounded.Add) { addIface = true } }
        items(n.config.filter { it.section != "loopback" }, key = { it.section }) { c ->
            val live = n.live.firstOrNull { it.name == c.section }
            val zone = n.firewall.zones.firstOrNull { c.section in it.networks }?.name
            Panel(onClick = { edit = c to live }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(when { live?.up == true -> Ops.ok; c.disabled -> Ops.faint; else -> Ops.bad }, pulse = live?.up == true)
                    Spacer(Modifier.width(4.dp))
                    Text(c.section, color = Ops.text, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    zone?.let { Tag(it, if (it == "wan") Ops.warn else Ops.accent) }
                    Spacer(Modifier.width(6.dp))
                    Tag(c.proto, Ops.muted)
                }
                Spacer(Modifier.padding(top = 6.dp))
                MonoText(listOfNotNull(live?.ipv4?.firstOrNull() ?: c.ipaddr.ifBlank { null }, live?.device ?: c.device.ifBlank { null }).joinToString("  ·  ").ifBlank { "no address" }, color = Ops.muted, size = 13)
                live?.error?.let { Text("Error: $it", color = Ops.bad, fontSize = 12.sp) }
            }
        }
    }

    if (addIface) AddIfaceSheet(r, onDismiss = { addIface = false }) { data.refresh() }
    edit?.let { (c, live) ->
        IfaceSheet(r, c, live, onDismiss = { edit = null }, onAction = { action ->
            ui.run("${action.replaceFirstChar { it.uppercase() }} ${c.section}…", "Done", data::refresh) { r.ifaceAction(c.section, action) }
        }) { data.refresh() }
    }
}

@Composable
private fun IfaceSheet(r: Router, c: IfaceConfig, live: Iface?, onDismiss: () -> Unit, onAction: (String) -> Unit, onDone: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    val ui = LocalUi.current
    var proto by remember { mutableStateOf(c.proto) }
    var ip by remember { mutableStateOf(c.ipaddr) }
    var mask by remember { mutableStateOf(c.netmask.ifBlank { "255.255.255.0" }) }
    var gw by remember { mutableStateOf(c.gateway) }
    var dns by remember { mutableStateOf(c.dns.joinToString(" ")) }
    var user by remember { mutableStateOf(c.username) }
    var pass by remember { mutableStateOf(c.password) }
    var peerdns by remember { mutableStateOf(c.peerdns) }
    var confirmDown by remember { mutableStateOf(false) }
    val supported = proto in listOf("static", "dhcp", "pppoe", "dhcpv6", "none")
    val valid = proto != "static" || (ip.matches(IPV4) && mask.matches(IPV4) && ipOk(gw))

    EditSheet(c.section, onDismiss, saveEnabled = supported && valid, onDelete = { confirmDelete = true }, onSave = {
        val values: Map<String, Any?> = when (proto) {
            "static" -> mapOf("proto" to proto, "ipaddr" to ip, "netmask" to mask, "gateway" to gw.ifBlank { null })
            "pppoe" -> mapOf("proto" to proto, "username" to user, "password" to pass)
            else -> mapOf("proto" to proto)
        } + mapOf(
            "dns" to dns.split(Regex("[\\s,]+")).filter { it.isNotBlank() },
            "peerdns" to if (proto != "static" && !peerdns) "0" else null,
        )
        ui.run("Applying ${c.section}…", "${c.section} updated", onDone) { r.saveIface(c.section, values); onDismiss() }
    }) {
        live?.let {
            Panel(padding = PaddingValues(12.dp)) {
                KV("Status", if (it.up) "up for ${duration(it.uptime)}" else "down", valueColor = if (it.up) Ops.ok else Ops.bad)
                KV("Device", it.device ?: "—")
                it.ipv4.forEach { a -> KV("IPv4", a) }
                it.ipv6.take(2).forEach { a -> KV("IPv6", a) }
                it.gateway?.let { g -> KV("Gateway", g) }
                if (it.dns.isNotEmpty()) KV("DNS", it.dns.joinToString(", "))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Restart") { onAction("up") }
                if (it.up) GhostButton("Stop", color = Ops.bad) { confirmDown = true }
                if (c.proto == "dhcp") GhostButton("Renew", color = Ops.muted) { onAction("renew") }
            }
        }
        Caps("Protocol")
        ChipPicker(listOf("static", "dhcp", "pppoe", "dhcpv6", "none").let { if (c.proto in it) it else it + c.proto }.map { it to it }, { it == proto }, { proto = it })
        if (!supported) Text("Protocol '$proto' is edited in LuCI; the app shows it read-only.", color = Ops.warn, fontSize = 13.sp)
        if (proto == "static") {
            Field(ip, { ip = it }, "IPv4 address", mono = true, keyboard = KeyboardType.Decimal, isError = !ip.matches(IPV4))
            Field(mask, { mask = it }, "Netmask", mono = true, keyboard = KeyboardType.Decimal, isError = !mask.matches(IPV4))
            Field(gw, { gw = it }, "Gateway (optional)", mono = true, keyboard = KeyboardType.Decimal, isError = !ipOk(gw))
        }
        if (proto == "pppoe") {
            Field(user, { user = it }, "PPPoE username")
            Field(pass, { pass = it }, "PPPoE password", password = true)
        }
        if (proto != "static" && proto != "none") SwitchRow("Use DNS from provider", peerdns) { peerdns = it }
        Field(dns, { dns = it }, "Custom DNS servers", mono = true, placeholder = "1.1.1.1 9.9.9.9", supporting = "Space-separated. Empty = none")
        RollbackNote()
    }
    if (confirmDelete) Confirm("Delete ${c.section}?", "Removes this interface from the router's network config. If this phone reaches the router through it, the change is rolled back automatically.", "Delete", onDismiss = { confirmDelete = false }) {
        ui.run("Deleting ${c.section}…", "${c.section} deleted", onDone) { r.deleteUci("network", c.section); onDismiss() }
    }
    if (confirmDown) Confirm("Stop ${c.section}?", "If this phone reaches the router through ${c.section}, you'll lose the connection until it's started again (e.g. from LuCI on another network, or a reboot).", "Stop", onDismiss = { confirmDown = false }) { onAction("down") }
}

@Composable
private fun AddIfaceSheet(r: Router, onDismiss: () -> Unit, onDone: () -> Unit) {
    val ui = LocalUi.current
    var name by remember { mutableStateOf("") }
    var proto by remember { mutableStateOf("static") }
    var device by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf("") }
    val valid = name.matches(Regex("^[a-zA-Z0-9_]{1,15}$")) && (proto != "static" || ip.matches(IPV4))
    EditSheet("Add interface", onDismiss, saveEnabled = valid, saveLabel = "Create", onSave = {
        val values: Map<String, Any?> = mapOf("proto" to proto, "device" to device.ifBlank { null }) +
            if (proto == "static") mapOf("ipaddr" to ip, "netmask" to "255.255.255.0") else emptyMap()
        ui.run("Creating $name…", "$name created", onDone) { r.saveUci("network", name, "interface", values); onDismiss() }
    }) {
        Field(name, { name = it.trim() }, "Name", mono = true, placeholder = "guest", supporting = "Letters, digits and _ (max 15)")
        Caps("Protocol")
        ChipPicker(listOf("static", "dhcp", "pppoe", "none").map { it to it }, { it == proto }, { proto = it })
        Field(device, { device = it.trim() }, "Device (optional)", mono = true, placeholder = "br-lan.40", supporting = "Bridge, VLAN or port the interface runs on")
        if (proto == "static") Field(ip, { ip = it }, "IPv4 address", mono = true, keyboard = KeyboardType.Decimal, isError = ip.isNotEmpty() && !ip.matches(IPV4))
        Text("New interfaces have no firewall zone yet: add one under Firewall → Zones.", color = Ops.muted, fontSize = 12.sp)
        RollbackNote()
    }
}

// ======================================================================== Firewall

@Composable
fun FirewallScreen(onBack: () -> Unit) {
    val data = rememberData("firewall") { network() }
    val r = LocalRouter.current
    val ui = LocalUi.current
    var fwd by remember { mutableStateOf<Redirect?>(null) }
    var newFwd by remember { mutableStateOf(false) }
    var rule by remember { mutableStateOf<Rule?>(null) }
    var newRule by remember { mutableStateOf(false) }
    var zone by remember { mutableStateOf<Zone?>(null) }

    DetailScreen("Firewall", data, onBack, subtitle = "fw4 / nftables") { n ->
        val fw = n.firewall
        section("Zones")
        items(fw.zones, key = { "z" + it.section }) { z ->
            val fwdTo = fw.forwardings.filter { it.src == z.name && it.enabled }.map { it.dest }
            Panel(onClick = { zone = z }, padding = PaddingValues(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(z.name, color = Ops.text, fontFamily = Mono, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (z.masq) Tag("NAT", Ops.warn)
                }
                Spacer(Modifier.padding(top = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PolicyTag("in", z.input); PolicyTag("out", z.output); PolicyTag("fwd", z.forward)
                }
                Spacer(Modifier.padding(top = 6.dp))
                MonoText("networks: ${z.networks.joinToString(" ").ifBlank { "—" }}", color = Ops.muted, size = 12)
                if (fwdTo.isNotEmpty()) MonoText("→ forwards to ${fwdTo.joinToString(", ")}", color = Ops.accent, size = 12)
            }
        }
        section("Port forwards") { GhostButton("Add", Icons.Rounded.Add) { newFwd = true } }
        if (fw.redirects.isEmpty()) item { EmptyNote("No port forwards.") }
        items(fw.redirects, key = { "r" + it.section }) { f ->
            Panel(onClick = { fwd = f }, padding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                ListRow(f.name, "${f.src}:${f.srcDport} → ${f.destIp}:${f.destPort.ifBlank { f.srcDport }} · ${f.proto}", Icons.Rounded.Public, if (f.enabled) Ops.accent else Ops.faint, subtitleMono = true) {
                    if (!f.enabled) Tag("off", Ops.faint)
                }
            }
        }
        section("Traffic rules") { GhostButton("Add", Icons.Rounded.Add) { newRule = true } }
        items(fw.rules, key = { "t" + it.section }) { t ->
            Panel(onClick = { rule = t }, padding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                ListRow(
                    t.name,
                    "${t.src.ifBlank { "device" }} → ${t.dest ?: "device"} · ${t.proto}${t.destPort.takeIf { it.isNotBlank() }?.let { " :$it" }.orEmpty()}",
                    subtitleMono = true,
                ) {
                    PolicyTag(null, t.target)
                    Spacer(Modifier.width(4.dp))
                    OpsSwitch(t.enabled, { on -> ui.run(if (on) "Enabling rule…" else "Disabling rule…", if (on) "Rule enabled" else "Rule disabled", data::refresh) { r.setRuleEnabled(t.section, on) } })
                }
            }
        }
    }

    val zones = ((data.state.value as? Load.Ok)?.data?.firewall?.zones?.map { it.name } ?: listOf("lan", "wan"))
    if (fwd != null || newFwd) ForwardSheet(r, fwd, zones, { fwd = null; newFwd = false }) { data.refresh() }
    if (rule != null || newRule) RuleSheet(r, rule, zones, { rule = null; newRule = false }) { data.refresh() }
    zone?.let { z ->
        val fwds = (data.state.value as? Load.Ok)?.data?.firewall?.forwardings.orEmpty()
        ZoneSheet(r, z, zones, fwds, { zone = null }) { data.refresh() }
    }
}

@Composable
private fun PolicyTag(label: String?, policy: String) {
    val c = when (policy) { "ACCEPT" -> Ops.ok; "REJECT" -> Ops.warn; "DROP" -> Ops.bad; else -> Ops.muted }
    Tag(listOfNotNull(label, policy.lowercase()).joinToString(" "), c)
}

private val policies = listOf("ACCEPT" to "accept", "REJECT" to "reject", "DROP" to "drop")

@Composable
private fun ZoneSheet(r: Router, z: Zone, zones: List<String>, forwardings: List<Forwarding>, onDismiss: () -> Unit, onDone: () -> Unit) {
    val ui = LocalUi.current
    var input by remember { mutableStateOf(z.input) }
    var output by remember { mutableStateOf(z.output) }
    var forward by remember { mutableStateOf(z.forward) }
    var masq by remember { mutableStateOf(z.masq) }
    var mss by remember { mutableStateOf(z.mtuFix) }
    val current = forwardings.filter { it.src == z.name }
    var dests by remember { mutableStateOf(current.filter { it.enabled }.map { it.dest }.toSet()) }
    EditSheet("Zone ${z.name}", onDismiss, onSave = {
        ui.run("Applying firewall…", "Zone updated", onDone) {
            r.uciSet("firewall", z.section, mapOf("input" to input, "output" to output, "forward" to forward, "masq" to if (masq) "1" else null, "mtu_fix" to if (mss) "1" else null))
            current.filter { it.dest !in dests }.forEach { r.uciDelete("firewall", it.section) }
            (dests - current.map { it.dest }.toSet()).forEach { r.uciAdd("firewall", "forwarding", mapOf("src" to z.name, "dest" to it)) }
            r.apply(); onDismiss()
        }
    }) {
        Caps("Input (to router)"); Segmented(policies, input, { input = it })
        Caps("Output (from router)"); Segmented(policies, output, { output = it })
        Caps("Forward (within zone)"); Segmented(policies, forward, { forward = it })
        SwitchRow("Masquerading (NAT)", masq, "Usually only on wan") { masq = it }
        SwitchRow("MSS clamping", mss) { mss = it }
        Caps("Allow forwarding to")
        ChipPicker(zones.filter { it != z.name }.map { it to it }, { it in dests }, { d -> dests = if (d in dests) dests - d else dests + d })
        RollbackNote()
    }
}

@Composable
fun ForwardSheet(
    r: Router, f: Redirect?, zones: List<String>, onDismiss: () -> Unit,
    initialIp: String = "", initialName: String = "", onDone: () -> Unit,
) {
    val ui = LocalUi.current
    var name by remember { mutableStateOf(f?.name ?: initialName) }
    var proto by remember { mutableStateOf(f?.proto ?: "tcp udp") }
    var ext by remember { mutableStateOf(f?.srcDport.orEmpty()) }
    var ip by remember { mutableStateOf(f?.destIp ?: initialIp) }
    var port by remember { mutableStateOf(f?.destPort.orEmpty()) }
    var src by remember { mutableStateOf(f?.src ?: "wan") }
    var dest by remember { mutableStateOf(f?.dest ?: "lan") }
    var enabled by remember { mutableStateOf(f?.enabled ?: true) }
    val portRe = Regex("^\\d{1,5}(-\\d{1,5})?$")
    val ok = name.isNotBlank() && ext.matches(portRe) && ip.matches(IPV4) && (port.isBlank() || port.matches(portRe))
    EditSheet(if (f == null) "New port forward" else f.name, onDismiss, saveEnabled = ok,
        onDelete = f?.let { { ui.run("Deleting…", "Port forward deleted", onDone) { r.deleteUci("firewall", it.section); onDismiss() } } },
        onSave = {
            ui.run("Applying firewall…", "Port forward saved", onDone) {
                r.saveUci("firewall", f?.section, "redirect", mapOf(
                    "name" to name, "target" to "DNAT", "src" to src, "dest" to dest, "proto" to proto.split(' '),
                    "src_dport" to ext, "dest_ip" to ip, "dest_port" to port.ifBlank { null }, "enabled" to if (enabled) null else "0",
                ))
                onDismiss()
            }
        }) {
        SwitchRow("Enabled", enabled) { enabled = it }
        Field(name, { name = it }, "Name", placeholder = "Minecraft server")
        Caps("Protocol"); Segmented(listOf("tcp udp" to "TCP+UDP", "tcp" to "TCP", "udp" to "UDP"), proto, { proto = it })
        Field(ext, { ext = it }, "External port", mono = true, keyboard = KeyboardType.Number, placeholder = "25565 or 8000-8100")
        Field(ip, { ip = it }, "Internal IP", mono = true, keyboard = KeyboardType.Decimal, isError = ip.isNotBlank() && !ip.matches(IPV4))
        Field(port, { port = it }, "Internal port", mono = true, keyboard = KeyboardType.Number, supporting = "Empty = same as external")
        Caps("From zone"); ChipPicker(zones.map { it to it }, { it == src }, { src = it })
        Caps("To zone"); ChipPicker(zones.map { it to it }, { it == dest }, { dest = it })
    }
}

@Composable
private fun RuleSheet(r: Router, t: Rule?, zones: List<String>, onDismiss: () -> Unit, onDone: () -> Unit) {
    val ui = LocalUi.current
    var name by remember { mutableStateOf(t?.name.orEmpty()) }
    var src by remember { mutableStateOf(t?.src ?: "wan") }
    var dest by remember { mutableStateOf(t?.dest ?: "") }
    var proto by remember { mutableStateOf(t?.proto ?: "tcp udp") }
    var port by remember { mutableStateOf(t?.destPort.orEmpty()) }
    var target by remember { mutableStateOf(t?.target ?: "ACCEPT") }
    var enabled by remember { mutableStateOf(t?.enabled ?: true) }
    val zoneOpts = listOf("" to "router", "*" to "any") + zones.map { it to it }
    EditSheet(if (t == null) "New traffic rule" else t.name, onDismiss, saveEnabled = name.isNotBlank(),
        onDelete = t?.let { { ui.run("Deleting…", "Rule deleted", onDone) { r.deleteUci("firewall", it.section); onDismiss() } } },
        onSave = {
            ui.run("Applying firewall…", "Rule saved", onDone) {
                r.saveUci("firewall", t?.section, "rule", mapOf(
                    "name" to name, "src" to src.ifBlank { null }, "dest" to dest.ifBlank { null }, "proto" to proto.split(' '),
                    "dest_port" to port.split(Regex("[\\s,]+")).filter { it.isNotBlank() }, "target" to target, "enabled" to if (enabled) null else "0",
                ))
                onDismiss()
            }
        }) {
        SwitchRow("Enabled", enabled) { enabled = it }
        Field(name, { name = it }, "Name")
        Caps("Source zone"); ChipPicker(zoneOpts, { it == src }, { src = it })
        Caps("Destination"); ChipPicker(zoneOpts, { it == dest }, { dest = it })
        Caps("Protocol"); Segmented(listOf("tcp udp" to "TCP+UDP", "tcp" to "TCP", "udp" to "UDP", "icmp" to "ICMP", "all" to "Any"), proto, { proto = it })
        Field(port, { port = it }, "Destination port(s)", mono = true, placeholder = "22 443 1000-2000")
        Caps("Action"); Segmented(policies, target, { target = it })
        RollbackNote()
    }
}

// ======================================================================== DHCP & DNS

@Composable
fun DhcpDnsScreen(onBack: () -> Unit) {
    val data = rememberData("dhcp") { network() }
    val r = LocalRouter.current
    val ui = LocalUi.current
    var pool by remember { mutableStateOf<DhcpPool?>(null) }
    var host by remember { mutableStateOf<StaticLease?>(null) }
    var newHost by remember { mutableStateOf(false) }
    var rec by remember { mutableStateOf<DnsRecord?>(null) }
    var newRec by remember { mutableStateOf(false) }
    var dnsEdit by remember { mutableStateOf(false) }

    DetailScreen("DHCP & DNS", data, onBack, subtitle = "dnsmasq / odhcpd") { n ->
        val d = n.dhcp
        section("Address pools")
        items(d.pools, key = { "p" + it.section }) { p ->
            Panel(onClick = { pool = p }, padding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                ListRow(p.iface, if (p.ignore) "DHCP disabled" else "hosts .${p.start} – .${p.start + p.limit - 1} · lease ${p.leasetime}", Icons.Rounded.Lan, if (p.ignore) Ops.faint else Ops.accent, subtitleMono = true)
            }
        }
        d.dns?.let { s ->
            section("DNS forwarding")
            item {
                Panel(onClick = { dnsEdit = true }) {
                    KV("Upstream servers", s.servers.joinToString(", ").ifBlank { "from WAN (ISP)" })
                    KV("Ignore ISP DNS", if (s.noResolv) "yes" else "no")
                    KV("Local domain", s.domain.ifBlank { "—" })
                    KV("Rebind protection", if (s.rebindProtection) "on" else "off")
                }
            }
        }
        section("Static leases") { GhostButton("Add", Icons.Rounded.Add) { newHost = true } }
        if (d.hosts.isEmpty()) item { EmptyNote("No reserved addresses. Reserve one from a device's page.") }
        items(d.hosts, key = { "h" + it.section }) { h ->
            Panel(onClick = { host = h }, padding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                ListRow(h.name.ifBlank { h.mac }, "${h.ip.ifBlank { "dynamic" }} · ${h.mac}", subtitleMono = true)
            }
        }
        section("DNS records") { GhostButton("Add", Icons.Rounded.Add) { newRec = true } }
        if (d.records.isEmpty()) item { EmptyNote("No custom hostnames.") }
        items(d.records, key = { "d" + it.section }) { x ->
            Panel(onClick = { rec = x }, padding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                ListRow(x.name, x.ip, Icons.Rounded.Language, subtitleMono = true)
            }
        }
    }

    pool?.let { p -> PoolSheet(r, p, { pool = null }) { data.refresh() } }
    if (host != null || newHost) {
        val h = host
        var name by remember(h) { mutableStateOf(h?.name.orEmpty()) }
        var mac by remember(h) { mutableStateOf(h?.mac.orEmpty()) }
        var ip by remember(h) { mutableStateOf(h?.ip.orEmpty()) }
        val macOk = mac.matches(Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$"))
        EditSheet(if (h == null) "New static lease" else h.name.ifBlank { h.mac }, { host = null; newHost = false }, saveEnabled = macOk && ipOk(ip),
            onDelete = h?.let { { ui.run("Deleting…", "Removed", data::refresh) { r.deleteReservation(it.section); host = null } } },
            onSave = { ui.run("Saving…", "Static lease saved", data::refresh) { r.saveReservation(h?.section, mac.uppercase(), name.trim(), ip.trim()); host = null; newHost = false } }) {
            Field(name, { name = it }, "Hostname")
            Field(mac, { mac = it }, "MAC address", mono = true, isError = mac.isNotBlank() && !macOk, placeholder = "AA:BB:CC:DD:EE:FF")
            Field(ip, { ip = it }, "IPv4 address", mono = true, keyboard = KeyboardType.Decimal, isError = !ipOk(ip))
        }
    }
    if (rec != null || newRec) {
        val x = rec
        var name by remember(x) { mutableStateOf(x?.name.orEmpty()) }
        var ip by remember(x) { mutableStateOf(x?.ip.orEmpty()) }
        EditSheet(if (x == null) "New DNS record" else x.name, { rec = null; newRec = false }, saveEnabled = name.isNotBlank() && ip.isNotBlank(),
            onDelete = x?.let { { ui.run("Deleting…", "Removed", data::refresh) { r.deleteUci("dhcp", it.section); rec = null } } },
            onSave = { ui.run("Saving…", "DNS record saved", data::refresh) { r.saveUci("dhcp", x?.section, "domain", mapOf("name" to name.trim(), "ip" to ip.trim())); rec = null; newRec = false } }) {
            Field(name, { name = it }, "Hostname", mono = true, placeholder = "nas.lan")
            Field(ip, { ip = it }, "IP address", mono = true, placeholder = "192.168.1.10")
        }
    }
    if (dnsEdit) (data.state.value as? Load.Ok)?.data?.dhcp?.dns?.let { s ->
        var servers by remember { mutableStateOf(s.servers.joinToString(" ")) }
        var noResolv by remember { mutableStateOf(s.noResolv) }
        var domain by remember { mutableStateOf(s.domain) }
        var rebind by remember { mutableStateOf(s.rebindProtection) }
        EditSheet("DNS forwarding", { dnsEdit = false }, onSave = {
            ui.run("Applying DNS…", "DNS settings saved", data::refresh) {
                r.saveUci("dhcp", s.section, "dnsmasq", mapOf("server" to servers.split(Regex("[\\s,]+")).filter { it.isNotBlank() },
                    "noresolv" to if (noResolv) "1" else null, "domain" to domain.trim(), "rebind_protection" to if (rebind) "1" else "0"))
                dnsEdit = false
            }
        }) {
            Caps("Presets")
            ChipPicker(DNS_PRESETS.map { it.second to it.first }, { it == servers.trim() }, { v -> servers = v; noResolv = v.isNotBlank() })
            Field(servers, { servers = it }, "Upstream DNS servers", mono = true, placeholder = "1.1.1.1 8.8.8.8", supporting = "Space-separated. Empty = use the ISP's")
            SwitchRow("Ignore ISP DNS", noResolv, "Only use the servers above") { noResolv = it }
            Field(domain, { domain = it }, "Local domain", mono = true, placeholder = "lan")
            SwitchRow("Rebind protection", rebind, "Blocks upstream answers pointing into your LAN") { rebind = it }
        }
    }
}

@Composable
private fun PoolSheet(r: Router, p: DhcpPool, onDismiss: () -> Unit, onDone: () -> Unit) {
    val ui = LocalUi.current
    var start by remember { mutableStateOf(p.start.toString()) }
    var limit by remember { mutableStateOf(p.limit.toString()) }
    var lease by remember { mutableStateOf(p.leasetime) }
    var enabled by remember { mutableStateOf(!p.ignore) }
    val ok = start.toIntOrNull() in 1..254 && limit.toIntOrNull() in 1..65534 && lease.matches(Regex("^(\\d+[smhdw]?|infinite)$"))
    EditSheet("DHCP · ${p.iface}", onDismiss, saveEnabled = ok, onSave = {
        ui.run("Applying DHCP…", "Pool updated", onDone) {
            r.saveUci("dhcp", p.section, "dhcp", mapOf("start" to start, "limit" to limit, "leasetime" to lease, "ignore" to if (enabled) null else "1")); onDismiss()
        }
    }) {
        SwitchRow("Serve DHCP on ${p.iface}", enabled) { enabled = it }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(start, { start = it.filter(Char::isDigit) }, "Start", Modifier.weight(1f), mono = true, keyboard = KeyboardType.Number)
            Field(limit, { limit = it.filter(Char::isDigit) }, "Count", Modifier.weight(1f), mono = true, keyboard = KeyboardType.Number)
        }
        Field(lease, { lease = it }, "Lease time", mono = true, supporting = "e.g. 12h, 1d, 30m, infinite")
        RollbackNote()
    }
}

// ======================================================================== Routing

@Composable
fun RoutesScreen(onBack: () -> Unit) {
    val data = rememberData("routes") { network() }
    val r = LocalRouter.current
    val ui = LocalUi.current
    var edit by remember { mutableStateOf<StaticRoute?>(null) }
    var add by remember { mutableStateOf(false) }
    DetailScreen("Routing", data, onBack, subtitle = "IPv4 routes") { n ->
        section("Active routes")
        item {
            Panel {
                n.live.flatMap { it.routes }.forEach { rt ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        MonoText(if (rt.mask == 0) "default" else "${rt.target}/${rt.mask}", Modifier.weight(0.45f), size = 12)
                        MonoText("via ${rt.nexthop.takeIf { it != "0.0.0.0" } ?: "link"}", Modifier.weight(0.35f), color = Ops.muted, size = 12)
                        MonoText(rt.iface, color = Ops.accent, size = 12)
                    }
                }
                if (n.live.all { it.routes.isEmpty() }) EmptyNote("Only connected-network routes.")
            }
        }
        item { NeighborPanel() }
        item { KernelRoutesPanel() }
        section("Static routes") { GhostButton("Add", Icons.Rounded.Add) { add = true } }
        items(n.routes, key = { it.section }) { s ->
            Panel(onClick = { edit = s }, padding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                ListRow("${s.target}${s.netmask.takeIf { it.isNotBlank() }?.let { " / $it" }.orEmpty()}", "via ${s.gateway.ifBlank { "link" }} · ${s.iface}", Icons.Rounded.AltRoute, subtitleMono = true)
            }
        }
        if (n.routes.isEmpty()) item { EmptyNote("No static routes.") }
    }
    if (edit != null || add) {
        val s = edit
        val ifaces = (data.state.value as? Load.Ok)?.data?.config?.map { it.section }.orEmpty()
        var iface by remember(s) { mutableStateOf(s?.iface ?: "lan") }
        var target by remember(s) { mutableStateOf(s?.target.orEmpty()) }
        var mask by remember(s) { mutableStateOf(s?.netmask ?: "255.255.255.0") }
        var gw by remember(s) { mutableStateOf(s?.gateway.orEmpty()) }
        var metric by remember(s) { mutableStateOf(s?.metric.orEmpty()) }
        EditSheet(if (s == null) "New static route" else s.target, { edit = null; add = false },
            saveEnabled = target.matches(IPV4) && mask.matches(IPV4) && ipOk(gw),
            onDelete = s?.let { { ui.run("Deleting…", "Route removed", data::refresh) { r.deleteUci("network", it.section); edit = null } } },
            onSave = { ui.run("Applying route…", "Route saved", data::refresh) {
                r.saveUci("network", s?.section, "route", mapOf("interface" to iface, "target" to target, "netmask" to mask, "gateway" to gw.ifBlank { null }, "metric" to metric.ifBlank { null }))
                edit = null; add = false
            } }) {
            Caps("Interface"); ChipPicker(ifaces.map { it to it }, { it == iface }, { iface = it })
            Field(target, { target = it }, "Target network", mono = true, keyboard = KeyboardType.Decimal)
            Field(mask, { mask = it }, "Netmask", mono = true, keyboard = KeyboardType.Decimal)
            Field(gw, { gw = it }, "Gateway", mono = true, keyboard = KeyboardType.Decimal)
            Field(metric, { metric = it.filter(Char::isDigit) }, "Metric", mono = true, keyboard = KeyboardType.Number)
            RollbackNote()
        }
    }
}

/** ARP / neighbor table: every IPv4 host the router has talked to, with live state. */
@Composable
private fun NeighborPanel() {
    val data = rememberData("neigh") { neighbors() }
    val list = (data.state.collectAsStateWithLifecycle().value as? Load.Ok)?.data.orEmpty()
    Panel(title = "Neighbors (ARP) · ${list.size}") {
        list.sortedWith(compareBy({ !it.active }, { it.dev }, { it.ip })).forEach { n ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(if (n.active) Ops.ok else if (n.idle) Ops.warn else Ops.faint)
                MonoText(n.ip, Modifier.weight(0.36f), size = 12)
                MonoText(n.mac, Modifier.weight(0.44f), color = Ops.muted, size = 11)
                MonoText(n.dev, color = Ops.faint, size = 11)
            }
        }
        if (list.isEmpty()) EmptyNote("Not available on this router.")
    }
}

@Composable
private fun KernelRoutesPanel() {
    val data = rememberData("rtable") { routeTable() }
    val text = (data.state.collectAsStateWithLifecycle().value as? Load.Ok)?.data.orEmpty()
    if (text.isBlank()) return
    Panel(title = "Kernel routing table") {
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
            MonoText(text.trim(), color = Ops.muted, size = 11, maxLines = 400)
        }
    }
}