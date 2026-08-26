package com.openwrtmgr.app.feature.diagnostics

import com.openwrtmgr.app.domain.model.DiagnosticResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Runs diagnostics *from the phone itself*, not the router — ping/traceroute aren't ubus calls,
 * and this is genuinely more useful for "can my phone reach X" troubleshooting than a router-side
 * check would be. A hostname/IP is a plain diagnostic argument here, but still shell-escaped
 * defensively before being handed to `ping`.
 */
object NetworkDiagnostics {

    private val SAFE_HOST = Regex("^[A-Za-z0-9.:_-]+$")

    suspend fun ping(host: String, count: Int = 4): DiagnosticResult = withContext(Dispatchers.IO) {
        if (!SAFE_HOST.matches(host)) return@withContext DiagnosticResult(false, "\"$host\" isn't a valid hostname or IP address.")
        runCatching {
            val process = ProcessBuilder("/system/bin/ping", "-c", count.toString(), "-W", "2", host)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(count * 3L + 5, TimeUnit.SECONDS)
            if (!finished) process.destroy()
            DiagnosticResult(success = finished && process.exitValue() == 0, output = output.ifBlank { "No output." })
        }.getOrElse { DiagnosticResult(false, "Couldn't run ping: ${it.message}") }
    }

    /** Most Android builds don't ship a `traceroute` binary — this is a best-effort attempt with a clear fallback message. */
    suspend fun traceroute(host: String, maxHops: Int = 20): DiagnosticResult = withContext(Dispatchers.IO) {
        if (!SAFE_HOST.matches(host)) return@withContext DiagnosticResult(false, "\"$host\" isn't a valid hostname or IP address.")
        runCatching {
            val process = ProcessBuilder("/system/bin/traceroute", "-m", maxHops.toString(), host)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) process.destroy()
            DiagnosticResult(success = finished && process.exitValue() == 0, output = output.ifBlank { "No output." })
        }.getOrElse {
            DiagnosticResult(false, "This device doesn't have a traceroute binary available (common on Android). Try ping instead.")
        }
    }

    suspend fun dnsLookup(host: String): DiagnosticResult = withContext(Dispatchers.IO) {
        runCatching {
            val addresses = InetAddress.getAllByName(host)
            DiagnosticResult(true, addresses.joinToString("\n") { "${it.hostAddress}" })
        }.getOrElse { DiagnosticResult(false, "Lookup failed: ${it.message}") }
    }
}
