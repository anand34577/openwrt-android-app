package com.openwrtmgr.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [RouterProfileEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun routerProfileDao(): RouterProfileDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "openwrt_manager.db")
                // ponytail: no real users/migration path yet (pre-release) — destructive fallback
                // for the sshPort column added in v2. Write a real Migration before this ships.
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
