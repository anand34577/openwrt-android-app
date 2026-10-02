package com.openwrtmgr.app.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * Everything the app does to a router, on top of [Ubus]. All config changes go through
 * [uciSet]/[uciAdd]/[uciDelete] (staged in the session) and then [apply], which is LuCI's
 * apply-with-rollback: if the change cuts the phone off (wrong LAN IP, Wi-Fi disabled) the
 * router reverts it automatically after [ROLLBACK_SECONDS] because we can't confirm.
 */
class Router(val profile: RouterProfile, val ubus: Ubus, private val onPasswordChanged: (String) -> Unit = {}) {

    // ---------- status ----------

    suspend fun board() = parseBoard(ubus.call("system", "board"))
    suspend fun sysInfo() = parseSysInfo(ubus.call("system", "info"))
    suspend fun interfaces() = parseInterfaces(ubus.call("network.interface", "dump"))
    suspend fun deviceStats() = parseDeviceStats(ubus.call("network.device", "status"))

    /** (busy, total) CPU jiffies, null if /proc/stat isn't readable under this session's ACL. */
    suspend fun cpuJiffies(): Pair<Long, Long>? =
        ubus.callOrNull("file", "read", args("path" to "/proc/stat"))?.get("data").str()?.let(::parseCpuJiffies)

    // ---------- uci + safe apply ----------

    suspend fun uci(config: String): JsonObject = ubus.call("uci", "get", args("config" to config))["values"].obj()

    /** Stage option changes. A null value deletes the option; a List writes a uci list. */
    suspend fun uciSet(config: String, section: String, values: Map<String, Any?>) {
        val (del, set) = values.entries.partition { it.value == null || (it.value as? String)?.isEmpty() == true || (it.value as? List<*>)?.isEmpty() == true }
        if (set.isNotEmpty()) ubus.call("uci", "set", args("config" to config, "section" to section, "values" to set.associate { it.key to it.value }))
        if (del.isNotEmpty()) ubus.callOrNull("uci", "delete", args("config" to config, "section" to section, "options" to del.map { it.key }))
    }

    suspend fun uciAdd(config: String, type: String, values: Map<String, Any?>, name: String? = null): String {
        val clean = values.filterValues { it != null && it != "" && (it as? List<*>)?.isEmpty() != true }
        return ubus.call("uci", "add", args("config" to config, "type" to type, "name" to name, "values" to clean))["section"].str()
            ?: throw RouterException("Router didn't return the new section name")
    }

    suspend fun uciDelete(config: String, section: String) {
        ubus.call("uci", "delete", args("config" to config, "section" to section))
    }

    suspend fun revert(config: String) { ubus.callOrNull("uci", "revert", args("config" to config)) }

    private val applyLock = Mutex()

    /**
     * Commit + reload every staged change. With rollback, the router reverts unless we confirm
     * within [ROLLBACK_SECONDS] — the confirm only reaches it if we're still connected.
     */
    suspend fun apply(rollback: Boolean = true) = applyLock.withLock {
        val changes = ubus.callOrNull("uci", "changes")?.get("changes").obj()
        if (changes.isEmpty()) return@withLock
        try {
            ubus.call("uci", "apply", args("rollback" to rollback, "timeout" to ROLLBACK_SECONDS))
        } catch (e: RouterException) {
            if (e.code == Ubus.PERMISSION_DENIED) throw RouterException("Another apply is in progress on the router (e.g. from LuCI). Try again in ${ROLLBACK_SECONDS}s.")
            throw e
        }
        if (rollback) {
            // Give services a moment to restart, then prove we can still reach the router.
            kotlinx.coroutines.delay(2500)
            var confirmed = false
            repeat(8) {
                if (!confirmed && runCatching { ubus.call("uci", "confirm") }.isSuccess) confirmed = true
                if (!confirmed) kotlinx.coroutines.delay(2000)
            }
            if (!confirmed) throw RouterException("Lost contact with the router after applying — it will roll the change back within ${ROLLBACK_SECONDS}s.")
        }
    }

    suspend fun reloadConfig() { ubus.callOrNull("uci", "reload_config") }

    // ---------- wireless ----------

    data class Wireless(val radios: List<Radio>, val ssids: List<Ssid>)

    suspend fun wireless(): Wireless = coroutineScope {
        val uci = async { uci("wireless") }
        // 25.x denies network.wireless.status to the LuCI session; luci-rpc wraps the same data
        // (plus per-interface iwinfo) and is what LuCI's own Wireless page reads.
        val status = async {
            ubus.callOrNull("luci-rpc", "getWirelessDevices") ?: ubus.callOrNull("network.wireless", "status") ?: Ubus.EMPTY
        }
        val (radios, ssids) = parseWireless(uci.await(), status.await())
        // Enrich each up radio with live iwinfo from any of its interfaces.
        val live = radios.map { r ->
            async {
                if (r.liveChannel != null || !r.up) return@async r
                val ifn = ssids.firstOrNull { it.radio == r.section && it.ifname != null }?.ifname ?: return@async r
                val i = ubus.callOrNull("iwinfo", "info", args("device" to ifn)) ?: return@async r
                r.copy(liveChannel = i["channel"].int(), liveTxPower = i["txpower"].int(), noise = i["noise"].int(), hwmodes = i["hwmodes_text"].str() ?: i["hwmodes"].strList().joinToString("/"))
            }
        }.awaitAll()
        Wireless(live, ssids)
    }

    suspend fun channels(ifname: String): List<Int> =
        ubus.callOrNull("iwinfo", "freqlist", args("device" to ifname))?.get("results").arr()
            .filter { it.obj()["restricted"].bool() != true }.mapNotNull { it.obj()["channel"].int() }

    suspend fun scan(ifname: String): List<ScanResult> = parseScan(ubus.call("iwinfo", "scan", args("device" to ifname)))

    suspend fun saveRadio(section: String, values: Map<String, Any?>) { uciSet("wireless", section, values); apply() }

    suspend fun saveSsid(section: String?, values: Map<String, Any?>) {
        if (section == null) uciAdd("wireless", "wifi-iface", values) else uciSet("wireless", section, values)
        apply()
    }

    suspend fun deleteSsid(section: String) { uciDelete("wireless", section); apply() }

    // ---------- devices ----------

    data class Clients(val devices: List<Device>, val stationsByIf: Map<String, List<Station>>)

    suspend fun clients(): Clients = coroutineScope {
        val hints = async { ubus.callOrNull("luci-rpc", "getHostHints")?.let(::parseHostHints).orEmpty() }
        val leases = async { ubus.callOrNull("luci-rpc", "getDHCPLeases")?.let(::parseLeases).orEmpty() }
        val dhcp = async { parseDhcp(uci("dhcp")) }
        val fw = async { parseFirewall(uci("firewall")) }
        val neigh = async { neighbors() }
        val ifs = async { runCatching { interfaces() }.getOrDefault(emptyList()) }
        val w = wireless()
        val aps = w.ssids.filter { it.ifname != null && it.mode == "ap" }
        val stations = aps.map { s ->
            async {
                ubus.callOrNull("hostapd.${s.ifname}", "get_clients")?.let { parseHostapdClients(s.ifname!!, it) }
                    ?: ubus.callOrNull("iwinfo", "assoclist", args("device" to s.ifname))?.let { parseAssoclist(s.ifname!!, it) }
                    ?: emptyList()
            }
        }.awaitAll().flatten()
        val blockRules = fw.await().rules.filter { it.enabled && it.name.startsWith(BLOCK_PREFIX) }
            .flatMap { r -> r.srcMac.map { it.uppercase() to r } }.toMap()
        val devices = mergeDevices(
            hints.await(), leases.await(), stations, aps.associate { it.ifname!! to it.ssid }, dhcp.await().hosts, blockRules.keys,
            neighbors = neigh.await(),
            netByDev = ifs.await().mapNotNull { i -> i.device?.let { it to i.name } }.toMap(),
            netBySsidIf = aps.associate { it.ifname!! to (it.network.firstOrNull() ?: "") },
        ).map { it.copy(blockRule = blockRules[it.mac]) }
        Clients(devices, stations.groupBy { it.ifname })
    }

    /** Kernel neighbor table (IPv4) — the same `ip neigh` LuCI's Routes page runs. Empty if not permitted. */
    suspend fun neighbors(): List<Neighbor> = runCatching { parseNeighbors(exec("/sbin/ip", "-4", "neigh", "show").stdout) }.getOrDefault(emptyList())

    /** Full kernel routing table text (all tables), as LuCI shows it. */
    suspend fun routeTable(): String = runCatching { exec("/sbin/ip", "-4", "route", "show", "table", "all").stdout }.getOrDefault("")

    /**
     * Cumulative (download, upload) bytes for one device. Wi-Fi: the station's link counters.
     * Wired: summed conntrack bytes for its IP (both directions, so reported as download).
     */
    suspend fun deviceBytes(d: Device): Pair<Long, Long>? {
        d.station?.let { s ->
            val st = ubus.callOrNull("iwinfo", "assoclist", args("device" to s.ifname, "mac" to d.mac))?.let { parseAssoclist(s.ifname, it) }
                ?.firstOrNull { it.mac == d.mac } ?: return null
            return (st.txBytes ?: 0) to (st.rxBytes ?: 0) // router tx = device download
        }
        val ip = d.ipv4 ?: return null
        return connections().filter { it.src == ip || it.dst == ip }.sumOf { it.bytes } to 0L
    }

    /** Deauthenticate a Wi-Fi client; [banMs] > 0 keeps it from reconnecting for that long. */
    suspend fun kick(ifname: String, mac: String, banMs: Int = 0) {
        ubus.call("hostapd.$ifname", "del_client", args("addr" to mac.lowercase(), "reason" to 5, "deauth" to true, "ban_time" to banMs))
    }

    /** Block/unblock internet access for a device: a fw4 rule rejecting its MAC towards wan. */
    suspend fun setBlocked(mac: String, name: String, blocked: Boolean) {
        val rules = parseFirewall(uci("firewall")).rules.filter { it.name.startsWith(BLOCK_PREFIX) && it.srcMac.any { m -> m.equals(mac, true) } }
        if (blocked && rules.isEmpty()) {
            uciAdd("firewall", "rule", mapOf("name" to "$BLOCK_PREFIX$name", "src" to "*", "dest" to "wan", "src_mac" to listOf(mac), "proto" to "all", "target" to "REJECT"))
        } else if (!blocked) {
            rules.forEach { uciDelete("firewall", it.section) }
        }
        apply()
    }

    /** Reserve an IP (and/or a name) for a MAC — a dnsmasq `config host`. Empty ip = name only. */
    suspend fun saveReservation(existing: String?, mac: String, name: String, ip: String) {
        val values = mapOf("name" to name.ifBlank { null }, "mac" to mac, "ip" to ip.ifBlank { null }, "dns" to if (name.isNotBlank()) "1" else null)
        if (existing == null) uciAdd("dhcp", "host", values) else uciSet("dhcp", existing, values)
        apply()
    }

    suspend fun deleteReservation(section: String) { uciDelete("dhcp", section); apply() }

    // ---------- network ----------

    data class Network(val live: List<Iface>, val config: List<IfaceConfig>, val routes: List<StaticRoute>, val dhcp: Dhcp, val firewall: Firewall)

    suspend fun network(): Network = coroutineScope {
        val live = async { interfaces() }
        val cfg = async { parseNetworkConfig(uci("network")) }
        val dhcp = async { parseDhcp(uci("dhcp")) }
        val fw = async { parseFirewall(uci("firewall")) }
        val (ifs, routes) = cfg.await()
        Network(live.await(), ifs, routes, dhcp.await(), fw.await())
    }

    suspend fun ifaceAction(name: String, action: String) { ubus.call("network.interface.$name", action) } // up | down | renew

    suspend fun saveIface(section: String, values: Map<String, Any?>) { uciSet("network", section, values); apply() }

    suspend fun saveUci(config: String, section: String?, type: String, values: Map<String, Any?>) {
        if (section == null) uciAdd(config, type, values) else uciSet(config, section, values)
        apply()
    }

    suspend fun deleteUci(config: String, section: String) { uciDelete(config, section); apply() }

    // ---------- system ----------

    suspend fun services() = parseServices(ubus.call("rc", "list"))

    /** start | stop | restart | reload | enable | disable */
    suspend fun service(name: String, action: String) { ubus.call("rc", "init", args("name" to name, "action" to action)) }

    suspend fun log(lines: Int = 300) = parseLog(ubus.call("log", "read", args("lines" to lines, "stream" to false, "oneshot" to true)))

    suspend fun dmesg(): String = exec("/bin/dmesg").stdout

    suspend fun processes() = parseProcesses(ubus.call("luci", "getProcessList"))

    suspend fun kill(pid: Int, signal: Int = 15) { exec("/bin/kill", "-$signal", pid.toString()).orThrow("kill") }

    suspend fun reboot() { ubus.call("system", "reboot") }

    suspend fun setPassword(newPassword: String) {
        ubus.call("luci", "setPassword", args("username" to profile.username, "password" to newPassword))
        ubus.login(profile.username, newPassword) // future re-logins must use the new password
        onPasswordChanged(newPassword)
    }

    suspend fun routerTime(): Long? = ubus.callOrNull("luci", "getUnixtime")?.get("result").long()

    suspend fun setRouterTime(epochSeconds: Long) { ubus.call("luci", "setLocaltime", args("localtime" to epochSeconds)) }

    suspend fun systemSection(): Pair<String, JsonObject> =
        uci("system").entries.first { it.value.obj()[".type"].str() == "system" }.let { it.key to it.value.obj() }

    /** Scheduled reboot via root's crontab; null clears it. Other cron lines are preserved. */
    suspend fun crontab(): String = ubus.callOrNull("file", "read", args("path" to "/etc/crontabs/root"))?.get("data").str().orEmpty()

    suspend fun setCrontab(text: String) {
        ubus.call("file", "write", args("path" to "/etc/crontabs/root", "data" to text, "mode" to 384)) // 0600
        service("cron", "restart")
    }

    // packages — LuCI's package-manager helper; works for apk (25.x) and opkg
    suspend fun installedPackages() = parsePackages(ubus.cgiExec(PKG, "list-installed", stderr = false), installed = true)
    suspend fun availablePackages() = parsePackages(ubus.cgiExec(PKG, "list-available", stderr = false), installed = false)

    /** update | install <pkg> | remove <pkg>. Returns the tool's combined output. */
    suspend fun packageAction(vararg argv: String): String {
        val out = ubus.cgiExec(PKG, *argv, stderr = true)
        val j = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(out).obj() }.getOrNull()
        if (j == null || j.isEmpty()) return out
        val text = listOfNotNull(j["stdout"].str(), j["stderr"].str()).joinToString("\n").trim()
        if ((j["code"].int() ?: 0) != 0) throw RouterException(text.ifBlank { "Package manager failed (code ${j["code"].int()})" })
        return text
    }

    // backup / restore / firmware — exactly LuCI's flash.js sequence
    suspend fun backup(): ByteArray = ubus.downloadBackup()

    suspend fun uploadBackup(bytes: ByteArray): String {
        ubus.upload("/tmp/backup.tar.gz", bytes)
        val list = exec("/bin/tar", "-tzf", "/tmp/backup.tar.gz")
        if (list.code != 0) {
            ubus.callOrNull("file", "remove", args("path" to "/tmp/backup.tar.gz"))
            throw RouterException("That file isn't a readable backup archive.")
        }
        return list.stdout
    }

    suspend fun restoreBackup() {
        exec("/sbin/sysupgrade", "--restore-backup", "/tmp/backup.tar.gz").orThrow("Restore")
        runCatching { exec("/sbin/reboot") }
    }

    data class FirmwareCheck(val valid: Boolean, val forceable: Boolean, val allowBackup: Boolean, val message: String)

    suspend fun uploadFirmware(bytes: ByteArray): FirmwareCheck {
        ubus.upload("/tmp/firmware.bin", bytes)
        val v = ubus.callOrNull("system", "validate_firmware_image", args("path" to "/tmp/firmware.bin")) ?: Ubus.EMPTY
        val t = exec("/sbin/sysupgrade", "--test", "/tmp/firmware.bin")
        return FirmwareCheck(
            valid = (v["valid"].bool() ?: false) && t.code == 0, forceable = v["forceable"].bool() ?: false,
            allowBackup = v["allow_backup"].bool() ?: true, message = t.stderr.trim(),
        )
    }

    /** Starts flashing; the connection drops as the router goes down. */
    suspend fun flashFirmware(keepSettings: Boolean, force: Boolean) {
        val argv = buildList { if (!keepSettings) add("-n"); if (force) add("--force"); add("/tmp/firmware.bin") }
        runCatching { exec("/sbin/sysupgrade", *argv.toTypedArray()) }
    }

    suspend fun discardFirmware() { ubus.callOrNull("file", "remove", args("path" to "/tmp/firmware.bin")) }

    suspend fun factoryReset() { runCatching { exec("/sbin/firstboot", "-r", "-y") } }

    // diagnostics — same command lines LuCI's Diagnostics page sends, so the same ACL allows them
    suspend fun ping(host: String) = ubus.cgiExec("ping", "-4", "-c", "5", "-W", "1", host.safeHost())
    suspend fun traceroute(host: String) = ubus.cgiExec("traceroute", "-4", "-q", "1", "-w", "1", "-n", "-m", "20", host.safeHost())
    suspend fun nslookup(host: String) = ubus.cgiExec("nslookup", host.safeHost())

    private fun String.safeHost(): String {
        val h = trim()
        if (!h.matches(Regex("^[A-Za-z0-9.:_-]{1,253}$"))) throw RouterException("Enter a host name or IP address.")
        return h
    }

    /**
     * One-shot guest network: a bridge + static interface on a free /24, DHCP, an isolated
     * firewall zone that can only reach WAN (plus DHCP/DNS on the router), and an isolated
     * WPA2/WPA3 SSID on each chosen radio. Applied as one rollback-protected change.
     */
    suspend fun createGuestNetwork(ssid: String, key: String, radios: List<String>): String {
        val net = parseNetworkConfig(uci("network")).first
        val taken = net.map { it.section }.toSet() + parseFirewall(uci("firewall")).zones.map { it.name }
        val name = (listOf("guest") + (2..9).map { "guest$it" }).first { it !in taken }
        val used = (net.map { it.ipaddr } + interfaces().flatMap { i -> i.ipv4.map { it.substringBefore('/') } })
            .mapNotNull { ip -> ip.split('.').takeIf { it.size == 4 }?.let { "${it[0]}.${it[1]}.${it[2]}" } }.toSet()
        val subnet = (50..250).map { "192.168.$it" }.first { it !in used }
        uciAdd("network", "device", mapOf("name" to "br-$name", "type" to "bridge", "bridge_empty" to "1"))
        uciAdd("network", "interface", mapOf("proto" to "static", "device" to "br-$name", "ipaddr" to "$subnet.1", "netmask" to "255.255.255.0"), name = name)
        uciAdd("dhcp", "dhcp", mapOf("interface" to name, "start" to "100", "limit" to "150", "leasetime" to "2h"), name = name)
        uciAdd("firewall", "zone", mapOf("name" to name, "network" to listOf(name), "input" to "REJECT", "output" to "ACCEPT", "forward" to "REJECT"))
        uciAdd("firewall", "forwarding", mapOf("src" to name, "dest" to "wan"))
        uciAdd("firewall", "rule", mapOf("name" to "Allow-$name-DNS", "src" to name, "dest_port" to "53", "proto" to listOf("tcp", "udp"), "target" to "ACCEPT"))
        uciAdd("firewall", "rule", mapOf("name" to "Allow-$name-DHCP", "src" to name, "dest_port" to "67", "proto" to listOf("udp"), "family" to "ipv4", "target" to "ACCEPT"))
        radios.forEach { radio ->
            uciAdd("wireless", "wifi-iface", mapOf(
                "device" to radio, "mode" to "ap", "network" to listOf(name), "ssid" to ssid,
                "encryption" to "sae-mixed", "key" to key, "isolate" to "1",
            ))
        }
        apply()
        return "$name · $subnet.0/24"
    }

    data class WifiSchedule(val off: String, val on: String, val days: String)

    /** Radio on/off times kept as tagged lines in root's crontab. */
    suspend fun wifiSchedule(): WifiSchedule? {
        val lines = crontab().lines().filter { WIFI_TAG in it }
        val off = lines.firstOrNull { "wifi down" in it }?.split(' ') ?: return null
        val on = lines.firstOrNull { "wifi up" in it }?.split(' ')
        return WifiSchedule("%02d:%02d".format(off[1].toInt(), off[0].toInt()), on?.let { "%02d:%02d".format(it[1].toInt(), it[0].toInt()) } ?: "", off[4])
    }

    /** null clears it. [days] is a cron weekday field: "*" or e.g. "1-5" / "0,6". */
    suspend fun setWifiSchedule(s: WifiSchedule?) {
        val others = crontab().lines().filter { it.isNotBlank() && WIFI_TAG !in it }
        val mine = s?.let {
            val (offH, offM) = it.off.split(':'); val (onH, onM) = it.on.split(':')
            listOf("${offM.toInt()} ${offH.toInt()} * * ${it.days} /sbin/wifi down $WIFI_TAG", "${onM.toInt()} ${onH.toInt()} * * * /sbin/wifi up $WIFI_TAG") // "up" daily: harmless when already up, never strands a radio off
        }.orEmpty()
        setCrontab((others + mine).joinToString("\n", postfix = "\n"))
    }

    suspend fun connections() = parseConntrack(ubus.call("luci", "getConntrackList"))

    suspend fun ports(): List<Port> = parsePorts(ubus.callOrNull("luci", "getBuiltinEthernetPorts") ?: Ubus.EMPTY, deviceStats())

    suspend fun mounts() = parseMounts(ubus.call("luci", "getMountPoints"))

    /** Small text files LuCI's ACL lets us read/write (authorized_keys, rc.local, sysupgrade.conf...). */
    suspend fun readFile(path: String): String = try {
        ubus.call("file", "read", args("path" to path))["data"].str().orEmpty()
    } catch (e: RouterException) {
        if (e.code == 4) "" else throw e // missing file reads as empty; saving creates it
    }

    suspend fun writeFile(path: String, text: String, mode: Int = 420) { // 0644
        ubus.call("file", "write", args("path" to path, "data" to text, "mode" to mode))
    }

    /** Pause internet for a MAC on a schedule (fw4 rule time limits). Blank times = always blocked. */
    suspend fun saveBlockSchedule(mac: String, name: String, start: String, stop: String, weekdays: List<String>) {
        parseFirewall(uci("firewall")).rules.filter { it.name.startsWith(BLOCK_PREFIX) && it.srcMac.any { m -> m.equals(mac, true) } }
            .forEach { uciDelete("firewall", it.section) }
        suspend fun add(from: String?, to: String?) = uciAdd("firewall", "rule", mapOf(
            "name" to "$BLOCK_PREFIX$name", "src" to "*", "dest" to "wan", "src_mac" to listOf(mac), "proto" to "all", "target" to "REJECT",
            "start_time" to from, "stop_time" to to, "weekdays" to weekdays,
        ))
        // nftables hour ranges can't wrap midnight: 22:00–07:00 becomes 22:00–23:59 + 00:00–07:00.
        if (start.isNotBlank() && stop.isNotBlank() && start > stop) { add(start, "23:59:59"); add("00:00", stop) }
        else add(start.ifBlank { null }, stop.ifBlank { null })
        apply()
    }

    suspend fun setRuleEnabled(section: String, enabled: Boolean) {
        uciSet("firewall", section, mapOf("enabled" to if (enabled) null else "0")); apply()
    }

    /** Raw ubus call for the API console: pretty JSON of the reply, or the error. */
    suspend fun rawCall(obj: String, method: String, argsJson: String): String {
        val a = if (argsJson.isBlank()) Ubus.EMPTY else runCatching { kotlinx.serialization.json.Json.parseToJsonElement(argsJson).obj() }
            .getOrElse { throw RouterException("Arguments must be a JSON object, e.g. {\"config\":\"network\"}") }
        val res = ubus.call(obj, method, a)
        return kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), res)
    }

    suspend fun ubusObjects(): List<String> = ubus.listObjects()

    // raw uci editor
    suspend fun uciConfigs(): List<String> = ubus.call("uci", "configs")["configs"].strList().sorted()

    /** Some rpcd ACLs refuse `uci configs` but still allow reading each config: probe the well-known names instead. */
    suspend fun uciConfigsOrProbe(extra: List<String> = emptyList()): List<String> = try {
        uciConfigs()
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) {
        coroutineScope {
            (BASE_CONFIGS + extra).distinct().map { c -> async { c.takeIf { runCatching { uci(c) }.isSuccess } } }.awaitAll().filterNotNull().sorted()
        }
    }

    /** Wake-on-LAN via etherwake / wol, if installed and permitted by the session's rpcd ACL. */
    suspend fun wake(mac: String, device: String?) {
        val tries = listOf(
            "/usr/bin/etherwake" to listOfNotNull(device?.let { "-i" }, device, mac),
            "/usr/sbin/ether-wake" to listOfNotNull(device?.let { "-i" }, device, mac),
            "/usr/bin/wol" to listOf(mac),
        )
        for ((cmd, a) in tries) {
            try { exec(cmd, *a.toTypedArray()).orThrow("Wake-on-LAN"); return } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { }
        }
        throw RouterException("Couldn't send the wake packet. Install the 'etherwake' package (System → Packages), or wake it from LuCI.")
    }

    // ---------- exec ----------

    data class ExecResult(val code: Int, val stdout: String, val stderr: String) {
        fun orThrow(what: String): ExecResult {
            if (code != 0) throw RouterException("$what failed (exit $code)${stderr.trim().takeIf { it.isNotEmpty() }?.let { ":\n$it" }.orEmpty()}")
            return this
        }
    }

    /** rpcd `file.exec` — only commands the session's ACL allows (the same set LuCI uses). */
    suspend fun exec(command: String, vararg params: String): ExecResult {
        val r = ubus.call("file", "exec", args("command" to command, "params" to params.toList()))
        return ExecResult(r["code"].int() ?: 0, r["stdout"].str().orEmpty(), r["stderr"].str().orEmpty())
    }

    companion object {
        const val ROLLBACK_SECONDS = 30
        private val BASE_CONFIGS = listOf("system", "network", "wireless", "firewall", "dhcp", "dropbear", "uhttpd", "rpcd", "luci", "fstab", "ucitrack", "attendedsysupgrade", "dnsmasq", "odhcpd")
        const val BLOCK_PREFIX = "Block: "
        private const val WIFI_TAG = "# openwrtmgr-wifi"
        private const val PKG = "/usr/libexec/package-manager-call"
    }
}
