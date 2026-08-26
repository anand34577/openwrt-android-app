package com.openwrtmgr.app

import android.app.Application
import com.openwrtmgr.app.core.database.AppDatabase
import com.openwrtmgr.app.core.security.CredentialStore
import com.openwrtmgr.app.data.repository.DefaultRouterRepository
import com.openwrtmgr.app.domain.repository.RouterRepository

/**
 * Hand-rolled DI container: three objects total. A framework (Hilt/Koin) earns its keep once
 * this graph grows past what a constructor call reads at a glance — not before.
 */
class OpenWrtManagerApp : Application() {
    lateinit var routerRepository: RouterRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val database = AppDatabase.get(this)
        val credentialStore = CredentialStore(this)
        routerRepository = DefaultRouterRepository(database.routerProfileDao(), credentialStore)
    }
}
