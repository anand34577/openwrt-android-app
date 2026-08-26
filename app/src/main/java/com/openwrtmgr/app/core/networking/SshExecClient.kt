package com.openwrtmgr.app.core.networking

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.IOUtils
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.xfer.InMemoryDestFile
import net.schmizz.sshj.xfer.InMemorySourceFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.PublicKey
import java.util.concurrent.TimeUnit

/**
 * Runs non-interactive commands and file transfers over SSH. Not the SSH *terminal* (that's an
 * interactive shell + emulator, a bigger separate piece of work) — this exists only because a few
 * operations OpenWrt has no ubus object for at all (package management, backup/restore) are plain
 * shell commands and SFTP transfers, same as LuCI's own pages.
 *
 * One connection per call — fine at "run one apk command" / "pull one backup" frequency, wrong
 * for a terminal.
 *
 * Host key verification is trust-on-first-use (TOFU): [knownFingerprint] is whatever was pinned
 * the last time this profile connected successfully. Null means "never connected" — the first
 * key seen is accepted and reported back via [onFingerprintLearned] so the caller can persist it.
 * Any *subsequent* mismatch is a hard failure (possible MITM, or the router was reflashed) —
 * this client will not silently re-pin.
 */
class SshExecClient(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val knownFingerprint: String? = null,
    private val onFingerprintLearned: suspend (String) -> Unit = {},
) {
    data class ExecResult(val exitCode: Int, val output: String)

    suspend fun exec(command: String, timeoutSeconds: Long = 30): Result<ExecResult> = withSshConnection { ssh ->
        ssh.startSession().use { session ->
            val cmd = session.exec(command)
            val output = IOUtils.readFully(cmd.inputStream).toString()
            cmd.join(timeoutSeconds, TimeUnit.SECONDS)
            ExecResult(exitCode = cmd.exitStatus ?: -1, output = output.trim())
        }
    }

    /** Downloads a remote file's contents over SFTP into memory. Fine for config-backup-sized archives. */
    suspend fun downloadFile(remotePath: String): Result<ByteArray> = withSshConnection { ssh ->
        ssh.newSFTPClient().use { sftp ->
            val buffer = ByteArrayOutputStream()
            sftp.get(remotePath, object : InMemoryDestFile() {
                override fun getLength(): Long = 0
                override fun getOutputStream(): OutputStream = buffer
                override fun getOutputStream(append: Boolean): OutputStream = buffer
            })
            buffer.toByteArray()
        }
    }

    /** Uploads in-memory bytes to a remote path over SFTP, overwriting anything already there. */
    suspend fun uploadFile(remotePath: String, bytes: ByteArray): Result<Unit> = withSshConnection { ssh ->
        ssh.newSFTPClient().use { sftp ->
            sftp.put(
                object : InMemorySourceFile() {
                    override fun getName(): String = remotePath.substringAfterLast('/')
                    override fun getLength(): Long = bytes.size.toLong()
                    override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
                },
                remotePath,
            )
        }
    }

    private suspend fun <T> withSshConnection(block: (SSHClient) -> T): Result<T> = withContext(Dispatchers.IO) {
        var mismatchedFingerprint: String? = null
        var learnedFingerprint: String? = null

        runCatching {
            val ssh = SSHClient()
            try {
                ssh.addHostKeyVerifier(object : HostKeyVerifier {
                    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                        val fingerprint = SecurityUtils.getFingerprint(key)
                        return when (knownFingerprint) {
                            null -> {
                                learnedFingerprint = fingerprint
                                true
                            }
                            fingerprint -> true
                            else -> {
                                mismatchedFingerprint = fingerprint
                                false
                            }
                        }
                    }

                    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
                })
                ssh.connectTimeout = 5_000
                ssh.connect(host, port)
                ssh.authPassword(username, password)
                block(ssh)
            } finally {
                if (ssh.isConnected) ssh.disconnect()
            }
        }.onSuccess {
            learnedFingerprint?.let { onFingerprintLearned(it) }
        }.recoverCatching { cause ->
            mismatchedFingerprint?.let { throw SshHostKeyMismatchException(it) }
            throw SshException(cause)
        }
    }
}

class SshException(cause: Throwable) : Exception(
    "Couldn't reach the router over SSH. Check that dropbear/sshd is running and the SSH port is correct.\n\nDetails: ${cause.message}",
    cause,
)

/**
 * The SSH host key presented doesn't match the one pinned on first connect. This is either a
 * MITM attempt or the router was reflashed/replaced — either way, refuse silently re-pinning.
 * The user must explicitly confirm (re-add the router profile) to accept the new key.
 */
class SshHostKeyMismatchException(val newFingerprint: String) : Exception(
    "The router's SSH host key has changed since you last connected. This could mean the router " +
        "was reflashed or replaced — or that something is intercepting the connection. " +
        "If you're sure this is expected, remove and re-add this router to accept the new key.",
)
