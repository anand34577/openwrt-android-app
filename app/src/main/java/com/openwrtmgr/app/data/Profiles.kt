package com.openwrtmgr.app.data

import android.content.Context
import android.net.ConnectivityManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.Inet4Address
import java.util.UUID

@Serializable
data class RouterProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 80,
    val https: Boolean = false,
    val username: String = "root",
    val password: String = "",
    val certSha256: String? = null,
    val lastUsed: Long = 0,
) {
    val baseUrl get() = "${if (https) "https" else "http"}://${if (':' in host) "[$host]" else host}${if ((https && port == 443) || (!https && port == 80)) "" else ":$port"}"
}

/**
 * Saved routers, password included, in Keystore-backed EncryptedSharedPreferences — one JSON
 * blob. ponytail: a handful of profiles doesn't need a database.
 */
class Profiles(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context, "routers",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    private val json = Json { ignoreUnknownKeys = true }
    private val _all = MutableStateFlow(load())
    val all: StateFlow<List<RouterProfile>> = _all

    private fun load(): List<RouterProfile> =
        prefs.getString("list", null)?.let { runCatching { json.decodeFromString<List<RouterProfile>>(it) }.getOrNull() }.orEmpty()

    fun save(p: RouterProfile) = write(_all.value.filterNot { it.id == p.id } + p)
    fun delete(id: String) = write(_all.value.filterNot { it.id == id })
    fun get(id: String) = _all.value.firstOrNull { it.id == id }

    private fun write(list: List<RouterProfile>) {
        val sorted = list.sortedByDescending { it.lastUsed }
        prefs.edit().putString("list", json.encodeToString(sorted)).apply()
        _all.value = sorted
    }

    /** Router opened straight away at launch ("remember me"); null = show the list. */
    var autoOpen: String?
        get() = prefs.getString("autoOpen", null)?.takeIf { id -> get(id) != null }
        set(v) { prefs.edit().putString("autoOpen", v).apply() }
}

/** Holds the one live [Router] session; screens ask for it by profile id. */
class Session(private val profiles: Profiles) {
    private val lock = Mutex()
    private var current: Router? = null
    /** Passwords for profiles saved without "remember password" — this process only, never on disk. */
    private val transient = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** True when we have no password for this router and must ask the user. */
    fun needsPassword(id: String) = profiles.get(id)?.password.isNullOrEmpty() && !transient.containsKey(id)

    /** Use [password] for this run; [remember] also stores it (encrypted) in the profile. */
    fun providePassword(id: String, password: String, remember: Boolean) {
        val p = profiles.get(id) ?: return
        if (remember) { profiles.save(p.copy(password = password)); transient.remove(id) } else transient[id] = password
    }

    suspend fun connect(id: String): Router = lock.withLock {
        current?.takeIf { it.profile.id == id }?.let { return it }
        val p = profiles.get(id) ?: throw RouterException("Router not found")
        val password = p.password.ifEmpty { transient[id] ?: throw RouterException("Password required") }
        val ubus = Ubus(p.baseUrl, p.certSha256) { fp -> profiles.get(id)?.let { profiles.save(it.copy(certSha256 = fp)) } }
        try {
            ubus.login(p.username, password)
        } catch (e: RouterException) {
            transient.remove(id) // a wrong typed password must be asked for again
            throw e
        }
        // re-read: login may have just pinned the HTTPS cert into the stored profile
        profiles.save((profiles.get(id) ?: p).copy(lastUsed = System.currentTimeMillis()))
        Router(p, ubus) { pw ->
            if (profiles.get(id)?.password.isNullOrEmpty()) transient[id] = pw
            else profiles.get(id)?.let { profiles.save(it.copy(password = pw)) }
        }.also { current = it }
    }

    fun router(): Router = current ?: throw RouterException("Not connected")

    /** Leave the router; forgets an unremembered password so it's asked for next time. */
    fun disconnect(forget: Boolean = false) {
        current?.profile?.id?.let { if (forget) transient.remove(it) }
        current = null
    }

    /** Try credentials without saving anything — for the add-router form. */
    suspend fun test(p: RouterProfile): Board {
        val u = Ubus(p.baseUrl, p.certSha256)
        u.login(p.username, p.password)
        return parseBoard(u.call("system", "board"))
    }
}

/** The phone's current default gateway — almost always the router to add. */
fun gatewayAddress(context: Context): String? {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
    return lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
}
