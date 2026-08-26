package com.openwrtmgr.app.core.networking

import com.openwrtmgr.app.domain.model.Client
import com.openwrtmgr.app.domain.model.DhcpLease
import com.openwrtmgr.app.domain.model.DnsRecord
import com.openwrtmgr.app.domain.model.FirewallZone
import com.openwrtmgr.app.domain.model.LogEntry
import com.openwrtmgr.app.domain.model.LogSeverity
import com.openwrtmgr.app.domain.model.NetworkInterfaceInfo
import com.openwrtmgr.app.domain.model.Package
import com.openwrtmgr.app.domain.model.PackageActionResult
import com.openwrtmgr.app.domain.model.PortForward
import com.openwrtmgr.app.domain.model.RouterCapabilities
import com.openwrtmgr.app.domain.model.RouterProfile
import com.openwrtmgr.app.domain.model.ServiceStatus
import com.openwrtmgr.app.domain.model.SystemInfo
import com.openwrtmgr.app.domain.model.TrafficRule
import com.openwrtmgr.app.domain.model.UciSection
import com.openwrtmgr.app.domain.model.VlanDevice
import com.openwrtmgr.app.domain.model.WifiAssociation
import com.openwrtmgr.app.domain.model.WifiRadio
import com.openwrtmgr.app.domain.model.mergeClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ubus-over-HTTP implementation of [OpenWrtClient]. One instance per connected router profile.
 * Requires the router to have uhttpd-mod-ubus (bundled with default LuCI installs).
 *
 * [onSshHostKeyLearned] persists the SSH host-key fingerprint the first time any SSH-backed call
 * (packages, service control, backup/restore) succeeds, so later calls can verify against it —
 * see [SshExecClient]'s TOFU pinning.
 */
class UbusHttpClient(
    private val profile: RouterProfile,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build(),
    private val onSshHostKeyLearned: suspend (String) -> Unit = {},
) : OpenWrtClient {

    // encodeDefaults=true: without it, kotlinx.serialization omits default-valued fields
    // (jsonrpc="2.0", method="call") from the request body, which uhttpd-mod-ubus rejects
    // with a -32700 parse error since it requires both to be present.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val requestId = AtomicInteger(1)
    private val endpoint = "${profile.baseUrl}/ubus"

    @Volatile private var sessionId: String = UBUS_ANONYMOUS_SESSION

    // Kept only to silently re-login once when the ubus session times out (procd's default idle
    // timeout is ~300s) — otherwise every call after that point fails forever until app restart,
    // since the cached client in DefaultRouterRepository is reused for the app's lifetime. Also
    // reused as the SSH credentials (same root login) for the SSH-backed calls below.
    @Volatile private var lastUsername: String? = null
    @Volatile private var lastPassword: String? = null

    override suspend fun authenticate(username: String, password: String): Result<Unit> = runCatching {
        if (!login(username, password)) throw OpenWrtException.AuthenticationFailed()
        lastUsername = username
        lastPassword = password
    }.recoverCatching { throw it.toOpenWrtException() }

    private suspend fun login(username: String, password: String): Boolean {
        val args = JsonObject(mapOf("username" to JsonPrimitive(username), "password" to JsonPrimitive(password)))
        val (status, data) = call(UBUS_ANONYMOUS_SESSION, "session", "login", args, allowReauth = false)
        if (status != 0 || data == null) return false
        val loginResult = json.decodeFromJsonElement(UbusSessionLoginResult.serializer(), data)
        sessionId = loginResult.session
        return true
    }

    override suspend fun getCapabilities(): Result<RouterCapabilities> = runCatching {
        // ubus "list" enumerates every object rpcd currently exposes to this session —
        // that's the real, per-router, per-permission capability set.
        // Unlike "call", its result is a bare JSON array of object-name strings, not [status, data].
        RouterCapabilities(listObjects(sessionId))
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun getSystemInfo(): Result<SystemInfo> = runCatching {
        val (infoStatus, info) = call(sessionId, "system", "info", JsonObject(emptyMap()))
        requireOk(infoStatus, "system.info")
        val (boardStatus, board) = call(sessionId, "system", "board", JsonObject(emptyMap()))
        requireOk(boardStatus, "system.board")
        parseSystemInfo(info!!.jsonObject, board!!.jsonObject)
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun getInterfaces(): Result<List<NetworkInterfaceInfo>> = runCatching {
        val (status, data) = call(sessionId, "network.interface", "dump", JsonObject(emptyMap()))
        requireOk(status, "network.interface.dump")
        val interfaces = data!!.jsonObject["interface"]?.jsonArray ?: JsonArray(emptyList())
        interfaces.map { it.jsonObject.toInterfaceInfo() }
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun getWifiRadios(): Result<List<WifiRadio>> = runCatching {
        // ubus object "network.wireless" (netifd) — the source of truth for uci wifi-device *section
        // names* (radio0, radio1...) and administrative enabled/disabled state, which iwinfo alone
        // can't give us (a disabled radio won't answer iwinfo queries at all). iwinfo enriches each
        // radio with live signal/bitrate once we know its actual netdev ifname.
        val radios = wirelessStatus() ?: return@runCatching emptyList()
        radios.map { (radioName, radioObj) ->
            val ifname = radioObj["interfaces"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("ifname")?.jsonPrimitive?.contentOrNull
            val iwinfo = ifname?.let { name ->
                val (status, info) = call(sessionId, "iwinfo", "info", JsonObject(mapOf("device" to JsonPrimitive(name))))
                if (status == 0) info?.jsonObject else null
            }
            buildWifiRadio(radioName, radioObj, iwinfo)
        }
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun setInterfaceUp(interfaceName: String): Result<Unit> = runCatching {
        val (status, _) = call(sessionId, "network.interface.$interfaceName", "up", JsonObject(emptyMap()))
        requireOk(status, "network.interface.$interfaceName.up")
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun setInterfaceDown(interfaceName: String): Result<Unit> = runCatching {
        val (status, _) = call(sessionId, "network.interface.$interfaceName", "down", JsonObject(emptyMap()))
        requireOk(status, "network.interface.$interfaceName.down")
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun setRadioEnabled(device: String, enabled: Boolean): Result<Unit> = runCatching {
        // device is the uci wifi-device section name (e.g. "radio0") from getWifiRadios(), not a netdev ifname.
        uciSetStrings("wireless", device, mapOf("disabled" to if (enabled) "0" else "1"))
        uciCommit("wireless")
        val (status, _) = call(sessionId, "network.wireless", "reload", JsonObject(emptyMap()))
        requireOk(status, "network.wireless.reload")
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun getDhcpLeases(): Result<List<DhcpLease>> = runCatching {
        // ubus object "file" (rpcd built-in) — same source LuCI's DHCP leases page reads.
        // Not every install grants this or runs dnsmasq for DHCP (odhcpd-only setups): empty, not an error.
        val args = JsonObject(mapOf("path" to JsonPrimitive("/tmp/dhcp.leases")))
        val (status, data) = call(sessionId, "file", "read", args)
        if (status != 0 || data == null) return@runCatching emptyList()
        val contents = data.jsonObject["data"]?.jsonPrimitive?.contentOrNull ?: return@runCatching emptyList()
        parseDhcpLeases(contents)
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun getWifiAssociations(): Result<List<WifiAssociation>> = runCatching {
        val radios = wirelessStatus() ?: return@runCatching emptyList()
        radios.values.flatMap { radioObj ->
            radioObj["interfaces"]?.jsonArray.orEmpty()
        }.flatMap { ifaceJson ->
            val ifaceObj = ifaceJson.jsonObject
            val ifname = ifaceObj["ifname"]?.jsonPrimitive?.contentOrNull ?: return@flatMap emptyList()
            val ssid = ifaceObj["config"]?.jsonObject?.get("ssid")?.jsonPrimitive?.contentOrNull
            val (status, data) = call(sessionId, "iwinfo", "assoclist", JsonObject(mapOf("device" to JsonPrimitive(ifname))))
            if (status != 0 || data == null) return@flatMap emptyList()
            data.jsonObject["results"]?.jsonArray.orEmpty().map { it.jsonObject.toWifiAssociation(ifname, ssid) }
        }
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun getClients(): Result<List<Client>> = runCatching {
        // getDhcpLeases()/getWifiAssociations() already treat an unsupported ubus object (missing
        // ACL, no dnsmasq, no iwinfo) as a successful empty list — a real *thrown* failure here
        // (auth, network, unexpected response) is a genuine problem and must not be swallowed into
        // a silent "no devices found", or a session-expiry looks identical to an empty network.
        val leases = getDhcpLeases().getOrThrow()
        val associations = getWifiAssociations().getOrThrow()
        mergeClients(leases, associations)
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun getPortForwards(): Result<List<PortForward>> = runCatching {
        val sections = uciGetAll("firewall") ?: return@runCatching emptyList()
        sections.mapNotNull { (sectionId, section) ->
            if (section[".type"]?.jsonPrimitive?.contentOrNull != "redirect") return@mapNotNull null
            section.toPortForward(sectionId)
        }
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun savePortForward(rule: PortForward): Result<Unit> = runCatching {
        val isNewSection = rule.uciSectionId == null
        val sectionId = rule.uciSectionId ?: uciAdd("firewall", "redirect")
            ?: throw OpenWrtException.UnexpectedResponse("uci didn't return a new section id")
        try {
            uciSetStrings(
                "firewall",
                sectionId,
                mapOf(
                    "name" to rule.name,
                    "enabled" to if (rule.enabled) "1" else "0",
                    "target" to "DNAT",
                    "src" to rule.sourceZone,
                    "dest" to rule.destZone,
                    "proto" to rule.protocol,
                    "src_dport" to rule.externalPort,
                    "dest_ip" to rule.internalIp,
                    "dest_port" to rule.internalPort,
                ),
            )
            uciCommit("firewall")
        } catch (e: Exception) {
            // A failure between `add` and `set`/`commit` would otherwise leave a nameless orphan
            // redirect section in /etc/config/firewall — clean it up before surfacing the error.
            if (isNewSection) runCatching { uciDelete("firewall", sectionId) }
            throw e
        }
        // Persisted, not applied to the live ruleset yet — see reloadFirewall().
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun deletePortForward(uciSectionId: String): Result<Unit> = runCatching {
        uciDelete("firewall", uciSectionId)
        uciCommit("firewall")
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun getFirewallZones(): Result<List<FirewallZone>> = runCatching {
        val sections = uciGetAll("firewall") ?: return@runCatching emptyList()
        sections.mapNotNull { (sectionId, section) ->
            if (section[".type"]?.jsonPrimitive?.contentOrNull != "zone") return@mapNotNull null
            section.toFirewallZone(sectionId)
        }
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun saveFirewallZone(zone: FirewallZone): Result<Unit> = runCatching {
        val isNewSection = zone.uciSectionId == null
        val sectionId = zone.uciSectionId ?: uciAdd("firewall", "zone")
            ?: throw OpenWrtException.UnexpectedResponse("uci didn't return a new section id")
        try {
            uciSet(
                "firewall",
                sectionId,
                mapOf(
                    "name" to JsonPrimitive(zone.name),
                    "input" to JsonPrimitive(zone.input),
                    "output" to JsonPrimitive(zone.output),
                    "forward" to JsonPrimitive(zone.forward),
                    "masq" to JsonPrimitive(if (zone.masq) "1" else "0"),
                    "network" to JsonArray(zone.networks.map { JsonPrimitive(it) }),
                ),
            )
            uciCommit("firewall")
        } catch (e: Exception) {
            if (isNewSection) runCatching { uciDelete("firewall", sectionId) }
            throw e
        }
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun deleteFirewallZone(uciSectionId: String): Result<Unit> = runCatching {
        uciDelete("firewall", uciSectionId)
        uciCommit("firewall")
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun getTrafficRules(): Result<List<TrafficRule>> = runCatching {
        val sections = uciGetAll("firewall") ?: return@runCatching emptyList()
        sections.mapNotNull { (sectionId, section) ->
            if (section[".type"]?.jsonPrimitive?.contentOrNull != "rule") return@mapNotNull null
            section.toTrafficRule(sectionId)
        }
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun saveTrafficRule(rule: TrafficRule): Result<Unit> = runCatching {
        val isNewSection = rule.uciSectionId == null
        val sectionId = rule.uciSectionId ?: uciAdd("firewall", "rule")
            ?: throw OpenWrtException.UnexpectedResponse("uci didn't return a new section id")
        try {
            val values = buildMap {
                put("name", rule.name)
                put("enabled", if (rule.enabled) "1" else "0")
                put("src", rule.sourceZone)
                rule.destZone?.let { put("dest", it) }
                put("proto", rule.protocol)
                if (rule.destPort.isNotBlank()) put("dest_port", rule.destPort)
                put("target", rule.target)
            }
            uciSetStrings("firewall", sectionId, values)
            uciCommit("firewall")
        } catch (e: Exception) {
            if (isNewSection) runCatching { uciDelete("firewall", sectionId) }
            throw e
        }
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun deleteTrafficRule(uciSectionId: String): Result<Unit> = runCatching {
        uciDelete("firewall", uciSectionId)
        uciCommit("firewall")
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun getVlanDevices(): Result<List<VlanDevice>> = runCatching {
        val sections = uciGetAll("network") ?: return@runCatching emptyList()
        sections.mapNotNull { (sectionId, section) -> section.toVlanDeviceOrNull(sectionId) }
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun reloadFirewall(): Result<Unit> = runCatching {
        val result = sshClient().exec("/etc/init.d/firewall reload").getOrThrow()
        if (result.exitCode != 0) throw OpenWrtException.SshFailure("Firewall reload failed:\n${result.output.take(300)}")
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun getDnsRecords(): Result<List<DnsRecord>> = runCatching {
        val sections = uciGetAll("dhcp") ?: return@runCatching emptyList()
        sections.mapNotNull { (sectionId, section) -> section.toDnsRecordOrNull(sectionId) }
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun saveDnsRecord(record: DnsRecord): Result<Unit> = runCatching {
        val isNewSection = record.uciSectionId == null
        val sectionId = record.uciSectionId ?: uciAdd("dhcp", "domain")
            ?: throw OpenWrtException.UnexpectedResponse("uci didn't return a new section id")
        try {
            uciSetStrings("dhcp", sectionId, mapOf("name" to record.hostname, "ip" to record.ipAddress))
            uciCommit("dhcp")
        } catch (e: Exception) {
            if (isNewSection) runCatching { uciDelete("dhcp", sectionId) }
            throw e
        }
        // Persisted, not applied to the live dnsmasq instance — needs `/etc/init.d/dnsmasq restart`.
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun deleteDnsRecord(uciSectionId: String): Result<Unit> = runCatching {
        uciDelete("dhcp", uciSectionId)
        uciCommit("dhcp")
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun getUciConfig(config: String): Result<List<UciSection>> = runCatching {
        val sections = uciGetAll(config) ?: return@runCatching emptyList()
        sections.map { (sectionId, section) -> section.toUciSection(sectionId) }
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun setUciValues(config: String, section: String, values: Map<String, String>): Result<Unit> = runCatching {
        uciSetStrings(config, section, values)
        uciCommit(config)
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun addUciSection(config: String, type: String): Result<String> = runCatching {
        val id = uciAdd(config, type) ?: throw OpenWrtException.UnexpectedResponse("uci didn't return a new section id")
        uciCommit(config)
        id
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun deleteUciSection(config: String, section: String): Result<Unit> = runCatching {
        uciDelete(config, section)
        uciCommit(config)
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun reboot(): Result<Unit> = runCatching {
        val (status, _) = call(sessionId, "system", "reboot", JsonObject(emptyMap()))
        requireOk(status, "system.reboot")
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun getServices(): Result<List<ServiceStatus>> = runCatching {
        val (status, data) = call(sessionId, "service", "list", JsonObject(emptyMap()))
        if (status != 0 || data == null) return@runCatching emptyList()
        data.jsonObject.entries
            .map { (name, serviceJson) -> serviceJson.jsonObject.toServiceStatus(name) }
            .sortedBy { it.name }
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun startService(name: String): Result<PackageActionResult> = runCatching {
        requireValidServiceName(name)
        sshClient().exec("/etc/init.d/$name start").getOrThrow().toActionResult()
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun stopService(name: String): Result<PackageActionResult> = runCatching {
        requireValidServiceName(name)
        sshClient().exec("/etc/init.d/$name stop").getOrThrow().toActionResult()
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun restartService(name: String): Result<PackageActionResult> = runCatching {
        requireValidServiceName(name)
        sshClient().exec("/etc/init.d/$name restart").getOrThrow().toActionResult()
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun getLogs(lines: Int): Result<List<LogEntry>> = runCatching {
        val args = JsonObject(mapOf("lines" to JsonPrimitive(lines)))
        val (status, data) = call(sessionId, "log", "read", args)
        if (status != 0 || data == null) return@runCatching emptyList()
        data.jsonObject["log"]?.jsonArray.orEmpty().map { it.jsonObject.toLogEntry() }
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun getInstalledPackages(): Result<List<Package>> = runCatching {
        val installedResult = sshClient().exec("apk list --installed").getOrThrow()
        if (installedResult.exitCode != 0) {
            if (installedResult.output.contains("not found") || installedResult.exitCode == 127) {
                throw OpenWrtException.SshFailure(
                    "This router doesn't have `apk` — it's likely running OpenWrt older than 24.10 " +
                        "(which used `opkg` instead). opkg isn't supported yet.",
                )
            }
            throw OpenWrtException.SshFailure("`apk list --installed` failed:\n${installedResult.output.take(300)}")
        }
        val installed = parseApkListInstalled(installedResult.output)

        // Best-effort: an upgradable-list failure (e.g. no network, no `apk update` run yet)
        // shouldn't hide the installed list the user actually asked for.
        val upgradable = sshClient().exec("apk list --upgradable").getOrNull()
            ?.takeIf { it.exitCode == 0 }
            ?.let { parseApkListUpgradable(it.output) }
            .orEmpty()

        installed.map { pkg -> pkg.copy(availableVersion = upgradable[pkg.name]) }
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun refreshPackageLists(): Result<PackageActionResult> = runCatching {
        sshClient().exec("apk update").getOrThrow().toActionResult()
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun installPackage(name: String): Result<PackageActionResult> = runCatching {
        requireValidPackageName(name)
        sshClient().exec("apk add $name").getOrThrow().toActionResult()
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun removePackage(name: String): Result<PackageActionResult> = runCatching {
        requireValidPackageName(name)
        sshClient().exec("apk del $name").getOrThrow().toActionResult()
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun backupConfig(): Result<ByteArray> = runCatching {
        val remotePath = "/tmp/openwrtmgr-backup.tar.gz"
        val ssh = sshClient()
        val makeBackup = ssh.exec("sysupgrade -b $remotePath", timeoutSeconds = 60).getOrThrow()
        if (makeBackup.exitCode != 0) {
            throw OpenWrtException.SshFailure("Backup failed:\n${makeBackup.output.take(300)}")
        }
        val bytes = ssh.downloadFile(remotePath).getOrThrow()
        ssh.exec("rm -f $remotePath")
        bytes
    }.recoverCatching { throw it.toOpenWrtException() }

    override suspend fun restoreConfig(archive: ByteArray): Result<Unit> = runCatching {
        val remotePath = "/tmp/openwrtmgr-restore.tar.gz"
        val ssh = sshClient()
        ssh.uploadFile(remotePath, archive).getOrThrow()
        // sysupgrade -r applies the archive and reboots — the connection is expected to drop
        // before this returns cleanly on most routers, so its result is intentionally ignored:
        // a "failure" here is often actually the restore succeeding.
        ssh.exec("sysupgrade -r $remotePath", timeoutSeconds = 15)
        Unit
    }.recoverCatching { throw it.toOpenWrtException() }

    /** Package name / service name as a shell argument: reject anything but a plain apk name/version-spec. */
    private fun requireValidPackageName(name: String) {
        if (!name.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._+-]*$"))) {
            throw OpenWrtException.UnexpectedResponse("\"$name\" isn't a valid package name")
        }
    }

    private fun requireValidServiceName(name: String) {
        if (!name.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$"))) {
            throw OpenWrtException.UnexpectedResponse("\"$name\" isn't a valid service name")
        }
    }

    /** Same credentials as the ubus session (root's password), over SSH — set once by [authenticate]. */
    private fun sshClient(): SshExecClient {
        val username = lastUsername ?: throw OpenWrtException.NotAuthenticated()
        val password = lastPassword ?: throw OpenWrtException.NotAuthenticated()
        return SshExecClient(
            host = profile.host,
            port = profile.sshPort,
            username = username,
            password = password,
            knownFingerprint = profile.sshHostKeyFingerprint,
            onFingerprintLearned = onSshHostKeyLearned,
        )
    }

    /** ubus `network.wireless status` keyed by uci wifi-device section name (radio0, radio1...). */
    private suspend fun wirelessStatus(): Map<String, JsonObject>? {
        val (status, data) = call(sessionId, "network.wireless", "status", JsonObject(emptyMap()))
        if (status != 0 || data == null) return null
        return data.jsonObject.mapValues { it.value.jsonObject }
    }

    private suspend fun uciGetAll(config: String): Map<String, JsonObject>? {
        val (status, data) = call(sessionId, "uci", "get_all", JsonObject(mapOf("config" to JsonPrimitive(config))))
        if (status != 0 || data == null) return null
        return data.jsonObject["values"]?.jsonObject?.mapValues { it.value.jsonObject } ?: emptyMap()
    }

    private suspend fun uciAdd(config: String, type: String): String? {
        val args = JsonObject(mapOf("config" to JsonPrimitive(config), "type" to JsonPrimitive(type)))
        val (status, data) = call(sessionId, "uci", "add", args)
        if (status != 0) throw OpenWrtException.RouterRejected(status, "Could not create $type section in $config")
        return data?.jsonObject?.get("section")?.jsonPrimitive?.contentOrNull
    }

    private suspend fun uciSet(config: String, section: String, values: Map<String, JsonElement>) {
        val args = JsonObject(
            mapOf(
                "config" to JsonPrimitive(config),
                "section" to JsonPrimitive(section),
                "values" to JsonObject(values),
            ),
        )
        val (status, _) = call(sessionId, "uci", "set", args)
        if (status != 0) throw OpenWrtException.RouterRejected(status, "Could not update $config.$section")
    }

    private suspend fun uciSetStrings(config: String, section: String, values: Map<String, String>) =
        uciSet(config, section, values.mapValues { JsonPrimitive(it.value) })

    private suspend fun uciDelete(config: String, section: String) {
        val args = JsonObject(mapOf("config" to JsonPrimitive(config), "section" to JsonPrimitive(section)))
        val (status, _) = call(sessionId, "uci", "delete", args)
        if (status != 0) throw OpenWrtException.RouterRejected(status, "Could not delete $config.$section")
    }

    private suspend fun uciCommit(config: String) {
        val (status, _) = call(sessionId, "uci", "commit", JsonObject(mapOf("config" to JsonPrimitive(config))))
        if (status != 0) throw OpenWrtException.RouterRejected(status, "Could not commit $config")
    }

    private fun requireOk(status: Int, calledMethod: String) {
        if (status == UbusStatus.PERMISSION_DENIED) throw OpenWrtException.NotAuthenticated()
        if (status != 0) throw OpenWrtException.RouterRejected(status, "Call to $calledMethod failed")
    }

    /**
     * Performs one ubus "call" JSON-RPC request and returns (ubusStatusCode, dataElementOrNull).
     * Session expiry (procd's idle timeout, ~300s by default) shows up two different ways and
     * both trigger the same silent re-login-and-retry-once here:
     *  - the call reaches ubus but is denied: embedded status `UbusStatus.PERMISSION_DENIED` (6)
     *    inside an otherwise-successful JSON-RPC response.
     *  - uhttpd-mod-ubus rejects the session before even reaching ubus: JSON-RPC-level
     *    `error.code == -32002` ("Access denied"), thrown by [post] as [UbusSessionExpiredException].
     * `allowReauth = false` on the retry (and on the login call itself) prevents a retry loop.
     */
    private suspend fun call(
        sid: String,
        objectName: String,
        method: String,
        args: JsonObject,
        allowReauth: Boolean = true,
    ): Pair<Int, JsonElement?> = withContext(Dispatchers.IO) {
        val params = listOf(JsonPrimitive(sid), JsonPrimitive(objectName), JsonPrimitive(method), args)

        suspend fun reauthAndRetry(): Pair<Int, JsonElement?>? {
            if (!allowReauth) return null
            val username = lastUsername
            val password = lastPassword
            if (username != null && password != null && login(username, password)) {
                return call(sessionId, objectName, method, args, allowReauth = false)
            }
            return null
        }

        val result = try {
            post(rpcMethod = "call", params = params)
        } catch (e: UbusSessionExpiredException) {
            return@withContext reauthAndRetry() ?: throw OpenWrtException.NotAuthenticated()
        }
        val statusCode = (result.getOrNull(0) as? JsonPrimitive)?.long?.toInt() ?: -1

        if (statusCode == UbusStatus.PERMISSION_DENIED) {
            reauthAndRetry()?.let { return@withContext it }
        }

        val data = result.getOrNull(1)?.takeUnless { it is JsonNull }
        statusCode to data
    }

    /** ubus "list": result is a bare array of object-name strings visible to this session. */
    private suspend fun listObjects(sid: String): Set<String> = withContext(Dispatchers.IO) {
        val response = try {
            post(rpcMethod = "list", params = listOf(JsonPrimitive(sid)))
        } catch (e: UbusSessionExpiredException) {
            val username = lastUsername
            val password = lastPassword
            if (username != null && password != null && login(username, password)) {
                post(rpcMethod = "list", params = listOf(JsonPrimitive(sessionId)))
            } else {
                throw OpenWrtException.NotAuthenticated()
            }
        }
        response.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
    }

    private suspend fun post(rpcMethod: String, params: List<JsonElement>): List<JsonElement> = withContext(Dispatchers.IO) {
        val body = json.encodeToString(
            UbusRequest.serializer(),
            UbusRequest(id = requestId.getAndIncrement(), method = rpcMethod, params = params),
        )
        val request = Request.Builder()
            .url(endpoint)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val responseBody = try {
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                resp.body?.string() ?: throw IOException("Empty response body")
            }
        } catch (e: IOException) {
            throw OpenWrtException.Unreachable(profile.host, e)
        }

        val parsed = try {
            json.decodeFromString(UbusResponse.serializer(), responseBody)
        } catch (e: Exception) {
            throw OpenWrtException.UnexpectedResponse(responseBody.take(200))
        }
        parsed.error?.let {
            if (it.code == UBUS_RPC_ACCESS_DENIED) throw UbusSessionExpiredException()
            throw OpenWrtException.UnexpectedResponse("${it.code}: ${it.message}")
        }
        parsed.result ?: throw OpenWrtException.UnexpectedResponse("Missing result array")
    }

    private fun Throwable.toOpenWrtException(): OpenWrtException = when (this) {
        is OpenWrtException -> this
        is UbusSessionExpiredException -> OpenWrtException.NotAuthenticated()
        is SshHostKeyMismatchException -> OpenWrtException.SshHostKeyChanged(message ?: "SSH host key changed")
        is SshException -> OpenWrtException.SshFailure(message ?: "SSH error")
        else -> OpenWrtException.UnexpectedResponse(message ?: "Unknown error")
    }
}

internal fun parseSystemInfo(info: JsonObject, board: JsonObject): SystemInfo {
    val memory = info["memory"]?.jsonObject
    val load = info["load"]?.jsonArray.orEmpty()
        // ubus reports load as fixed-point *65536, matching /proc/loadavg scaling
        .map { it.jsonPrimitive.double / 65536.0 }
    val release = board["release"]?.jsonObject
    return SystemInfo(
        hostname = board["hostname"]?.jsonPrimitive?.contentOrNull ?: "OpenWrt",
        model = board["model"]?.jsonPrimitive?.contentOrNull ?: "Unknown model",
        openWrtVersion = release?.get("description")?.jsonPrimitive?.contentOrNull
            ?: release?.get("version")?.jsonPrimitive?.contentOrNull
            ?: "Unknown",
        kernelVersion = board["kernel"]?.jsonPrimitive?.contentOrNull ?: "Unknown",
        architecture = board["system"]?.jsonPrimitive?.contentOrNull ?: "Unknown",
        uptimeSeconds = info["uptime"]?.jsonPrimitive?.long ?: 0L,
        loadAverage = load,
        memoryTotalBytes = memory?.get("total")?.jsonPrimitive?.long ?: 0L,
        memoryFreeBytes = memory?.get("free")?.jsonPrimitive?.long ?: 0L,
    )
}

/**
 * Combines one `network.wireless status` radio entry (uci section name + admin state + SSID/mode
 * from its primary interface) with that interface's `iwinfo.info` (live signal/bitrate), which is
 * null when the radio is disabled or iwinfo isn't installed.
 */
internal fun buildWifiRadio(radioName: String, radioObj: JsonObject, iwinfo: JsonObject?): WifiRadio {
    val firstIface = radioObj["interfaces"]?.jsonArray?.firstOrNull()?.jsonObject
    val ifaceConfig = firstIface?.get("config")?.jsonObject
    val radioConfig = radioObj["config"]?.jsonObject
    val disabled = radioConfig?.get("disabled").asUciBoolean() ?: false
    return WifiRadio(
        device = radioName,
        ssid = ifaceConfig?.get("ssid")?.jsonPrimitive?.contentOrNull,
        channel = radioConfig?.get("channel")?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
        mode = ifaceConfig?.get("mode")?.jsonPrimitive?.contentOrNull,
        encryption = humanizeEncryption(ifaceConfig?.get("encryption")?.jsonPrimitive?.contentOrNull),
        isActive = !disabled,
        signalQualityPercent = iwinfo?.let { quality(it) },
        // iwinfo reports bitrate in kbit/s
        bitrateMbps = iwinfo?.get("bitrate")?.jsonPrimitive?.long?.let { it / 1000.0 },
        txPowerDbm = iwinfo?.get("txpower")?.jsonPrimitive?.int,
    )
}

/** uci bool options render as JSON booleans or as "0"/"1" strings depending on ubus/schema path — handle both. */
private fun JsonElement?.asUciBoolean(): Boolean? = when (this?.jsonPrimitive?.contentOrNull) {
    "1", "true" -> true
    "0", "false" -> false
    else -> null
}

/** uci list-type options render as a JSON array; some paths may still hand back a space-separated string. */
private fun JsonObject.stringList(key: String): List<String> = when (val value = this[key]) {
    is JsonArray -> value.mapNotNull { it.jsonPrimitive.contentOrNull }
    is JsonPrimitive -> value.contentOrNull?.split(Regex("\\s+"))?.filter { it.isNotBlank() } ?: emptyList()
    else -> emptyList()
}

/** uci `wifi-iface` `encryption` values (psk2+ccmp, sae-mixed, none, ...) → a short human label. */
internal fun humanizeEncryption(raw: String?): String {
    if (raw == null) return "Unknown"
    if (raw == "none") return "Open"
    return when (raw.substringBefore('+')) {
        "psk" -> "WPA-PSK"
        "psk2" -> "WPA2-PSK"
        "psk-mixed", "psk2-mixed" -> "WPA/WPA2-PSK"
        "sae" -> "WPA3-SAE"
        "sae-mixed" -> "WPA2/WPA3-SAE"
        "wpa" -> "WPA-EAP"
        "wpa2" -> "WPA2-EAP"
        "wpa3", "wpa3-mixed" -> "WPA3-EAP"
        "owe" -> "OWE (Enhanced Open)"
        "wep-open", "wep-shared" -> "WEP"
        else -> raw.uppercase()
    }
}

private fun quality(radio: JsonObject): Int? {
    val q = radio["quality"]?.jsonPrimitive?.int ?: return null
    val qMax = radio["quality_max"]?.jsonPrimitive?.int?.takeIf { it > 0 } ?: return null
    return (q * 100 / qMax).coerceIn(0, 100)
}

/** `iwinfo.assoclist` result entry — one currently-associated Wi-Fi client. */
internal fun JsonObject.toWifiAssociation(device: String, ssid: String?): WifiAssociation = WifiAssociation(
    macAddress = this["mac"]?.jsonPrimitive?.contentOrNull ?: "??:??:??:??:??:??",
    device = device,
    ssid = ssid,
    signalDbm = this["signal"]?.jsonPrimitive?.int,
    rxRateMbps = this["rx"]?.jsonObject?.get("rate")?.jsonPrimitive?.long?.let { it / 1000.0 },
    txRateMbps = this["tx"]?.jsonObject?.get("rate")?.jsonPrimitive?.long?.let { it / 1000.0 },
)

/** dnsmasq `/tmp/dhcp.leases` line format: `<expiry_epoch> <mac> <ip> <hostname|*> <client_id|*>`. */
internal fun parseDhcpLeases(contents: String): List<DhcpLease> =
    contents.lineSequence()
        .mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 4) return@mapNotNull null
            val expiry = parts[0].toLongOrNull() ?: return@mapNotNull null
            DhcpLease(
                macAddress = parts[1],
                ipAddress = parts[2],
                hostname = parts[3].takeUnless { it == "*" },
                expiresEpochSeconds = expiry,
            )
        }
        .toList()

/** One `config redirect` section from `uci get_all firewall`. */
internal fun JsonObject.toPortForward(sectionId: String): PortForward = PortForward(
    uciSectionId = sectionId,
    name = this["name"]?.jsonPrimitive?.contentOrNull ?: sectionId,
    enabled = this["enabled"].asUciBoolean() ?: true,
    protocol = this["proto"]?.jsonPrimitive?.contentOrNull ?: "tcp",
    externalPort = this["src_dport"]?.jsonPrimitive?.contentOrNull ?: "",
    internalIp = this["dest_ip"]?.jsonPrimitive?.contentOrNull ?: "",
    internalPort = this["dest_port"]?.jsonPrimitive?.contentOrNull ?: "",
    sourceZone = this["src"]?.jsonPrimitive?.contentOrNull ?: "wan",
    destZone = this["dest"]?.jsonPrimitive?.contentOrNull ?: "lan",
)

/** One `config zone` section from `uci get_all firewall`. */
internal fun JsonObject.toFirewallZone(sectionId: String): FirewallZone = FirewallZone(
    uciSectionId = sectionId,
    name = this["name"]?.jsonPrimitive?.contentOrNull ?: sectionId,
    input = this["input"]?.jsonPrimitive?.contentOrNull ?: "REJECT",
    output = this["output"]?.jsonPrimitive?.contentOrNull ?: "ACCEPT",
    forward = this["forward"]?.jsonPrimitive?.contentOrNull ?: "REJECT",
    masq = this["masq"].asUciBoolean() ?: false,
    networks = this.stringList("network"),
)

/** One `config rule` section from `uci get_all firewall`. */
internal fun JsonObject.toTrafficRule(sectionId: String): TrafficRule = TrafficRule(
    uciSectionId = sectionId,
    name = this["name"]?.jsonPrimitive?.contentOrNull ?: sectionId,
    enabled = this["enabled"].asUciBoolean() ?: true,
    sourceZone = this["src"]?.jsonPrimitive?.contentOrNull ?: "wan",
    destZone = this["dest"]?.jsonPrimitive?.contentOrNull,
    protocol = this["proto"]?.jsonPrimitive?.contentOrNull ?: "tcp",
    destPort = this["dest_port"]?.jsonPrimitive?.contentOrNull ?: "",
    target = this["target"]?.jsonPrimitive?.contentOrNull ?: "ACCEPT",
)

/** A `config device` section from `uci get_all network`, if it's a bridge-VLAN (802.1q/ad) device. */
internal fun JsonObject.toVlanDeviceOrNull(sectionId: String): VlanDevice? {
    if (this[".type"]?.jsonPrimitive?.contentOrNull != "device") return null
    val deviceType = this["type"]?.jsonPrimitive?.contentOrNull
    if (deviceType != "8021q" && deviceType != "8021ad") return null
    return VlanDevice(
        uciSectionId = sectionId,
        name = this["name"]?.jsonPrimitive?.contentOrNull ?: sectionId,
        type = deviceType,
        baseDevice = this["ifname"]?.jsonPrimitive?.contentOrNull,
        vlanId = this["vid"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
    )
}

/** A `config domain` section from `uci get_all dhcp` — a static dnsmasq hostname -> IP entry. */
internal fun JsonObject.toDnsRecordOrNull(sectionId: String): DnsRecord? {
    if (this[".type"]?.jsonPrimitive?.contentOrNull != "domain") return null
    val hostname = this["name"]?.jsonPrimitive?.contentOrNull ?: return null
    val ip = this["ip"]?.jsonPrimitive?.contentOrNull ?: return null
    return DnsRecord(uciSectionId = sectionId, hostname = hostname, ipAddress = ip)
}

/** Any UCI section, generically — for the raw config editor. List-type options render space-joined. */
internal fun JsonObject.toUciSection(sectionId: String): UciSection {
    val type = this[".type"]?.jsonPrimitive?.contentOrNull ?: "unknown"
    val anonymous = this[".anonymous"]?.jsonPrimitive?.boolean ?: sectionId.startsWith("cfg")
    val options = entries
        .filterNot { it.key.startsWith(".") }
        .associate { (key, value) -> key to value.toDisplayString() }
    return UciSection(id = sectionId, type = type, isAnonymous = anonymous, options = options)
}

private fun JsonElement.toDisplayString(): String = when (this) {
    is JsonArray -> joinToString(" ") { it.jsonPrimitive.contentOrNull ?: "" }
    is JsonPrimitive -> contentOrNull ?: toString()
    else -> toString()
}

/** One entry from `service list`: `{"instances": {"instance1": {"running": true, "pid": 123}, ...}}`. */
internal fun JsonObject.toServiceStatus(name: String): ServiceStatus {
    val instances = this["instances"]?.jsonObject?.values.orEmpty().map { it.jsonObject }
    val runningInstance = instances.firstOrNull { it["running"]?.jsonPrimitive?.boolean == true }
    return ServiceStatus(
        name = name,
        running = runningInstance != null,
        instanceCount = instances.size,
        pid = runningInstance?.get("pid")?.jsonPrimitive?.int,
    )
}

/** One entry from `log read`: `{"msg":"...","priority":6,"time":1700000000,...}`. */
internal fun JsonObject.toLogEntry(): LogEntry = LogEntry(
    message = this["msg"]?.jsonPrimitive?.contentOrNull ?: "",
    severity = LogSeverity.fromLevel(this["priority"]?.jsonPrimitive?.int),
    epochSeconds = this["time"]?.jsonPrimitive?.long ?: 0L,
)

private fun SshExecClient.ExecResult.toActionResult() = PackageActionResult(succeeded = exitCode == 0, output = output)

/**
 * Splits an `apk` "name-version" token at the first hyphen directly followed by a digit — apk
 * package names essentially never have a digit right after a `-`, so this is where the version
 * starts (handles both a `-r0` release suffix and a bare `-1` kernel/kmod-style suffix). Matches
 * Alpine apk-tools' documented `<name>-<version>-r<release>` token format; not verified against a
 * live 24.10+/apk-migrated router — check it against real output before relying on it further.
 */
internal fun parseApkNameVersion(token: String): Pair<String, String>? {
    val splitIndex = token.indices.firstOrNull { i -> token[i] == '-' && i + 1 < token.length && token[i + 1].isDigit() }
        ?: return null
    val name = token.substring(0, splitIndex)
    val version = token.substring(splitIndex + 1)
    return if (name.isNotBlank() && version.isNotBlank()) name to version else null
}

/** `apk list --installed` — one `<name>-<version> <arch> {<repo>} (<license>) [installed]` line each. */
internal fun parseApkListInstalled(output: String): List<Package> =
    output.lineSequence()
        .mapNotNull { line -> line.trim().substringBefore(' ').takeIf { it.isNotBlank() } }
        .mapNotNull { token -> parseApkNameVersion(token)?.let { (name, version) -> Package(name, version) } }
        .toList()

/**
 * `apk list --upgradable` — same leading token, but it's the *candidate* (new) version, e.g.
 * `apk-tools-2.14.4-r1 x86_64 {apk-tools} (GPL-2.0-only) [upgradable from: apk-tools-2.14.0-r2]`.
 */
internal fun parseApkListUpgradable(output: String): Map<String, String> =
    output.lineSequence()
        .mapNotNull { line -> line.trim().substringBefore(' ').takeIf { it.isNotBlank() } }
        .mapNotNull { token -> parseApkNameVersion(token) }
        .toMap()

internal fun JsonObject.toInterfaceInfo(): NetworkInterfaceInfo {
    val ipv4 = this["ipv4-address"]?.jsonArray?.firstOrNull()?.jsonObject?.get("address")?.jsonPrimitive?.contentOrNull
    val ipv6 = this["ipv6-address"]?.jsonArray?.firstOrNull()?.jsonObject?.get("address")?.jsonPrimitive?.contentOrNull
    return NetworkInterfaceInfo(
        name = this["interface"]?.jsonPrimitive?.contentOrNull ?: "?",
        protocol = this["proto"]?.jsonPrimitive?.contentOrNull ?: "unknown",
        device = this["device"]?.jsonPrimitive?.contentOrNull,
        isUp = this["up"]?.jsonPrimitive?.boolean ?: false,
        ipv4Address = ipv4,
        ipv6Address = ipv6,
    )
}
