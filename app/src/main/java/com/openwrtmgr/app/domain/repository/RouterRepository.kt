package com.openwrtmgr.app.domain.repository

import com.openwrtmgr.app.core.networking.OpenWrtClient
import com.openwrtmgr.app.domain.model.RouterProfile
import kotlinx.coroutines.flow.Flow

interface RouterRepository {
    fun observeProfiles(): Flow<List<RouterProfile>>
    suspend fun addProfile(profile: RouterProfile, password: String): Long

    /** Updates a saved profile's fields; pass [password] to also change the stored credential. */
    suspend fun updateProfile(profile: RouterProfile, password: String?)
    suspend fun deleteProfile(profile: RouterProfile)
    suspend fun markConnected(profileId: Long)

    /** Returns (and lazily authenticates) the client for a saved profile. One instance per router. */
    suspend fun clientFor(profileId: Long): Result<OpenWrtClient>
}
