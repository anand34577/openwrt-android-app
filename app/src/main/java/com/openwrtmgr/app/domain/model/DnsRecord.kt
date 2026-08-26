package com.openwrtmgr.app.domain.model

/**
 * A dnsmasq static DNS entry — either a `config domain` section (hostname -> IP, `dhcp` config)
 * or a `config address`/rebind entry. This app manages `domain` sections: the common "give this
 * name a fixed IP on the LAN" case.
 */
data class DnsRecord(
    val uciSectionId: String? = null,
    val hostname: String,
    val ipAddress: String,
)

/** Result of a single ping/DNS-lookup diagnostic run from the phone. */
data class DiagnosticResult(val success: Boolean, val output: String)
