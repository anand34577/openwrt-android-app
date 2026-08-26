package com.openwrtmgr.app.core.networking

import com.openwrtmgr.app.domain.model.Client
import com.openwrtmgr.app.domain.model.DhcpLease
import com.openwrtmgr.app.domain.model.DnsRecord
import com.openwrtmgr.app.domain.model.FirewallZone
import com.openwrtmgr.app.domain.model.LogEntry
import com.openwrtmgr.app.domain.model.NetworkInterfaceInfo
import com.openwrtmgr.app.domain.model.Package
import com.openwrtmgr.app.domain.model.PackageActionResult
import com.openwrtmgr.app.domain.model.PortForward
import com.openwrtmgr.app.domain.model.RouterCapabilities
import com.openwrtmgr.app.domain.model.ServiceStatus
import com.openwrtmgr.app.domain.model.SystemInfo
import com.openwrtmgr.app.domain.model.TrafficRule
import com.openwrtmgr.app.domain.model.UciSection
import com.openwrtmgr.app.domain.model.VlanDevice
import com.openwrtmgr.app.domain.model.WifiAssociation
import com.openwrtmgr.app.domain.model.WifiRadio

/**
 * Section 42 — the protocol-agnostic boundary. Domain/UI code depends only on this;
 * it never knows whether calls go over ubus-HTTP, SSH, or (later) rpcd-direct.
 */
interface OpenWrtClient {
    suspend fun authenticate(username: String, password: String): Result<Unit>
    suspend fun getCapabilities(): Result<RouterCapabilities>
    suspend fun getSystemInfo(): Result<SystemInfo>
    suspend fun getInterfaces(): Result<List<NetworkInterfaceInfo>>

    /** Empty list on a router with no radios or no `iwinfo` package — callers must not assume non-empty. */
    suspend fun getWifiRadios(): Result<List<WifiRadio>>

    /** Dangerous: severs the phone's own connection if it's on this interface. */
    suspend fun setInterfaceUp(interfaceName: String): Result<Unit>
    suspend fun setInterfaceDown(interfaceName: String): Result<Unit>

    /** Dangerous: disabling the radio the phone is connected through disconnects it. */
    suspend fun setRadioEnabled(device: String, enabled: Boolean): Result<Unit>

    /** Parsed from dnsmasq's lease file; empty (not an error) if dnsmasq isn't the DHCP server. */
    suspend fun getDhcpLeases(): Result<List<DhcpLease>>
    suspend fun getWifiAssociations(): Result<List<WifiAssociation>>

    /** DHCP leases merged with Wi-Fi associations by MAC. Convenience over the two calls above. */
    suspend fun getClients(): Result<List<Client>>

    /** Reads `config redirect` sections from `/etc/config/firewall`. */
    suspend fun getPortForwards(): Result<List<PortForward>>

    /** Creates (uciSectionId == null) or updates an existing redirect. Cleans up on partial failure. */
    suspend fun savePortForward(rule: PortForward): Result<Unit>
    suspend fun deletePortForward(uciSectionId: String): Result<Unit>

    /** Firewall zones (`config zone`) — network groupings traffic rules/forwardings reference. */
    suspend fun getFirewallZones(): Result<List<FirewallZone>>
    suspend fun saveFirewallZone(zone: FirewallZone): Result<Unit>
    suspend fun deleteFirewallZone(uciSectionId: String): Result<Unit>

    /** Traffic rules (`config rule`) — allow/block rules independent of NAT/port-forwarding. */
    suspend fun getTrafficRules(): Result<List<TrafficRule>>
    suspend fun saveTrafficRule(rule: TrafficRule): Result<Unit>
    suspend fun deleteTrafficRule(uciSectionId: String): Result<Unit>

    /** Read-only listing of `config device`/bridge-VLAN sections. Authoring VLANs isn't built yet. */
    suspend fun getVlanDevices(): Result<List<VlanDevice>>

    /** Restarts the firewall (`/etc/init.d/firewall reload`, over SSH) so UCI edits take live effect. */
    suspend fun reloadFirewall(): Result<Unit>

    /** dnsmasq static host records (`config domain` sections in the `dhcp` config). */
    suspend fun getDnsRecords(): Result<List<DnsRecord>>
    suspend fun saveDnsRecord(record: DnsRecord): Result<Unit>
    suspend fun deleteDnsRecord(uciSectionId: String): Result<Unit>

    /** Generic UCI access for the raw config editor — works on any config, not just the ones above. */
    suspend fun getUciConfig(config: String): Result<List<UciSection>>
    suspend fun setUciValues(config: String, section: String, values: Map<String, String>): Result<Unit>
    suspend fun addUciSection(config: String, type: String): Result<String>
    suspend fun deleteUciSection(config: String, section: String): Result<Unit>

    /** Dangerous: drops every client. */
    suspend fun reboot(): Result<Unit>

    /** Read-only status via ubus `service list`. */
    suspend fun getServices(): Result<List<ServiceStatus>>

    /** Real control via `/etc/init.d/<name> start|stop|restart` over SSH — ubus has no generic call for this. */
    suspend fun startService(name: String): Result<PackageActionResult>
    suspend fun stopService(name: String): Result<PackageActionResult>
    suspend fun restartService(name: String): Result<PackageActionResult>

    /** procd's syslog ring buffer, the same source `logread` reads. */
    suspend fun getLogs(lines: Int = 200): Result<List<LogEntry>>

    /**
     * Package management. No ubus object exists for this; every call here runs `apk` over SSH
     * (OpenWrt replaced `opkg` with Alpine's `apk` as of 24.10 — `apk` only, not both).
     */
    suspend fun getInstalledPackages(): Result<List<Package>>
    suspend fun refreshPackageLists(): Result<PackageActionResult>
    suspend fun installPackage(name: String): Result<PackageActionResult>
    suspend fun removePackage(name: String): Result<PackageActionResult>

    /**
     * `sysupgrade -b` run remotely then pulled over SFTP — the standard OpenWrt config backup
     * (a tar.gz of `/etc/config` plus everything else `sysupgrade` is told to keep).
     */
    suspend fun backupConfig(): Result<ByteArray>

    /** Pushes a previously-downloaded backup archive and applies it with `sysupgrade -r`. Reboots the router. */
    suspend fun restoreConfig(archive: ByteArray): Result<Unit>
}

/** Human-readable failure the UI can render directly. */
sealed class OpenWrtException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unreachable(host: String, cause: Throwable? = null) :
        OpenWrtException("Couldn't reach the router at $host. Check the address and that it's on the network.", cause)

    class AuthenticationFailed :
        OpenWrtException("The router rejected that username or password.")

    class NotAuthenticated :
        OpenWrtException("Session expired. Please sign in again.")

    class UnexpectedResponse(detail: String) :
        OpenWrtException("The router sent back something this app didn't expect.\n\nDetails: $detail")

    class RouterRejected(ubusStatus: Int, detail: String) :
        OpenWrtException("The router rejected this request (ubus status $ubusStatus).\n\n$detail")

    class SshFailure(detail: String) : OpenWrtException(detail)

    class SshHostKeyChanged(detail: String) : OpenWrtException(detail)
}
