package com.openwrtmgr.app.data.repository

import com.openwrtmgr.app.core.database.RouterProfileDao
import com.openwrtmgr.app.core.database.toDomain
import com.openwrtmgr.app.core.database.toEntity
import com.openwrtmgr.app.core.networking.OpenWrtClient
import com.openwrtmgr.app.core.networking.UbusHttpClient
import com.openwrtmgr.app.core.security.CredentialStore
import com.openwrtmgr.app.domain.model.RouterProfile
import com.openwrtmgr.app.domain.repository.RouterRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DefaultRouterRepository(
    private val dao: RouterProfileDao,
    private val credentialStore: CredentialStore,
) : RouterRepository {

    // One authenticated client per router, kept for the app's lifetime (section 36 — independent per-router state).
    // ponytail: plain in-memory map behind a mutex; promote to a real cache/eviction policy only if router count grows large.
    private val clients = mutableMapOf<Long, OpenWrtClient>()
    private val clientsLock = Mutex()

    override fun observeProfiles(): Flow<List<RouterProfile>> =
        dao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun addProfile(profile: RouterProfile, password: String): Long {
        val id = dao.upsert(profile.toEntity())
        credentialStore.savePassword(id, password)
        return id
    }

    override suspend fun deleteProfile(profile: RouterProfile) {
        dao.delete(profile.toEntity())
        credentialStore.clearPassword(profile.id)
        clientsLock.withLock { clients.remove(profile.id) }
    }

    override suspend fun markConnected(profileId: Long) {
        val entity = dao.getById(profileId) ?: return
        dao.update(entity.copy(lastConnectedEpochMillis = System.currentTimeMillis()))
    }

    override suspend fun clientFor(profileId: Long): Result<OpenWrtClient> = runCatching {
        clientsLock.withLock {
            clients[profileId]?.let { return@runCatching it }

            val entity = dao.getById(profileId) ?: error("Unknown router profile $profileId")
            val password = credentialStore.getPassword(profileId) ?: error("No saved credentials for this router")
            val client = UbusHttpClient(entity.toDomain())
            client.authenticate(entity.username, password).getOrThrow()
            clients[profileId] = client
            client
        }
    }
}
