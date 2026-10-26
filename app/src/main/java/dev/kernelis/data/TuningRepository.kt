package dev.kernelis.data

import dev.kernelis.core.capability.CapabilityId
import dev.kernelis.core.capability.CapabilitySnapshot
import dev.kernelis.core.capability.Risk
import dev.kernelis.core.tuning.ConfigSnapshot
import dev.kernelis.core.tuning.KernelProfile
import dev.kernelis.core.tuning.KernelWriter
import dev.kernelis.core.tuning.PlannedWrite
import dev.kernelis.core.tuning.ProfileEntry
import dev.kernelis.core.tuning.TuningRequest
import dev.kernelis.core.tuning.ValidationResult
import dev.kernelis.core.tuning.WriteOutcome
import dev.kernelis.data.db.ChangeLogDao
import dev.kernelis.data.db.ChangeLogEntity
import dev.kernelis.data.db.ProfileDao
import dev.kernelis.data.db.ProfileEntity
import dev.kernelis.data.db.SnapshotDao
import dev.kernelis.data.db.SnapshotEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executes changes and records everything that happens.
 *
 * Ordering is fixed and deliberate:
 *   1. persist a snapshot (if required),
 *   2. write each effective change and verify it,
 *   3. log every attempt — applied, rejected, failed.
 *
 * A partial failure does **not** silently continue: the result reports exactly which
 * entries applied, and the operation id allows the whole batch to be rolled back.
 */
@Singleton
public class TuningRepository @Inject constructor(
    private val writer: KernelWriter,
    private val changeLogDao: ChangeLogDao,
    private val snapshotDao: SnapshotDao,
    private val profileDao: ProfileDao,
    private val json: Json,
) {
    private val mutex = Mutex()

    public data class ExecutionReport(
        val operationId: String,
        val applied: List<ChangeLogEntity>,
        val rejected: List<ChangeLogEntity>,
        val snapshotId: String?,
        val failures: List<String>,
    ) {
        val isFullSuccess: Boolean get() = failures.isEmpty() && rejected.isEmpty()

        val summary: String
            get() = buildString {
                append("${applied.size} applied")
                if (rejected.isNotEmpty()) append(", ${rejected.size} rejected")
                if (failures.isNotEmpty()) append(", ${failures.size} failed")
            }
    }

    public suspend fun applyPlan(
        plan: dev.kernelis.core.tuning.ApplyPlan,
        acknowledgeHighRisk: Boolean,
    ): ExecutionReport = mutex.withLock {
        val effective = plan.effective
        if (effective.isEmpty()) {
            return@withLock ExecutionReport(plan.operationId, emptyList(), emptyList(), null, emptyList())
        }
        if (plan.requiresAcknowledgement && !acknowledgeHighRisk) {
            return@withLock ExecutionReport(
                operationId = plan.operationId,
                applied = emptyList(),
                rejected = emptyList(),
                snapshotId = null,
                failures = listOf("High-risk change requires explicit acknowledgement."),
            )
        }

        val snapshot = if (plan.requiresSnapshot) {
            runCatching { createSnapshotLocked("Before change ${plan.operationId.take(8)}", automatic = true) }
                .getOrElse { error ->
                    // Without a snapshot we refuse to make a risky change: recovery must be possible.
                    return@withLock ExecutionReport(
                        operationId = plan.operationId,
                        applied = emptyList(),
                        rejected = emptyList(),
                        snapshotId = null,
                        failures = listOf("Could not create a safety snapshot, so the change was not applied: ${error.message}"),
                    )
                }
        } else {
            null
        }
        val snapshotId: String? = snapshot?.id

        val applied = mutableListOf<ChangeLogEntity>()
        val rejected = mutableListOf<ChangeLogEntity>()
        val failures = mutableListOf<String>()

        for (write in effective) {
            val valid = write.validation as? ValidationResult.Valid
            if (valid == null) {
                val reason = (write.validation as? ValidationResult.Invalid)?.reason ?: "Invalid value"
                val entry = log(
                    write = write,
                    requested = write.requested,
                    observed = null,
                    status = STATUS_REJECTED,
                    message = reason,
                    operationId = plan.operationId,
                )
                rejected += entry
                continue
            }

            val request = TuningRequest(
                capability = write.capability,
                path = write.path,
                rawValue = valid.normalised,
                scope = write.scope,
                previousValue = write.current,
            )
            when (val outcome = writer.write(request)) {
                is WriteOutcome.Applied -> {
                    applied += log(
                        write = write,
                        requested = outcome.requested,
                        observed = outcome.verified,
                        status = STATUS_APPLIED,
                        message = "Verified at ${outcome.verified}",
                        operationId = plan.operationId,
                        previous = outcome.previousValue,
                    )
                }
                is WriteOutcome.NotApplied -> {
                    rejected += log(
                        write = write,
                        requested = write.requested,
                        observed = outcome.observed,
                        status = if (outcome.reverted) STATUS_REVERTED else STATUS_REJECTED,
                        message = outcome.reason,
                        operationId = plan.operationId,
                        previous = outcome.previousValue,
                    )
                    failures += "${write.capability.label}: ${outcome.reason}"
                }
                is WriteOutcome.Failed -> {
                    rejected += log(
                        write = write,
                        requested = write.requested,
                        observed = outcome.after,
                        status = STATUS_FAILED,
                        message = outcome.reason,
                        operationId = plan.operationId,
                        previous = outcome.before,
                    )
                    failures += "${write.capability.label}: ${outcome.reason}"
                }
                WriteOutcome.Busy -> {
                    failures += "${write.capability.label}: another write is in progress"
                }
            }
        }

        ExecutionReport(plan.operationId, applied, rejected, snapshotId, failures)
    }

    /** Rolls back every applied row of one operation, newest first. */
    public suspend fun rollbackOperation(operationId: String): ExecutionReport = mutex.withLock {
        val rows = changeLogDao.byOperation(operationId)
            .filter { it.status == STATUS_APPLIED && it.reversible }
            .sortedByDescending { it.id }
        val applied = mutableListOf<ChangeLogEntity>()
        val rejected = mutableListOf<ChangeLogEntity>()
        val failures = mutableListOf<String>()
        val rollbackOpId = UUID.randomUUID().toString()

        for (row in rows) {
            val previous = row.previousValue
            if (previous == null) {
                failures += "${row.capability}: no previous value recorded"
                continue
            }
            val ok = writer.attemptRevert(row.path, previous)
            val entity = ChangeLogEntity(
                timestampMs = System.currentTimeMillis(),
                capability = row.capability,
                path = row.path,
                scope = row.scope,
                previousValue = row.requestedValue,
                requestedValue = previous,
                observedValue = previous,
                status = if (ok) STATUS_APPLIED else STATUS_FAILED,
                message = if (ok) {
                    "Rolled back ${row.requestedValue} → $previous"
                } else {
                    "Rollback failed for ${row.capability}"
                },
                operationId = rollbackOpId,
                reversible = false,
                risk = row.risk,
            ).let { row2 -> row2.copy(id = changeLogDao.insert(row2)) }
            if (ok) applied += entity else {
                rejected += entity
                failures += "${row.capability}: rollback failed"
            }
        }
        ExecutionReport(rollbackOpId, applied, rejected, null, failures)
    }

    public suspend fun createSnapshot(label: String, automatic: Boolean = false): ConfigSnapshot =
        mutex.withLock { createSnapshotLocked(label, automatic) }

    private suspend fun createSnapshotLocked(label: String, automatic: Boolean): ConfigSnapshot {
        val entries = currentSnapshotEntries()
        val snapshot = ConfigSnapshot(
            id = UUID.randomUUID().toString(),
            label = label,
            createdAtMs = System.currentTimeMillis(),
            entries = entries,
            deviceFingerprint = lastFingerprint,
            automatic = automatic,
        )
        snapshotDao.insert(snapshot.toEntity(json))
        return snapshot
    }

    /**
     * Captures the current value of every available control.
     *
     * The capability snapshot is supplied by the caller through [bindCapabilities] so the
     * repository never performs its own scan (which would double the cost of a change).
     */
    private var lastCapabilities: CapabilitySnapshot? = null
    private var lastFingerprint: String = "unknown"

    public fun bindCapabilities(snapshot: CapabilitySnapshot) {
        lastCapabilities = snapshot
        lastFingerprint = snapshot.deviceFingerprint
    }

    private fun currentSnapshotEntries(): List<ProfileEntry> {
        val snapshot = lastCapabilities ?: return emptyList()
        val entries = mutableListOf<ProfileEntry>()
        for (id in WRITABLE_CAPABILITIES) {
            for (binding in snapshot.bindingsFor(id)) {
                if (!binding.isAvailable) continue
                val value = binding.current ?: continue
                if (binding.path.isBlank()) continue
                entries += ProfileEntry(capability = id, path = binding.path, value = value, scope = binding.scope)
            }
        }
        return entries
    }

    public suspend fun restoreSnapshot(snapshotId: String, planner: dev.kernelis.core.tuning.ApplyPlanner): ExecutionReport {
        val entity = snapshotDao.byId(snapshotId)
            ?: return ExecutionReport(UUID.randomUUID().toString(), emptyList(), emptyList(), null, listOf("Snapshot not found"))
        val snapshot = entity.toModel(json)
        val caps = lastCapabilities
            ?: return ExecutionReport(UUID.randomUUID().toString(), emptyList(), emptyList(), null, listOf("No capability scan available"))
        val plan = planner.plan(caps, snapshot.entries)
        val report = applyPlan(plan, acknowledgeHighRisk = true)
        // Record that a restore happened so history shows the intent, not just diffs.
        changeLogDao.insert(
            ChangeLogEntity(
                timestampMs = System.currentTimeMillis(),
                capability = "SNAPSHOT",
                path = snapshotId,
                scope = null,
                previousValue = null,
                requestedValue = snapshot.label,
                observedValue = null,
                status = STATUS_SNAPSHOT_RESTORED,
                message = "Restored snapshot '${snapshot.label}' (${report.summary})",
                operationId = report.operationId,
                reversible = false,
                risk = Risk.NONE.name,
            ),
        )
        return report
    }

    public fun observeHistory(limit: Int = 200): Flow<List<ChangeLogEntity>> = changeLogDao.observeRecent(limit)

    public fun observeSnapshots(): Flow<List<SnapshotEntity>> = snapshotDao.observeAll()

    public suspend fun deleteSnapshot(id: String) = snapshotDao.delete(id)

    public suspend fun clearHistory() = changeLogDao.clear()

    public fun observeProfiles(): Flow<List<ProfileEntity>> = profileDao.observeAll()

    public suspend fun saveProfile(profile: KernelProfile) {
        val now = System.currentTimeMillis()
        profileDao.upsert(
            profile.copy(
                builtIn = false,
                createdAtMs = if (profile.createdAtMs == 0L) now else profile.createdAtMs,
                updatedAtMs = now,
            ).toEntity(json),
        )
    }

    public suspend fun deleteProfile(id: String) = profileDao.delete(id)

    public suspend fun profileById(id: String): KernelProfile? = profileDao.byId(id)?.toModel(json)

    public suspend fun ensureBuiltInProfiles() {
        if (profileDao.count() > 0) return
        for (preset in KernelProfile.BUILT_IN) profileDao.upsert(preset.toEntity(json))
    }

    private suspend fun log(
        write: PlannedWrite,
        requested: String,
        observed: String?,
        status: String,
        message: String,
        operationId: String,
        previous: String? = write.current,
    ): ChangeLogEntity {
        val entity = ChangeLogEntity(
            timestampMs = System.currentTimeMillis(),
            capability = write.capability.name,
            path = write.path,
            scope = write.scope,
            previousValue = previous,
            requestedValue = requested,
            observedValue = observed,
            status = status,
            message = message,
            operationId = operationId,
            reversible = status == STATUS_APPLIED && previous != null,
            risk = write.risk.name,
        )
        return entity.copy(id = changeLogDao.insert(entity))
    }

    public companion object {
        public const val STATUS_APPLIED: String = "APPLIED"
        public const val STATUS_REJECTED: String = "REJECTED"
        public const val STATUS_FAILED: String = "FAILED"
        public const val STATUS_REVERTED: String = "REVERTED"
        public const val STATUS_SNAPSHOT_RESTORED: String = "SNAPSHOT_RESTORED"

        /** Capabilities that produce a restorable value in a snapshot. */
        public val WRITABLE_CAPABILITIES: List<CapabilityId> = listOf(
            CapabilityId.CPU_MIN_FREQ,
            CapabilityId.CPU_MAX_FREQ,
            CapabilityId.CPU_GOVERNOR,
            CapabilityId.GPU_GOVERNOR,
            CapabilityId.GPU_MIN_FREQ,
            CapabilityId.GPU_MAX_FREQ,
            CapabilityId.IO_SCHEDULER,
            CapabilityId.IO_READ_AHEAD,
            CapabilityId.VM_DIRTY_RATIO,
            CapabilityId.VM_SWAPPINESS,
            CapabilityId.VM_VFS_CACHE_PRESSURE,
        )
    }
}
