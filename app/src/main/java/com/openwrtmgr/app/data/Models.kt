package com.openwrtmgr.app.data

import kotlinx.serialization.json.JsonObject

// Domain models + pure parsers from ubus JSON. Parsers are top-level so tests can feed them
// captured router replies directly.

data class Board(
    val hostname: String, val model: String, val release: String, val target: String,
    val kernel: String, val cpu: String,
)

data class SysInfo(
    val uptime: Long, val load: List<Double>,
    val memTotal: Long, val memAvailable: Long,
    val rootTotal: Long, val rootUsed: Long,
    val tmpTotal: Long, val tmpUsed: Long,
) {
    val memUsedPct get() = pct(memTotal - memAvailable, memTotal)
    val rootUsedPct get() = pct(rootUsed, rootTotal)
}

private fun pct(used: Long, total: Long) = if (total > 0) (used * 100f / total).coerceIn(0f, 100f) else 0f

fun parseBoard(j: JsonObject): Board {
    val rel = j["release"].obj()
    return Board(
        hostname = j["hostname"].str() ?: "OpenWrt",
        model = j["model"].str() ?: "Unknown device",
        release = rel["description"].str() ?: listOfNotNull(rel["distribution"].str(), rel["version"].str()).joinToString(" "),
        target = rel["target"].str().orEmpty(),
        kernel = j["kernel"].str().orEmpty(),
        cpu = j["system"].str().orEmpty(),
    )
}

fun parseSysInfo(j: JsonObject): SysInfo {
    val mem = j["memory"].obj()
    val total = mem["total"].long() ?: 0
    // "available" is the kernel's MemAvailable (free + reclaimable cache) — "free" alone makes a
    // healthy router look full. Fall back to free+buffered+cached on very old kernels.
    val avail = mem["available"].long()
        ?: ((mem["free"].long() ?: 0) + (mem["buffered"].long() ?: 0) + (mem["cached"].long() ?: 0))
    val root = j["root"].obj(); val tmp = j["tmp"].obj()
    return SysInfo(
        uptime = j["uptime"].long() ?: 0,
        load = j["load"].arr().map { (it.dbl() ?: 0.0) / 65536.0 },
        memTotal = total, memAvailable = avail,
        rootTotal = (root["total"].long() ?: 0) * 1024, rootUsed = (root["used"].long() ?: 0) * 1024,
        tmpTotal = (tmp["total"].long() ?: 0) * 1024, tmpUsed = (tmp["used"].long() ?: 0) * 1024,
    )
}

/** /proc/stat first line → (busy, total) jiffies. CPU% = Δbusy/Δtotal between two samples. */
fun parseCpuJiffies(procStat: String): Pair<Long, Long>? {
    val f = procStat.lineSequence().firstOrNull { it.startsWith("cpu ") }?.trim()?.split(Regex("\\s+"))?.drop(1)?.mapNotNull { it.toLongOrNull() }
        ?: return null
    if (f.size < 4) return null
    val idle = f[3] + (f.getOrNull(4) ?: 0)
    return (f.sum() - idle) to f.sum()
}

// ---------------- Interfaces ----------------

data class Iface(
    val name: String, val proto: String, val up: Boolean, val device: String?, val uptime: Long,
    val ipv4: List<String>, val ipv6: List<String>, val gateway: String?, val dns: List<String>,
    val routes: List<Route>, val error: String?,
)

data class Route(val target: String, val mask: Int, val nexthop: String, val metric: Int?, val iface: String)

fun parseInterfaces(dump: JsonObject): List<Iface> = dump["interface"].arr().map { e ->
    val o = e.obj()
    val name = o["interface"].str() ?: "?"
    val routes = o["route"].arr().map { r ->
        val ro = r.obj()
        Route(ro["target"].str() ?: "", ro["mask"].int() ?: 0, ro["nexthop"].str() ?: "", ro["metric"].int(), name)
    }
    Iface(
        name = name,
        proto = o["proto"].str() ?: "none",
        up = o["up"].bool() ?: false,
        device = o["l3_device"].str() ?: o["device"].str(),
        uptime = o["uptime"].long() ?: 0,
        ipv4 = o["ipv4-address"].arr().mapNotNull { a -> a.obj()["address"].str()?.let { "$it/${a.obj()["mask"].int()}" } },
        ipv6 = (o["ipv6-address"].arr() + o["ipv6-prefix-assignment"].arr())
            .mapNotNull { a -> a.obj()["address"].str()?.let { "$it/${a.obj()["mask"].int()}" } },
        gateway = routes.firstOrNull { it.target == "0.0.0.0" && it.mask == 0 }?.nexthop,
        dns = o["dns-server"].strList(),
        routes = routes,
        error = o["errors"].arr().firstOrNull()?.obj()?.get("code").str(),
    )
}

/** `network.device status` (no name) → per-netdev counters, for live throughput. */
data class DevStats(val name: String, val up: Boolean, val rx: Long, val tx: Long, val speed: String?, val mac: String?)

fun parseDeviceStats(j: JsonObject): Map<String, DevStats> = j.mapValues { (name, v) ->
    val o = v.obj(); val s = o["statistics"].obj()
    DevStats(name, o["up"].bool() ?: false, s["rx_bytes"].long() ?: 0, s["tx_bytes"].long() ?: 0, o["speed"].str(), o["macaddr"].str())
}

// ---------------- Wireless ----------------

data class Radio(
    val section: String, val band: String, val channel: String, val htmode: String, val txpower: String,
    val country: String, val disabled: Boolean, val up: Boolean,
    /** live from iwinfo, when the radio is up */
    val liveChannel: Int? = null, val liveTxPower: Int? = null, val noise: Int? = null, val hwmodes: String? = null,
)

data class Ssid(
    val section: String, val radio: String, val ssid: String, val mode: String, val network: List<String>,
    val encryption: String, val key: String, val disabled: Boolean, val hidden: Boolean, val isolate: Boolean,
    /** live netdev name (phy0-ap0) from `network.wireless status`, null when down */
    val ifname: String? = null,
    /** disable | allow | deny, with [maclist] */
    val macfilter: String = "disable", val maclist: List<String> = emptyList(),
)

/** Band label from uci `band` (21.02+) or channel/hwmode for older configs. */
fun bandLabel(band: String?, channel: String?, hwmode: String?): String {
    when (band) { "2g" -> return "2.4 GHz"; "5g" -> return "5 GHz"; "6g" -> return "6 GHz"; "60g" -> return "60 GHz" }
    val ch = channel?.toIntOrNull()
    return when {
        ch != null && ch <= 14 -> "2.4 GHz"
        ch != null -> "5 GHz"
        hwmode == "11a" -> "5 GHz"
        else -> "2.4 GHz"
    }
}

fun parseWireless(uci: JsonObject, status: JsonObject): Pair<List<Radio>, List<Ssid>> {
    val ifnames = mutableMapOf<String, String>()
    status.forEach { (_, r) -> r.obj()["interfaces"].arr().forEach { i -> i.obj().let { o -> o["section"].str()?.let { s -> o["ifname"].str()?.let { ifnames[s] = it } } } } }
    val sections = uci.entries.sortedBy { it.value.obj()[".index"].int() ?: 0 }
    val radios = sections.filter { it.value.obj()[".type"].str() == "wifi-device" }.map { (name, v) ->
        val o = v.obj()
        // luci-rpc getWirelessDevices embeds the radio's live iwinfo; plain network.wireless status doesn't.
        val live = status[name].obj()["iwinfo"].obj()
        Radio(
            liveChannel = live["channel"].int(), liveTxPower = live["txpower"].int(),
            noise = live["noise"].int()?.takeIf { it != 0 }, hwmodes = live["hwmodes_text"].str(),
            section = name,
            band = bandLabel(o["band"].str(), o["channel"].str(), o["hwmode"].str()),
            channel = o["channel"].str() ?: "auto",
            htmode = o["htmode"].str().orEmpty(),
            txpower = o["txpower"].str().orEmpty(),
            country = o["country"].str().orEmpty(),
            disabled = o["disabled"].bool() ?: false,
            up = status[name].obj()["up"].bool() ?: false,
        )
    }
    val ssids = sections.filter { it.value.obj()[".type"].str() == "wifi-iface" }.map { (name, v) ->
        val o = v.obj()
        Ssid(
            section = name, radio = o["device"].str().orEmpty(), ssid = o["ssid"].str().orEmpty(),
            mode = o["mode"].str() ?: "ap", network = o["network"].strList(),
            encryption = o["encryption"].str() ?: "none", key = o["key"].str().orEmpty(),
            disabled = o["disabled"].bool() ?: false, hidden = o["hidden"].bool() ?: false,
            isolate = o["isolate"].bool() ?: false, ifname = ifnames[name],
            macfilter = o["macfilter"].str() ?: "disable", maclist = o["maclist"].strList().map { it.uppercase() },
        )
    }
    return radios to ssids
}

fun encryptionLabel(raw: String): String = when (raw.substringBefore('+')) {
    "none" -> "Open"
    "owe" -> "OWE"
    "psk" -> "WPA"
    "psk2" -> "WPA2"
    "psk-mixed", "psk2-mixed" -> "WPA/WPA2"
    "sae" -> "WPA3"
    "sae-mixed" -> "WPA2/WPA3"
    "wpa", "wpa2", "wpa3", "wpa3-mixed" -> "Enterprise"
    else -> raw
}

data class ScanResult(val ssid: String, val bssid: String, val channel: Int?, val signal: Int?, val quality: Int?, val encryption: String)

fun parseScan(j: JsonObject): List<ScanResult> = j["results"].arr().map { e ->
    val o = e.obj(); val enc = o["encryption"].obj()
    ScanResult(
        ssid = o["ssid"].str() ?: "(hidden)", bssid = o["bssid"].str().orEmpty(), channel = o["channel"].int(),
        signal = o["signal"].int(),
        quality = o["quality"].int()?.let { q -> o["quality_max"].int()?.takeIf { it > 0 }?.let { q * 100 / it } },
        encryption = enc["description"].str() ?: if (enc["enabled"].bool() == true) "Encrypted" else "Open",
    )
}.sortedByDescending { it.signal ?: -200 }

// ---------------- Clients ----------------

data class Station(
    val mac: String, val ifname: String, val signal: Int?, val rxRate: Long?, val txRate: Long?,
    val rxBytes: Long?, val txBytes: Long?, val connectedSec: Long?,
)

/** `hostapd.<if> get_clients` → stations. Rates are kbit/s (hostapd reports in 100kbit units). */
fun parseHostapdClients(ifname: String, j: JsonObject): List<Station> = j["clients"].obj().map { (mac, v) ->
    val o = v.obj()
    Station(
        mac = mac.uppercase(), ifname = ifname, signal = o["signal"].int(),
        rxRate = o["rate"].obj()["rx"].long()?.times(100), txRate = o["rate"].obj()["tx"].long()?.times(100),
        rxBytes = o["bytes"].obj()["rx"].long(), txBytes = o["bytes"].obj()["tx"].long(),
        connectedSec = o["connected_time"].long(),
    )
}

/** `iwinfo assoclist` fallback when hostapd objects aren't exposed. */
fun parseAssoclist(ifname: String, j: JsonObject): List<Station> = j["results"].arr().map { e ->
    val o = e.obj()
    Station(
        mac = o["mac"].str().orEmpty().uppercase(), ifname = ifname, signal = o["signal"].int(),
        rxRate = o["rx"].obj()["rate"].long(), txRate = o["tx"].obj()["rate"].long(),
        rxBytes = o["rx"].obj()["bytes"].long(), txBytes = o["tx"].obj()["bytes"].long(),
        connectedSec = o["connected_time"].long(),
    )
}

data class Lease(val mac: String, val ip: String, val hostname: String?, val expires: Long?)

fun parseLeases(j: JsonObject): List<Lease> =
    (j["dhcp_leases"].arr() + j["dhcp6_leases"].arr()).mapNotNull { e ->
        val o = e.obj()
        val mac = o["macaddr"].str()?.uppercase() ?: return@mapNotNull null
        Lease(mac, o["ipaddr"].str() ?: o["ip6addr"].str() ?: o["ip6addrs"].strList().firstOrNull().orEmpty(), o["hostname"].str()?.takeUnless { it == "*" }?.let(::shortHost), o["expires"].long())
    }

data class HostHint(val mac: String, val name: String?, val ipv4: List<String>, val ipv6: List<String>)

fun parseHostHints(j: JsonObject): List<HostHint> = j.map { (mac, v) ->
    val o = v.obj()
    HostHint(mac.uppercase(), o["name"].str()?.let(::shortHost), o["ipaddrs"].strList(), o["ip6addrs"].strList())
}

/** "smart-bulb.lan" → "smart-bulb": the local domain is noise in a device list. IPs stay intact. */
fun shortHost(name: String): String = if (name.any { it.isLetter() } && '.' in name) name.substringBefore('.') else name

data class StaticLease(val section: String, val name: String, val mac: String, val ip: String, val dns: Boolean)

/** One row of `ip neigh show`: the kernel's live view of who is on each LAN segment. */
data class Neighbor(val ip: String, val dev: String, val mac: String, val state: String) {
    /** REACHABLE/DELAY/PROBE = talked recently; STALE = known but idle; FAILED/INCOMPLETE = gone. */
    val active get() = state in setOf("REACHABLE", "DELAY", "PROBE", "PERMANENT", "NOARP")
    val idle get() = state == "STALE"
}

fun parseNeighbors(text: String): List<Neighbor> = text.lineSequence().mapNotNull { line ->
    val p = line.trim().split(Regex("\\s+"))
    val mac = p.getOrNull(p.indexOf("lladdr") + 1)?.takeIf { p.contains("lladdr") } ?: return@mapNotNull null
    Neighbor(p[0], p.getOrNull(p.indexOf("dev") + 1).orEmpty(), mac.uppercase(), p.last().uppercase())
}.toList()

enum class Presence { ONLINE, IDLE, OFFLINE }

/** One device on the network, merged from neighbors, host hints, DHCP leases, Wi-Fi stations and static leases. */
data class Device(
    val mac: String, val name: String, val ipv4: String?, val ipv6: List<String>,
    val station: Station?, val ssid: String?, val lease: Lease?, val reservation: StaticLease?,
    val blocked: Boolean, val kind: DeviceKind,
    /** The app's block rule for this MAC, if any (may carry a time schedule). */
    val blockRule: Rule? = null,
    val presence: Presence = Presence.OFFLINE,
    /** Logical network (lan, home, iot_devices...) the device was last seen on. */
    val network: String? = null,
    /** True when the address isn't from DHCP (manually configured on the device). */
    val staticIp: Boolean = false,
) {
    val online get() = presence != Presence.OFFLINE
    val wireless get() = station != null
}
enum class DeviceKind { PHONE, COMPUTER, TV, CONSOLE, IOT, NETWORK, PRINTER, VM, UNKNOWN }

/**
 * Label for nameless devices from a few well-known MAC prefixes. ponytail: tiny hand-picked OUI
 * table, not the 30k-entry IEEE registry — extend the map if a common vendor shows up as hex.
 */
fun macVendor(mac: String): Pair<String, DeviceKind>? {
    val p = mac.uppercase().take(8)
    OUI[p]?.let { return it }
    // Locally administered bit: phones (iOS/Android "private Wi-Fi address") and containers randomize.
    val second = mac.getOrNull(1)?.uppercaseChar()
    return if (second in setOf('2', '6', 'A', 'E')) "Private MAC" to DeviceKind.UNKNOWN else null
}

private val OUI = mapOf(
    "BC:24:11" to ("Proxmox VM" to DeviceKind.VM), "52:54:00" to ("QEMU VM" to DeviceKind.VM),
    "00:50:56" to ("VMware VM" to DeviceKind.VM), "00:0C:29" to ("VMware VM" to DeviceKind.VM), "00:15:5D" to ("Hyper-V VM" to DeviceKind.VM),
    "08:00:27" to ("VirtualBox VM" to DeviceKind.VM), "02:42:AC" to ("Docker" to DeviceKind.VM),
    "B8:27:EB" to ("Raspberry Pi" to DeviceKind.COMPUTER), "DC:A6:32" to ("Raspberry Pi" to DeviceKind.COMPUTER),
    "E4:5F:01" to ("Raspberry Pi" to DeviceKind.COMPUTER), "2C:CF:67" to ("Raspberry Pi" to DeviceKind.COMPUTER), "D8:3A:DD" to ("Raspberry Pi" to DeviceKind.COMPUTER),
    "18:FE:34" to ("Espressif" to DeviceKind.IOT), "24:0A:C4" to ("Espressif" to DeviceKind.IOT), "30:AE:A4" to ("Espressif" to DeviceKind.IOT),
    "A4:CF:12" to ("Espressif" to DeviceKind.IOT), "84:F3:EB" to ("Espressif" to DeviceKind.IOT), "EC:FA:BC" to ("Espressif" to DeviceKind.IOT),
    "D8:F1:5B" to ("Tuya" to DeviceKind.IOT), "50:02:91" to ("Espressif" to DeviceKind.IOT), "40:F5:20" to ("Espressif" to DeviceKind.IOT),
    "00:11:32" to ("Synology" to DeviceKind.COMPUTER), "00:08:9B" to ("QNAP" to DeviceKind.COMPUTER), "24:5E:BE" to ("QNAP" to DeviceKind.COMPUTER),
    "B4:B0:24" to ("TP-Link" to DeviceKind.NETWORK), "50:C7:BF" to ("TP-Link" to DeviceKind.NETWORK), "98:DA:C4" to ("TP-Link" to DeviceKind.NETWORK),
    "00:1E:A6" to ("Best IT World" to DeviceKind.UNKNOWN), "F8:89:D2" to ("CloudNetwork" to DeviceKind.COMPUTER),
    "3C:22:FB" to ("Apple" to DeviceKind.PHONE), "F0:18:98" to ("Apple" to DeviceKind.COMPUTER), "A4:83:E7" to ("Apple" to DeviceKind.PHONE),
    "00:C3:0A" to ("Xiaomi" to DeviceKind.PHONE), "64:09:80" to ("Xiaomi" to DeviceKind.PHONE), "28:6C:07" to ("Xiaomi" to DeviceKind.IOT),
    "70:EE:50" to ("Netatmo" to DeviceKind.IOT), "44:07:0B" to ("Google" to DeviceKind.TV), "F4:F5:D8" to ("Google" to DeviceKind.TV),
    "FC:A1:83" to ("Amazon" to DeviceKind.IOT), "74:C2:46" to ("Amazon" to DeviceKind.IOT), "00:04:4B" to ("NVIDIA Shield" to DeviceKind.TV),
)

fun guessKind(name: String?): DeviceKind {
    val n = name?.lowercase() ?: return DeviceKind.UNKNOWN
    fun has(vararg k: String) = k.any { it in n }
    return when {
        has("iphone", "ipad", "android", "galaxy", "pixel", "redmi", "oneplus", "xiaomi", "poco", "oppo", "vivo", "realme", "phone", "tab") -> DeviceKind.PHONE
        has("tv", "roku", "fire", "chromecast", "bravia", "webos", "tizen", "shield", "mibox") -> DeviceKind.TV
        has("playstation", "ps4", "ps5", "xbox", "nintendo", "steamdeck") -> DeviceKind.CONSOLE
        has("printer", "epson", "canon", "brother", "laserjet", "officejet") -> DeviceKind.PRINTER
        has("esp", "tasmota", "shelly", "sonoff", "tuya", "plug", "bulb", "cam", "vacuum", "sensor", "hue", "nest", "echo", "alexa", "home", "wled") -> DeviceKind.IOT
        has("openwrt", "router", "repeater", "switch", "-ap", "mesh", "gateway") -> DeviceKind.NETWORK
        has("macbook", "laptop", "desktop", "pc", "windows", "thinkpad", "imac", "linux", "ubuntu", "nas", "server", "dell", "lenovo", "hp-") -> DeviceKind.COMPUTER
        else -> DeviceKind.UNKNOWN
    }
}

fun mergeDevices(
    hints: List<HostHint>, leases: List<Lease>, stations: List<Station>, ssidByIf: Map<String, String>,
    reservations: List<StaticLease>, blockedMacs: Set<String>,
    neighbors: List<Neighbor> = emptyList(), netByDev: Map<String, String> = emptyMap(), netBySsidIf: Map<String, String> = emptyMap(),
): List<Device> {
    val hintBy = hints.associateBy { it.mac }
    val leaseBy = leases.filter { '.' in it.ip }.associateBy { it.mac }
    val v6By = leases.filter { ':' in it.ip }.groupBy { it.mac }
    val staBy = stations.associateBy { it.mac }
    val resBy = reservations.associateBy { it.mac.uppercase() }
    // A MAC can have several neighbor rows (old IPs); prefer the most alive one.
    val neighBy = neighbors.groupBy { it.mac }.mapValues { (_, n) -> n.maxBy { if (it.active) 2 else if (it.idle) 1 else 0 } }
    val macs = (hintBy.keys + leaseBy.keys + staBy.keys + resBy.keys + neighBy.keys).filter { it.matches(MAC) }.toSortedSet()
    return macs.map { mac ->
        val h = hintBy[mac]; val l = leaseBy[mac]; val s = staBy[mac]; val r = resBy[mac]; val n = neighBy[mac]
        val vendor = macVendor(mac)
        val name = r?.name?.takeIf { it.isNotBlank() } ?: l?.hostname ?: h?.name
            ?: vendor?.let { "${it.first} ·${mac.takeLast(8)}" } ?: mac
        val presence = when {
            s != null || n?.active == true -> Presence.ONLINE
            n?.idle == true -> Presence.IDLE
            else -> Presence.OFFLINE
        }
        val ip = n?.takeIf { it.active || it.idle }?.ip ?: l?.ip ?: h?.ipv4?.firstOrNull() ?: r?.ip?.takeIf { it.isNotBlank() }
        Device(
            mac = mac, name = name, ipv4 = ip,
            ipv6 = (h?.ipv6.orEmpty() + v6By[mac].orEmpty().map { it.ip }).distinct(),
            station = s, ssid = s?.let { ssidByIf[it.ifname] }, lease = l, reservation = r,
            blocked = mac in blockedMacs,
            kind = guessKind(r?.name ?: l?.hostname ?: h?.name).takeIf { it != DeviceKind.UNKNOWN } ?: vendor?.second ?: DeviceKind.UNKNOWN,
            presence = presence,
            network = s?.let { netBySsidIf[it.ifname] } ?: n?.dev?.let { netByDev[it] ?: it },
            staticIp = ip != null && l?.ip != ip && r?.ip != ip,
        )
    }.sortedWith(compareBy<Device> { it.presence.ordinal }.thenBy { it.name.lowercase() })
}
private val MAC = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")

// ---------------- Firewall ----------------

data class Zone(
    val section: String, val name: String, val input: String, val output: String, val forward: String,
    val masq: Boolean, val mtuFix: Boolean, val networks: List<String>,
)

data class Forwarding(val section: String, val src: String, val dest: String, val enabled: Boolean)

data class Redirect(
    val section: String, val name: String, val enabled: Boolean, val proto: String, val src: String,
    val srcDport: String, val destIp: String, val destPort: String, val dest: String,
)

data class Rule(
    val section: String, val name: String, val enabled: Boolean, val src: String, val dest: String?,
    val proto: String, val destPort: String, val srcMac: List<String>, val target: String, val family: String,
    /** fw4 time limits: rule only active between start/stop on these weekdays ("Mon Tue"); blank = always */
    val startTime: String = "", val stopTime: String = "", val weekdays: String = "",
)

data class Firewall(val zones: List<Zone>, val forwardings: List<Forwarding>, val redirects: List<Redirect>, val rules: List<Rule>)

fun parseFirewall(uci: JsonObject): Firewall {
    val s = uci.entries.sortedBy { it.value.obj()[".index"].int() ?: 0 }
    fun of(type: String) = s.filter { it.value.obj()[".type"].str() == type }.map { it.key to it.value.obj() }
    return Firewall(
        zones = of("zone").map { (id, o) ->
            Zone(id, o["name"].str() ?: id, o["input"].str() ?: "ACCEPT", o["output"].str() ?: "ACCEPT", o["forward"].str() ?: "REJECT",
                o["masq"].bool() ?: false, o["mtu_fix"].bool() ?: false, o["network"].strList())
        },
        forwardings = of("forwarding").map { (id, o) -> Forwarding(id, o["src"].str().orEmpty(), o["dest"].str().orEmpty(), o["enabled"].bool() ?: true) },
        redirects = of("redirect").map { (id, o) ->
            Redirect(id, o["name"].str() ?: id, o["enabled"].bool() ?: true, o["proto"].strList().joinToString(" ").ifBlank { "tcp udp" },
                o["src"].str() ?: "wan", o["src_dport"].str().orEmpty(), o["dest_ip"].str().orEmpty(), o["dest_port"].str().orEmpty(), o["dest"].str() ?: "lan")
        },
        rules = of("rule").map { (id, o) ->
            Rule(id, o["name"].str() ?: id, o["enabled"].bool() ?: true, o["src"].str() ?: "", o["dest"].str(),
                o["proto"].strList().joinToString(" ").ifBlank { "tcp udp" }, o["dest_port"].strList().joinToString(" "),
                o["src_mac"].strList(), o["target"].str() ?: "DROP", o["family"].str() ?: "any",
                o["start_time"].str().orEmpty(), o["stop_time"].str().orEmpty(), o["weekdays"].strList().joinToString(" "))
        },
    )
}

// ---------------- DHCP / DNS ----------------

data class DhcpPool(val section: String, val iface: String, val start: Int, val limit: Int, val leasetime: String, val ignore: Boolean)
data class DnsRecord(val section: String, val name: String, val ip: String)
data class DnsSettings(val section: String, val servers: List<String>, val noResolv: Boolean, val domain: String, val rebindProtection: Boolean)

data class Dhcp(val pools: List<DhcpPool>, val hosts: List<StaticLease>, val records: List<DnsRecord>, val dns: DnsSettings?)

fun parseDhcp(uci: JsonObject): Dhcp {
    val s = uci.entries.sortedBy { it.value.obj()[".index"].int() ?: 0 }
    fun of(type: String) = s.filter { it.value.obj()[".type"].str() == type }.map { it.key to it.value.obj() }
    return Dhcp(
        pools = of("dhcp").map { (id, o) ->
            DhcpPool(id, o["interface"].str() ?: id, o["start"].int() ?: 100, o["limit"].int() ?: 150, o["leasetime"].str() ?: "12h", o["ignore"].bool() ?: false)
        },
        hosts = of("host").mapNotNull { (id, o) ->
            val mac = o["mac"].strList().firstOrNull() ?: return@mapNotNull null
            StaticLease(id, o["name"].str().orEmpty(), mac.uppercase(), o["ip"].str().orEmpty(), o["dns"].bool() ?: false)
        },
        records = of("domain").mapNotNull { (id, o) -> DnsRecord(id, o["name"].str() ?: return@mapNotNull null, o["ip"].str().orEmpty()) },
        dns = of("dnsmasq").firstOrNull()?.let { (id, o) ->
            DnsSettings(id, o["server"].strList(), o["noresolv"].bool() ?: false, o["domain"].str().orEmpty(), o["rebind_protection"].bool() ?: true)
        },
    )
}

// ---------------- Network config ----------------

data class IfaceConfig(
    val section: String, val proto: String, val device: String, val ipaddr: String, val netmask: String,
    val gateway: String, val dns: List<String>, val username: String, val password: String, val disabled: Boolean,
    val peerdns: Boolean,
)

data class StaticRoute(val section: String, val iface: String, val target: String, val netmask: String, val gateway: String, val metric: String)

fun parseNetworkConfig(uci: JsonObject): Pair<List<IfaceConfig>, List<StaticRoute>> {
    val s = uci.entries.sortedBy { it.value.obj()[".index"].int() ?: 0 }
    val ifaces = s.filter { it.value.obj()[".type"].str() == "interface" }.map { (id, v) ->
        val o = v.obj()
        val ip = o["ipaddr"].strList().firstOrNull().orEmpty()
        IfaceConfig(
            section = id, proto = o["proto"].str() ?: "none", device = o["device"].str() ?: o["ifname"].str().orEmpty(),
            // 21.02+ may store "192.168.1.1/24" in ipaddr with no netmask option
            ipaddr = ip.substringBefore('/'), netmask = o["netmask"].str() ?: ip.substringAfter('/', "").let { if (it.isBlank()) "" else cidrToMask(it.toInt()) },
            gateway = o["gateway"].str().orEmpty(), dns = o["dns"].strList(), username = o["username"].str().orEmpty(),
            password = o["password"].str().orEmpty(), disabled = o["disabled"].bool() ?: false, peerdns = o["peerdns"].bool() ?: true,
        )
    }
    val routes = s.filter { it.value.obj()[".type"].str() == "route" }.map { (id, v) ->
        val o = v.obj()
        StaticRoute(id, o["interface"].str().orEmpty(), o["target"].str().orEmpty(), o["netmask"].str().orEmpty(), o["gateway"].str().orEmpty(), o["metric"].str().orEmpty())
    }
    return ifaces to routes
}

fun cidrToMask(bits: Int): String {
    val m = if (bits == 0) 0L else (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
    return listOf(24, 16, 8, 0).joinToString(".") { ((m shr it) and 0xFF).toString() }
}

// ---------------- System ----------------

data class Service(val name: String, val enabled: Boolean, val running: Boolean, val startPriority: Int?)

/** `rc list` → `{ "dnsmasq": {"start":19, "stop":?, "enabled":true, "running":true}, ... }` */
fun parseServices(j: JsonObject): List<Service> = j.map { (name, v) ->
    val o = v.obj()
    Service(name, o["enabled"].bool() ?: false, o["running"].bool() ?: false, o["start"].int())
}.sortedBy { it.name }

data class LogLine(val time: Long, val priority: Int, val source: String, val message: String)

fun parseLog(j: JsonObject): List<LogLine> = j["log"].arr().map { e ->
    val o = e.obj(); val msg = o["msg"].str().orEmpty()
    // "dnsmasq[1234]: message" → source "dnsmasq"
    val src = Regex("^([\\w.\\-/]+)(\\[\\d+])?: ").find(msg)
    LogLine(o["time"].long() ?: 0, (o["priority"].int() ?: 6) and 7, src?.groupValues?.get(1).orEmpty(), if (src != null) msg.substring(src.range.last + 1) else msg)
}

data class Proc(val pid: Int, val user: String, val cpu: Double, val mem: Double, val vsz: Long, val command: String)

fun parseProcesses(j: JsonObject): List<Proc> = j["result"].arr().map { e ->
    val o = e.obj()
    Proc(o["PID"].int() ?: 0, o["USER"].str().orEmpty(), o["%CPU"].str()?.trimEnd('%')?.toDoubleOrNull() ?: 0.0,
        o["%MEM"].str()?.trimEnd('%')?.toDoubleOrNull() ?: 0.0, o["VSZ"].long() ?: 0, o["COMMAND"].str().orEmpty())
}.sortedByDescending { it.cpu }

data class Pkg(val name: String, val version: String, val description: String, val size: Long, val installed: Boolean)

/** `package-manager-call list-installed|list-available`: apk JSON array, or opkg "name - ver - desc" text. */
fun parsePackages(text: String, installed: Boolean): List<Pkg> {
    val t = text.trim()
    if (t.startsWith("[")) {
        return runCatching { kotlinx.serialization.json.Json.parseToJsonElement(t).arr() }.getOrDefault(emptyList()).mapNotNull { e ->
            val o = e.obj()
            Pkg(o["name"].str() ?: return@mapNotNull null, o["version"].str().orEmpty(), o["description"].str().orEmpty().lineSequence().firstOrNull().orEmpty(),
                o["installed-size"].long() ?: o["file-size"].long() ?: o["size"].long() ?: 0,
                installed || o["status"].strList().contains("installed"))
        }
    }
    return t.lineSequence().mapNotNull { line ->
        val p = line.split(" - ", limit = 4)
        if (p.size < 2) null else Pkg(p[0].trim(), p[1].trim(), p.getOrNull(3)?.trim() ?: p.getOrNull(2)?.trim().orEmpty(), p.getOrNull(2)?.trim()?.toLongOrNull() ?: 0, installed)
    }.toList()
}

data class UciSection(val name: String, val type: String, val anonymous: Boolean, val options: Map<String, String>)

fun parseUciConfig(uci: JsonObject): List<UciSection> = uci.entries.sortedBy { it.value.obj()[".index"].int() ?: 0 }.map { (id, v) ->
    val o = v.obj()
    UciSection(id, o[".type"].str().orEmpty(), o[".anonymous"].bool() ?: false,
        o.filterKeys { !it.startsWith(".") }.mapValues { (_, x) -> x.strList().joinToString(" ").ifEmpty { x.str().orEmpty() } })
}

// ---------------- Connections / ports / storage ----------------

data class Conn(
    val proto: String, val src: String, val sport: String, val dst: String, val dport: String,
    val bytes: Long, val packets: Long,
)

/** `luci getConntrackList` → active NAT/connection-tracking entries. */
fun parseConntrack(j: JsonObject): List<Conn> = j["result"].arr().map { e ->
    val o = e.obj()
    Conn(
        (o["layer4"].str() ?: "?").lowercase(), o["src"].str().orEmpty(), o["sport"].str().orEmpty(),
        o["dst"].str().orEmpty(), o["dport"].str().orEmpty(), o["bytes"].long() ?: 0, o["packets"].long() ?: 0,
    )
}.sortedByDescending { it.bytes }

data class Port(val device: String, val role: String, val up: Boolean, val speed: String?, val rx: Long, val tx: Long)

/** `luci getBuiltinEthernetPorts` (role/device per physical jack) joined with live link state. */
fun parsePorts(ports: JsonObject, stats: Map<String, DevStats>): List<Port> = ports["result"].arr().mapNotNull { e ->
    val o = e.obj(); val dev = o["device"].str() ?: return@mapNotNull null
    val s = stats[dev]
    Port(dev, o["role"].str().orEmpty(), s?.up == true && s.speed?.startsWith("-") != true, s?.speed, s?.rx ?: 0, s?.tx ?: 0)
}

data class Mount(val device: String, val mount: String, val size: Long, val free: Long)

fun parseMounts(j: JsonObject): List<Mount> = j["result"].arr().map { e ->
    val o = e.obj()
    Mount(o["device"].str().orEmpty(), o["mount"].str().orEmpty(), o["size"].long() ?: 0, o["free"].long() ?: 0)
}.filter { it.size > 0 }
