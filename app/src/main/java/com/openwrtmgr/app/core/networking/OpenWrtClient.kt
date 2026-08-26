package com.openwrtmgr.app.core.networking

import com.openwrtmgr.app.domain.model.Client
import com.openwrtmgr.app.domain.model.DhcpLease
import com.openwrtmgr.app.domain.model.LogEntry
import com.openwrtmgr.app.domain.model.NetworkInterfaceInfo
import com.openwrtmgr.app.domain.model.Package
import com.openwrtmgr.app.domain.model.PackageActionResult
import com.openwrtmgr.app.domain.model.PortForward
import com.openwrtmgr.app.domain.model.RouterCapabilities
import com.openwrtmgr.app.domain.model.ServiceStatus
import com.openwrtmgr.app.domain.model.SystemInfo
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

    /** Section 11 — dangerous: severs the phone's own connection if it's on this interface. */
    suspend fun setInterfaceUp(interfaceName: String): Result<Unit>
    suspend fun setInterfaceDown(interfaceName: String): Result<Unit>

    /** Section 12 — dangerous: disabling the radio the phone is connected through disconnects it. */
    suspend fun setRadioEnabled(device: String, enabled: Boolean): Result<Unit>

    /** Section 14 — parsed from dnsmasq's lease file; empty (not an error) if dnsmasq isn't the DHCP server. */
    suspend fun getDhcpLeases(): Result<List<DhcpLease>>
    suspend fun getWifiAssociations(): Result<List<WifiAssociation>>

    /** Section 13 — DHCP leases merged with Wi-Fi associations by MAC. Convenience over the two calls above. */
    suspend fun getClients(): Result<List<Client>>

    /** Section 17 — reads `config redirect` sections from `/etc/config/firewall`. */
    suspend fun getPortForwards(): Result<List<PortForward>>

    /** Creates (uciSectionId == null) or updates an existing redirect, then applies with rollback. */
    suspend fun savePortForward(rule: PortForward): Result<Unit>
    suspend fun deletePortForward(uciSectionId: String): Result<Unit>

    /** Section 26 — dangerous, drops every client. */
    suspend fun reboot(): Result<Unit>

    /**
     * Section 19 — read-only. procd's `service` ubus object has no generic "restart any service"
     * call; real start/stop needs `/etc/init.d/<name> restart` (shell), which needs SSH (Phase 7).
     */
    suspend fun getServices(): Result<List<ServiceStatus>>

    /** Section 21 — procd's syslog ring buffer, the same source `logread` reads. */
    suspend fun getLogs(lines: Int = 200): Result<List<LogEntry>>

    /**
     * Section 20 — package management. No ubus object exists for this; every call here runs
     * `apk` over SSH (OpenWrt replaced `opkg` with Alpine's `apk` as of 24.10 — `apk` only,
     * not both). Requires SSH reachability and credentials, separate from the ubus HTTP session.
     */
    suspend fun getInstalledPackages(): Result<List<Package>>
    suspend fun refreshPackageLists(): Result<PackageActionResult>
    suspend fun installPackage(name: String): Result<PackageActionResult>
    suspend fun removePackage(name: String): Result<PackageActionResult>
}

/** Human-readable failure the UI can render directly — section 32 ("Better:" example). */
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
}
