package com.openwrtmgr.app.domain.model

/** One dnsmasq DHCP lease, from `/tmp/dhcp.leases` (read via ubus `file.read` — same source LuCI uses). */
data class DhcpLease(
    val macAddress: String,
    val ipAddress: String,
    val hostname: String?,
    val expiresEpochSeconds: Long,
)

/** One entry from ubus `iwinfo.assoclist` on a given radio/interface. */
data class WifiAssociation(
    val macAddress: String,
    val device: String,
    val ssid: String?,
    val signalDbm: Int?,
    val rxRateMbps: Double?,
    val txRateMbps: Double?,
)

/**
 * Section 13 — one connected device, merging a DHCP lease with a Wi-Fi association by MAC.
 * A device can appear with only one side known (e.g. a static-IP device never leased, or a
 * wired client with no Wi-Fi record) — both sides are nullable, not assumed present.
 */
data class Client(
    val macAddress: String,
    val ipAddress: String?,
    val hostname: String?,
    val connectionType: ConnectionType,
    val wifi: WifiAssociation?,
)

enum class ConnectionType { WIRED, WIRELESS, UNKNOWN }

fun mergeClients(leases: List<DhcpLease>, associations: List<WifiAssociation>): List<Client> {
    val byMac = associations.associateBy { it.macAddress.uppercase() }
    val leaseClients = leases.map { lease ->
        val mac = lease.macAddress.uppercase()
        val assoc = byMac[mac]
        Client(
            macAddress = lease.macAddress,
            ipAddress = lease.ipAddress,
            hostname = lease.hostname?.takeUnless { it == "*" },
            connectionType = if (assoc != null) ConnectionType.WIRELESS else ConnectionType.WIRED,
            wifi = assoc,
        )
    }
    val leasedMacs = leases.map { it.macAddress.uppercase() }.toSet()
    val wifiOnlyClients = associations
        .filter { it.macAddress.uppercase() !in leasedMacs }
        .map { assoc ->
            Client(
                macAddress = assoc.macAddress,
                ipAddress = null,
                hostname = null,
                connectionType = ConnectionType.WIRELESS,
                wifi = assoc,
            )
        }
    return leaseClients + wifiOnlyClients
}
