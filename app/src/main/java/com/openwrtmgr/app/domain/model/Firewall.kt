package com.openwrtmgr.app.domain.model

/**
 * Section 17 — a `config redirect` section from `/etc/config/firewall`, the standard OpenWrt
 * port-forwarding representation (dest_port/src_dport + target `DNAT`). Read/written via the
 * `uci` ubus object, not a bespoke "port forward" API — OpenWrt has no other kind of port forward.
 */
data class PortForward(
    val uciSectionId: String? = null, // null = not yet saved
    val name: String,
    val enabled: Boolean = true,
    val protocol: String = "tcp", // tcp | udp | tcpudp
    val externalPort: String, // single port or "start-end" range, as UCI stores it
    val internalIp: String,
    val internalPort: String,
    val sourceZone: String = "wan",
    val destZone: String = "lan",
)

/**
 * A `config zone` section from `/etc/config/firewall` — one of the interface/traffic groupings
 * (lan, wan, guest, ...) traffic rules and forwardings reference by name.
 */
data class FirewallZone(
    val uciSectionId: String? = null,
    val name: String,
    val input: String = "REJECT", // ACCEPT | REJECT | DROP
    val output: String = "ACCEPT",
    val forward: String = "REJECT",
    val masq: Boolean = false,
    val networks: List<String> = emptyList(),
)

/**
 * A `config rule` section — a traffic rule (not a port forward/`redirect`): allow/block specific
 * traffic between zones, independent of NAT/destination rewriting.
 */
data class TrafficRule(
    val uciSectionId: String? = null,
    val name: String,
    val enabled: Boolean = true,
    val sourceZone: String = "wan",
    val destZone: String? = null, // null = "Device (this router)"
    val protocol: String = "tcp",
    val destPort: String = "",
    val target: String = "ACCEPT", // ACCEPT | REJECT | DROP
)

/** Read-only summary of a `config device` bridge-VLAN section — full VLAN authoring isn't built yet. */
data class VlanDevice(
    val uciSectionId: String,
    val name: String,
    val type: String,
    val baseDevice: String?,
    val vlanId: Int?,
)
