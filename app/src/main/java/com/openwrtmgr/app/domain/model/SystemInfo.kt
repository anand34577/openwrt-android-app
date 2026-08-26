package com.openwrtmgr.app.domain.model

/** Merges ubus `system.info` + `system.board` — the two calls every OpenWrt router answers. */
data class SystemInfo(
    val hostname: String,
    val model: String,
    val openWrtVersion: String,
    val kernelVersion: String,
    val architecture: String,
    val uptimeSeconds: Long,
    val loadAverage: List<Double>,
    val memoryTotalBytes: Long,
    val memoryFreeBytes: Long,
)

data class NetworkInterfaceInfo(
    val name: String,
    val protocol: String,
    val device: String?,
    val isUp: Boolean,
    val ipv4Address: String?,
    val ipv6Address: String?,
)

/** What this router can actually do — drives which UI sections stay visible. Section 43. */
data class RouterCapabilities(
    val availableUbusObjects: Set<String>,
) {
    fun supports(objectName: String) = objectName in availableUbusObjects
}

/** One radio, from ubus `iwinfo.info`. Section 12/43 — absent entirely on routers with no radios. */
data class WifiRadio(
    val device: String,
    val ssid: String?,
    val channel: Int?,
    val mode: String?,
    val encryption: String,
    val isActive: Boolean,
    val signalQualityPercent: Int?,
    val bitrateMbps: Double?,
    val txPowerDbm: Int?,
)
