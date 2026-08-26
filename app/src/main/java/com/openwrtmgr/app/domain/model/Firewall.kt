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
