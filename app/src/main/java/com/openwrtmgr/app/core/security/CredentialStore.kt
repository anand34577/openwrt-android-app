package com.openwrtmgr.app.core.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Section 6/28 — router passwords, never Room, never plaintext. Backed by a Keystore-generated
 * AES256-GCM master key via Jetpack Security; the encrypted prefs file holds only ciphertext.
 */
class CredentialStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "router_credentials",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun savePassword(profileId: Long, password: String) {
        prefs.edit().putString(key(profileId), password).apply()
    }

    fun getPassword(profileId: Long): String? = prefs.getString(key(profileId), null)

    fun clearPassword(profileId: Long) {
        prefs.edit().remove(key(profileId)).apply()
    }

    private fun key(profileId: Long) = "router_$profileId"
}
