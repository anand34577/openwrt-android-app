package com.openwrtmgr.app.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.openwrtmgr.app.domain.model.RouterProfile

/** Non-secret profile fields only — password lives in CredentialStore, never here. */
@Entity(tableName = "router_profiles")
data class RouterProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int,
    val useHttps: Boolean,
    val username: String,
    val sshPort: Int = 22,
    val lastConnectedEpochMillis: Long?,
    val sshHostKeyFingerprint: String? = null,
)

fun RouterProfileEntity.toDomain() = RouterProfile(
    id = id,
    name = name,
    host = host,
    port = port,
    useHttps = useHttps,
    username = username,
    sshPort = sshPort,
    lastConnectedEpochMillis = lastConnectedEpochMillis,
    sshHostKeyFingerprint = sshHostKeyFingerprint,
)

fun RouterProfile.toEntity() = RouterProfileEntity(
    id = id,
    name = name,
    host = host,
    port = port,
    useHttps = useHttps,
    username = username,
    sshPort = sshPort,
    lastConnectedEpochMillis = lastConnectedEpochMillis,
    sshHostKeyFingerprint = sshHostKeyFingerprint,
)
