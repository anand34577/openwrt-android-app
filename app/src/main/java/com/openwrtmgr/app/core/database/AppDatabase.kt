package com.openwrtmgr.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration

@Database(entities = [RouterProfileEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun routerProfileDao(): RouterProfileDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** v1 -> v2: added `sshPort` for the SSH-based packages transport. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE router_profiles ADD COLUMN sshPort INTEGER NOT NULL DEFAULT 22")
            }
        }

        /** v2 -> v3: added `sshHostKeyFingerprint` for TOFU SSH host-key pinning. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE router_profiles ADD COLUMN sshHostKeyFingerprint TEXT")
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "openwrt_manager.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                .also { instance = it }
        }
    }
}
