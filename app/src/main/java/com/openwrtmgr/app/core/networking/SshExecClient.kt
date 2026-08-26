package com.openwrtmgr.app.core.networking

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.IOUtils
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.util.concurrent.TimeUnit

/**
 * Runs a single non-interactive command over SSH and returns its exit code + combined output.
 * Not the SSH *terminal* from section 27 (that's an interactive shell + emulator, still Phase 7
 * proper) — this exists only because a few operations OpenWrt has no ubus object for at all
 * (package management, backup, firmware) are plain shell commands, same as LuCI's own pages.
 *
 * One connection per call — fine at "run one apk command" frequency, wrong for a terminal.
 *
 * ponytail: host key checking is off (PromiscuousVerifier) — same LAN-convenience tradeoff as
 * cleartext HTTP (see network_security_config.xml), but SSH MITM is a bigger prize than HTTP MITM
 * on an open LAN. Add TOFU host-key pinning (store the key on first connect, compare after)
 * before this ships for real; flagging here rather than silently shipping it.
 */
class SshExecClient(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
) {
    data class ExecResult(val exitCode: Int, val output: String)

    suspend fun exec(command: String, timeoutSeconds: Long = 30): Result<ExecResult> = withContext(Dispatchers.IO) {
        runCatching {
            val ssh = SSHClient()
            try {
                ssh.addHostKeyVerifier(PromiscuousVerifier())
                ssh.connectTimeout = 5_000
                ssh.connect(host, port)
                ssh.authPassword(username, password)
                ssh.startSession().use { session ->
                    val cmd = session.exec(command)
                    val output = IOUtils.readFully(cmd.inputStream).toString()
                    cmd.join(timeoutSeconds, TimeUnit.SECONDS)
                    ExecResult(exitCode = cmd.exitStatus ?: -1, output = output.trim())
                }
            } finally {
                if (ssh.isConnected) ssh.disconnect()
            }
        }.recoverCatching { throw SshException(it) }
    }
}

class SshException(cause: Throwable) : Exception(
    "Couldn't reach the router over SSH. Check that dropbear/sshd is running and the SSH port is correct.\n\nDetails: ${cause.message}",
    cause,
)
