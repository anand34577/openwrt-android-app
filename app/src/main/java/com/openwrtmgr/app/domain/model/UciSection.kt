package com.openwrtmgr.app.domain.model

/**
 * One generic UCI config section (any `config` in any `/etc/config/<name>` file), as returned by
 * `uci get_all`. The raw editor works on this rather than a typed model per config, since UCI
 * itself has no schema beyond "a type name and string/list options".
 */
data class UciSection(
    val id: String,
    val type: String,
    val isAnonymous: Boolean,
    val options: Map<String, String>,
)

/** Names of the well-known top-level configs worth exposing in a picker (not exhaustive). */
val COMMON_UCI_CONFIGS = listOf(
    "system", "network", "wireless", "firewall", "dhcp", "dropbear", "uhttpd", "luci",
)
