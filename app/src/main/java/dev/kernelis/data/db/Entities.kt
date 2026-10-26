package dev.kernelis.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Append-only audit log of every privileged change KERNELIS attempted.
 *
 * Both successes and failures are recorded: knowing that a write was *rejected* is as
 * important for diagnostics as knowing it succeeded. Rows are never updated or deleted
 * except by explicit user action (clearing history), so the log cannot drift from truth.
 */
@Entity(
    tableName = "change_log",
    indices = [
        Index("timestampMs"),
        Index("capability"),
        Index("operationId"),
    ],
)
public data class ChangeLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    /** Semantic control identifier; never a path. */
    val capability: String,
    val path: String,
    val scope: String?,
    val previousValue: String?,
    val requestedValue: String,
    val observedValue: String?,
    /** APPLIED / REJECTED / FAILED / REVERTED / SNAPSHOT_RESTORED */
    val status: String,
    val message: String,
    /** Groups a multi-write operation (profile apply) so it can be rolled back whole. */
    val operationId: String,
    /** Rolling back this row is possible when a previous value was captured. */
    val reversible: Boolean,
    val risk: String,
)

@Entity(
    tableName = "snapshots",
    indices = [Index("createdAtMs")],
)
public data class SnapshotEntity(
    @PrimaryKey val id: String,
    val createdAtMs: Long,
    val label: String,
    /** Serialised `List<SnapshotEntry>` (JSON). Stored as text for portability. */
    @ColumnInfo(typeAffinity = ColumnInfo.TEXT)
    val payload: String,
    val entryCount: Int,
    val deviceFingerprint: String,
    /** True when created automatically before a risky change rather than by the user. */
    val automatic: Boolean,
)

@Entity(tableName = "profiles")
public data class ProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    /** Serialised profile payload (JSON). */
    val payload: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    /** Built-in presets cannot be deleted, only duplicated. */
    val builtIn: Boolean,
)

@Dao
public interface ChangeLogDao {
    @Insert
    public suspend fun insert(entry: ChangeLogEntity): Long

    @Query("SELECT * FROM change_log ORDER BY timestampMs DESC, id DESC LIMIT :limit")
    public fun observeRecent(limit: Int): Flow<List<ChangeLogEntity>>

    @Query("SELECT * FROM change_log WHERE operationId = :operationId ORDER BY id ASC")
    public suspend fun byOperation(operationId: String): List<ChangeLogEntity>

    @Query("SELECT * FROM change_log WHERE reversible = 1 ORDER BY id DESC LIMIT :limit")
    public fun observeReversible(limit: Int): Flow<List<ChangeLogEntity>>

    @Query("SELECT COUNT(*) FROM change_log")
    public fun observeCount(): Flow<Int>

    @Query("DELETE FROM change_log")
    public suspend fun clear()

    @Query("DELETE FROM change_log WHERE timestampMs < :beforeMs")
    public suspend fun pruneBefore(beforeMs: Long)
}

@Dao
public interface SnapshotDao {
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    public suspend fun insert(snapshot: SnapshotEntity)

    @Query("SELECT * FROM snapshots ORDER BY createdAtMs DESC")
    public fun observeAll(): Flow<List<SnapshotEntity>>

    @Query("SELECT * FROM snapshots WHERE id = :id")
    public suspend fun byId(id: String): SnapshotEntity?

    @Query("SELECT * FROM snapshots WHERE automatic = 1 ORDER BY createdAtMs DESC LIMIT 1")
    public suspend fun latestAutomatic(): SnapshotEntity?

    @Query("DELETE FROM snapshots WHERE id = :id")
    public suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM snapshots WHERE automatic = 1")
    public suspend fun automaticCount(): Int
}

@Dao
public interface ProfileDao {
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    public suspend fun upsert(profile: ProfileEntity)

    @Query("SELECT * FROM profiles ORDER BY builtIn DESC, name COLLATE NOCASE ASC")
    public fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    public suspend fun byId(id: String): ProfileEntity?

    @Query("DELETE FROM profiles WHERE id = :id AND builtIn = 0")
    public suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM profiles")
    public suspend fun count(): Int
}
