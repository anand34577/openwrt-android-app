package com.openwrtmgr.app.domain.model

/** Section 6 — a saved router. Password/token never lives here; see CredentialStore. */
data class RouterProfile(
    val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int = 80,
    val useHttps: Boolean = false,
    val username: String = "root",
    val sshPort: Int = 22,
    val lastConnectedEpochMillis: Long? = null,
    /** TOFU-pinned SSH host key fingerprint (SHA256, sshj format). Null until the first SSH connection. */
    val sshHostKeyFingerprint: String? = null,
) {
    val baseUrl: String get() = "${if (useHttps) "https" else "http"}://$host:$port"
}
