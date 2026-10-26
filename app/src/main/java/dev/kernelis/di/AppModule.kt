package dev.kernelis.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.kernelis.core.root.RootShell
import dev.kernelis.core.root.SuProcessRootShell
import dev.kernelis.core.sysfs.ShellSysFs
import dev.kernelis.core.sysfs.SysFs
import dev.kernelis.core.telemetry.TelemetryCollector
import dev.kernelis.data.db.ChangeLogDao
import dev.kernelis.data.db.KernelisDatabase
import dev.kernelis.data.db.ProfileDao
import dev.kernelis.data.db.SnapshotDao
import kotlinx.serialization.json.Json
import javax.inject.Singleton

/**
 * Application-wide wiring.
 *
 * The root boundary ([RootShell]) and the sysfs projection ([SysFs]) are the only two
 * seams a test or a future root provider needs to replace. Everything else is assembled
 * from them, which keeps device-specific behaviour out of the UI and domain layers.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideRootShell(): RootShell = SuProcessRootShell()

    @Provides
    @Singleton
    fun provideSysFs(rootShell: RootShell): SysFs = ShellSysFs(rootShell)

    @Provides
    @Singleton
    fun provideCpuTopologyScanner(sysFs: SysFs): dev.kernelis.core.capability.CpuTopologyScanner =
        dev.kernelis.core.capability.CpuTopologyScanner(sysFs)

    @Provides
    @Singleton
    fun provideCapabilityScanner(
        sysFs: SysFs,
        rootShell: RootShell,
        topologyScanner: dev.kernelis.core.capability.CpuTopologyScanner,
    ): dev.kernelis.core.capability.CapabilityScanner =
        dev.kernelis.core.capability.CapabilityScanner(sysFs, rootShell, topologyScanner)

    @Provides
    @Singleton
    fun provideTelemetryCollector(
        @ApplicationContext context: Context,
        sysFs: SysFs,
    ): TelemetryCollector = TelemetryCollector(context, sysFs)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KernelisDatabase =
        Room.databaseBuilder(context, KernelisDatabase::class.java, KernelisDatabase.NAME)
            .addMigrations(*KernelisDatabase.MIGRATIONS)
            // No destructive fallback: losing the audit log on a schema change would
            // destroy the evidence users rely on for recovery.
            .build()

    @Provides
    fun provideChangeLogDao(db: KernelisDatabase): ChangeLogDao = db.changeLogDao()

    @Provides
    fun provideSnapshotDao(db: KernelisDatabase): SnapshotDao = db.snapshotDao()

    @Provides
    fun provideProfileDao(db: KernelisDatabase): ProfileDao = db.profileDao()

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = false
    }
}
