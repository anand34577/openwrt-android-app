package com.openwrtmgr.app.domain.model

/**
 * Section 20/59 — one `apk` package. OpenWrt replaced `opkg` with Alpine's `apk` as of the
 * 24.10 release line; this app targets `apk` only, not both.
 */
data class Package(
    val name: String,
    val installedVersion: String,
    /** Non-null only when `apk list --upgradable` reports a newer version. */
    val availableVersion: String? = null,
) {
    val isUpgradable: Boolean get() = availableVersion != null && availableVersion != installedVersion
}

/** Result of running one `apk` subcommand over SSH — the router's own text, shown to the user. */
data class PackageActionResult(val succeeded: Boolean, val output: String)
