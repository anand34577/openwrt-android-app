package com.openwrtmgr.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Every failure the UI shows. The message is written for a human. */
class RouterException(message: String, val code: Int = 0) : Exception(message)

/**
 * OpenWrt's own management transport: the same endpoints LuCI uses, so whatever LuCI can do on
 * this router, the app can too, with the same ACLs:
 *  - `/ubus` JSON-RPC (rpcd objects: system, uci, network.*, luci, luci-rpc, hostapd.*, rc, file ...)
 *  - `/cgi-bin/cgi-exec|cgi-upload|cgi-backup` (cgi-io) for streaming exec, uploads and backups.
 *
 * Sessions expire after rpcd's idle timeout; every call transparently re-logs in once.
 */
class Ubus(
    private val baseUrl: String,
    /** HTTPS only: SHA-256 of the router's certificate pinned on first connect (self-signed is normal). */
    pinnedCertSha256: String?,
    private val onCertLearned: (String) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val ids = AtomicInteger(1)
    private val loginLock = Mutex()
    @Volatile private var sid = ANON
    @Volatile private var creds: Pair<String, String>? = null
    private val denied = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Whether this session may call obj.method (false once the router has denied it). */
    fun allowed(obj: String, method: String) = "$obj.$method" !in denied

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .apply { if (baseUrl.startsWith("https")) tofu(pinnedCertSha256) }
        .build()

    /** Long-running cgi-exec (package installs, traceroute) and uploads (firmware). */
    private val slowHttp = http.newBuilder().readTimeout(10, TimeUnit.MINUTES).writeTimeout(5, TimeUnit.MINUTES).build()

    // ponytail: trust-on-first-use for the router's self-signed cert; a CA-signed cert is pinned the same way.
    private fun OkHttpClient.Builder.tofu(pin: String?) {
        var learned = pin
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val fp = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded).joinToString("") { "%02x".format(it) }
                when (learned) {
                    null -> { learned = fp; onCertLearned(fp) }
                    fp -> Unit
                    else -> throw java.security.cert.CertificateException(
                        "The router's HTTPS certificate changed since you added it. If you reflashed or reset it, edit the router and re-save to trust the new certificate.",
                    )
                }
            }
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
        sslSocketFactory(ctx.socketFactory, tm)
        hostnameVerifier { _, _ -> true } // identity is the pinned cert, not the (usually IP) hostname
    }

    suspend fun login(user: String, password: String) {
        val res = rawCall(ANON, "session", "login", buildJsonObject { put("username", user); put("password", password) })
        val status = res.statusCode()
        if (status != 0) throw RouterException("Wrong username or password.", status)
        sid = res.data()["ubus_rpc_session"].str() ?: throw RouterException("Router didn't return a session.")
        creds = user to password
    }

    private suspend fun relogin(failedSid: String) = loginLock.withLock {
        if (sid != failedSid) return@withLock // another call already refreshed it
        val (u, p) = creds ?: throw RouterException("Session expired. Sign in again.")
        login(u, p)
    }

    val sessionId: String get() = sid

    /** ubus call; throws [RouterException] on any non-zero status. */
    suspend fun call(obj: String, method: String, args: JsonObject = EMPTY): JsonObject {
        val res = callRaw(obj, method, args)
        val status = res.statusCode()
        if (status != 0) throw RouterException(describe(status, "$obj.$method"), status)
        return res.data()
    }

    /** ubus call; null on any non-zero status (missing object, ACL, no data). */
    suspend fun callOrNull(obj: String, method: String, args: JsonObject = EMPTY): JsonObject? =
        runCatching { callRaw(obj, method, args) }.getOrNull()?.takeIf { it.statusCode() == 0 }?.data()

    /**
     * An expired session and a genuine ACL denial look identical (-32002 or status 6), so on a
     * denial we ask rpcd whether the session is still alive before re-logging in. Re-logging in
     * on every ACL denial would churn sessions and break a pending apply's confirm (same-sid only).
     */
    private suspend fun callRaw(obj: String, method: String, args: JsonObject): JsonArray {
        val name = "$obj.$method"
        // Denied by ACL earlier in this session: don't pay the denial + liveness round trips again.
        if (name in denied) throw RouterException(describe(PERMISSION_DENIED, name), PERMISSION_DENIED)
        val used = sid
        val res = try { rawCall(used, obj, method, args) } catch (e: SessionExpired) { null }
        if (res != null && res.statusCode() != PERMISSION_DENIED) return res
        if (creds == null || sessionAlive(used)) {
            if (res == null || (obj != "file" && obj != "uci")) denied += name // file.* denials are per-path and uci.* per-config, not per-method
            return res ?: throw RouterException(describe(PERMISSION_DENIED, name), PERMISSION_DENIED)
        }
        relogin(used)
        return try { rawCall(sid, obj, method, args) } catch (e: SessionExpired) {
            throw RouterException(describe(PERMISSION_DENIED, "$obj.$method"), PERMISSION_DENIED)
        }
    }

    private suspend fun sessionAlive(s: String) =
        runCatching { rawCall(s, "session", "access", EMPTY).statusCode() == 0 }.getOrDefault(false)

    private suspend fun rawCall(session: String, obj: String, method: String, args: JsonObject): JsonArray {
        val body = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", ids.getAndIncrement()); put("method", "call")
            put("params", JsonArray(listOf(JsonPrimitive(session), JsonPrimitive(obj), JsonPrimitive(method), args)))
        }.toString()
        val text = send(http, Request.Builder().url("$baseUrl/ubus").post(body.toRequestBody(JSON)).build())
        if (debugLog && obj != "session") {
            android.util.Log.d("ubus", "$obj.$method $args -> " + text.replace(SECRET, "\"$1\":\"***\"").take(3500))
        }
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
            throw RouterException("Unexpected reply from the router (is this an OpenWrt LuCI address?)")
        }
        root["error"]?.jsonObject?.let { err ->
            if (err["code"].int() == -32002) throw SessionExpired()
            throw RouterException("Router error: ${err["message"].str()}")
        }
        return root["result"]?.jsonArray ?: throw RouterException("Router sent an empty reply.")
    }

    /** ubus "list": every object name visible to this session. */
    suspend fun listObjects(): List<String> {
        val body = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", ids.getAndIncrement()); put("method", "list")
            put("params", JsonArray(listOf(JsonPrimitive(sid), JsonPrimitive("*"))))
        }.toString()
        val text = send(http, Request.Builder().url("$baseUrl/ubus").post(body.toRequestBody(JSON)).build())
        return runCatching { json.parseToJsonElement(text).jsonObject["result"]!!.jsonObject.keys.sorted() }.getOrDefault(emptyList())
    }

    // ---- cgi-io ----

    /** LuCI `fs.exec_direct`: run an ACL-permitted command line, stream back stdout (and stderr if asked). */
    suspend fun cgiExec(command: String, vararg args: String, stderr: Boolean = true): String {
        val cmd = (listOf(command) + args).joinToString(" ") { it.replace("\\", "\\\\").replace(Regex("(\\s)"), "\\\\$1") }
        return cgi("cgi-exec", slowHttp) { FormBody.Builder().add("sessionid", it).add("command", cmd).add("stderr", if (stderr) "1" else "0").build() }
    }

    /** LuCI's backup download: a sysupgrade-compatible tar.gz of the configuration. */
    suspend fun downloadBackup(): ByteArray = withContext(Dispatchers.IO) {
        cgiBytes("cgi-backup") { FormBody.Builder().add("sessionid", it).build() }
    }

    /** LuCI `ui.uploadFile`: write [bytes] to [path] on the router (e.g. /tmp/firmware.bin). */
    suspend fun upload(path: String, bytes: ByteArray) {
        val reply = cgi("cgi-upload", slowHttp) {
            MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("sessionid", it)
                .addFormDataPart("filename", path)
                .addFormDataPart("filedata", path.substringAfterLast('/'), bytes.toRequestBody(OCTET))
                .build()
        }
        val obj = runCatching { json.parseToJsonElement(reply).jsonObject }.getOrNull()
        if (obj?.get("failure") != null) throw RouterException("Upload failed: ${obj["message"].str()}")
    }

    private suspend fun cgi(endpoint: String, client: OkHttpClient, body: (String) -> RequestBody): String {
        val used = sid
        return try {
            send(client, Request.Builder().url("$baseUrl/cgi-bin/$endpoint").post(body(used)).build())
        } catch (e: SessionExpired) {
            relogin(used)
            try { send(client, Request.Builder().url("$baseUrl/cgi-bin/$endpoint").post(body(sid)).build()) }
            catch (e: SessionExpired) { throw RouterException("The router refused $endpoint (permission denied).") }
        }
    }

    private suspend fun cgiBytes(endpoint: String, body: (String) -> RequestBody): ByteArray {
        suspend fun once(s: String) = withContext(Dispatchers.IO) {
            try {
                http.newCall(Request.Builder().url("$baseUrl/cgi-bin/$endpoint").post(body(s)).build()).execute().use { r ->
                    if (r.code == 403) throw SessionExpired()
                    if (!r.isSuccessful) throw RouterException("Router answered HTTP ${r.code} for $endpoint")
                    r.body!!.bytes()
                }
            } catch (e: IOException) { throw unreachable(e) }
        }
        val used = sid
        return try { once(used) } catch (e: SessionExpired) {
            relogin(used)
            try { once(sid) } catch (e: SessionExpired) { throw RouterException("The router refused $endpoint (permission denied).") }
        }
    }

    private suspend fun send(client: OkHttpClient, req: Request): String = withContext(Dispatchers.IO) {
        try {
            client.newCall(req).execute().use { r ->
                if (r.code == 403 || r.code == 401) throw SessionExpired()
                if (r.code == 404) throw RouterException("${req.url.encodedPath} not found. Is LuCI (uhttpd-mod-ubus, cgi-io) installed?")
                if (!r.isSuccessful) throw RouterException("Router answered HTTP ${r.code}")
                r.body?.string().orEmpty()
            }
        } catch (e: IOException) { throw unreachable(e) }
    }

    private fun unreachable(e: IOException) = RouterException(
        e.cause?.message?.takeIf { "certificate" in it } ?: "Can't reach the router at $baseUrl. Check you're on its network.",
    )

    private class SessionExpired : Exception()

    companion object {
        /** Debug builds log ubus replies (secrets masked) to logcat tag "ubus". */
        @Volatile var debugLog = false
        private val SECRET = Regex("\"(key|password|psk|sae_password|private_key|preshared_key|auth_secret)\"\\s*:\\s*\"[^\"]*\"")
        const val ANON = "00000000000000000000000000000000"
        const val PERMISSION_DENIED = 6
        val EMPTY = JsonObject(emptyMap())
        private val JSON = "application/json".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()

        fun describe(status: Int, what: String) = when (status) {
            2 -> "Invalid argument for $what"
            3 -> "$what isn't available on this router"
            4 -> "Not found ($what)"
            5 -> "No data ($what)"
            6 -> "Permission denied for $what"
            7 -> "Router timed out on $what"
            else -> "$what failed (ubus status $status)"
        }
    }
}

// ---- small JSON helpers shared by the data layer ----

private fun JsonArray.statusCode(): Int = (getOrNull(0) as? JsonPrimitive)?.content?.toIntOrNull() ?: -1
private fun JsonArray.data(): JsonObject = getOrNull(1) as? JsonObject ?: Ubus.EMPTY

fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
fun JsonElement?.int(): Int? = str()?.toDoubleOrNull()?.toInt()
fun JsonElement?.long(): Long? = str()?.toDoubleOrNull()?.toLong()
fun JsonElement?.dbl(): Double? = str()?.toDoubleOrNull()
fun JsonElement?.bool(): Boolean? = when (str()) { "1", "true", "yes", "on" -> true; "0", "false", "no", "off" -> false; else -> null }
fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: Ubus.EMPTY
fun JsonElement?.arr(): List<JsonElement> = (this as? JsonArray) ?: emptyList()

/** uci list option: JSON array, or a space-separated string on some paths. */
fun JsonElement?.strList(): List<String> = when (this) {
    is JsonArray -> mapNotNull { it.str() }
    is JsonPrimitive -> str()?.split(Regex("\\s+"))?.filter { it.isNotBlank() }.orEmpty()
    else -> emptyList()
}

/** Builds a ubus args object from plain Kotlin values. */
fun args(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.filter { it.second != null }.associate { it.first to it.second.toJson() })

fun Any?.toJson(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is List<*> -> JsonArray(map { it.toJson() })
    is Map<*, *> -> JsonObject(entries.associate { it.key.toString() to it.value.toJson() })
    else -> JsonPrimitive(toString())
}
