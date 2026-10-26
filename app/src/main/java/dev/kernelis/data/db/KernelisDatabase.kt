package dev.kernelis.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ChangeLogEntity::class, SnapshotEntity::class, ProfileEntity::class],
    version = 1,
    exportSchema = true,
)
public abstract class KernelisDatabase : RoomDatabase() {
    public abstract fun changeLogDao(): ChangeLogDao
    public abstract fun snapshotDao(): SnapshotDao
    public abstract fun profileDao(): ProfileDao

    public companion object {
        public const val NAME: String = "kernelis.db"

        /**
         * Migrations are declared here and applied in order. Version 1 is the initial
         * schema; the list exists so that future versions must add a migration rather
         * than silently drop user history via `fallbackToDestructiveMigration`.
         */
        public val MIGRATIONS: Array<Migration> = emptyArray()
    }
}
