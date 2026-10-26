package dev.kernelis.data.cache

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.kernelis.core.capability.Availability
import dev.kernelis.core.capability.Binding
import dev.kernelis.core.capability.CapabilityId
import dev.kernelis.core.capability.CapabilitySnapshot
import dev.kernelis.core.capability.NumericRange
import dev.kernelis.core.capability.ValueKind
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.scanCacheStore by preferencesDataStore(name = "scan_cache")

/**
 * On-disk cache of the last capability scan, so a cold start shows the device's
 * controls immediately instead of re-running the whole probe.
 *
 * ## Why this is safe (and why the earlier reasoning against it was wrong)
 *
 * The first version of [dev.kernelis.domain.CapabilityService] deliberately kept the
 * cache in memory only, arguing that a disk snapshot could "claim capabilities the
 * current boot does not expose". That argument only holds for an *unvalidated*
 * snapshot. This cache is validated against two things before it is trusted:
 *
 *   - `boot_id` — changes on every boot. Tuning here is runtime-only, so a snapshot
 *     from a previous boot describes state that no longer exists.
 *   - the kernel release string — changes when the kernel changes (OTA, a flash).
 *
 * When either differs, the snapshot is discarded and a full scan runs. When both
 * match, the cached **paths and control metadata** are reused but the **values are
 * re-read fresh** by the caller (one batched read). So the cache never claims a value
 * it did not just observe: it only saves the expensive *discovery* work, which is
 * exactly the part that cannot change within a boot.
 *
 * A corrupt or unparseable cache is treated as absent, never as truth.
 */
@Singleton
public class ScanCache @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Cache payload plus the identity of the boot it belongs to. */
    @Serializable
    public data class Entry(
        val bootId: String,
        val kernelRelease: String,
        val savedAtEpochMs: Long,
        val snapshot: StoredSnapshot,
    )

    @Serializable
    public data class StoredSnapshot(
        val rootAvailable: Boolean,
        val rootProvider: String?,
        val deviceFingerprint: String,
        val notes: List<String> = emptyList(),
        val bindings: List<StoredBinding> = emptyList(),
    )

    @Serializable
    public data class StoredBinding(
        val capability: String,
        val path: String,
        val availabilityKind: String,
        val availabilityDetail: String? = null,
        val valueKind: String = "UNKNOWN",
        val current: String? = null,
        val samples: List<String> = emptyList(),
        val rangeMin: Long? = null,
        val rangeMax: Long? = null,
        val rangeStep: Long? = null,
        val units: String? = null,
        val scope: String? = null,
        val looksWritable: Boolean = false,
    )

    /**
     * Loads a snapshot only when it belongs to the current boot and kernel.
     *
     * @param bootId current boot identity, or null when it could not be read — in
     *   which case nothing is trusted and the caller must do a full scan.
     */
    public suspend fun load(bootId: String?, kernelRelease: String): CapabilitySnapshot? {
        if (bootId.isNullOrBlank()) return null
        val raw = runCatching {
            context.scanCacheStore.data.first()[KEY_PAYLOAD]
        }.getOrNull() ?: return null
        val entry = runCatching { json.decodeFromString<Entry>(raw) }.getOrNull() ?: return null
        if (entry.bootId != bootId) return null
        if (entry.kernelRelease != kernelRelease) return null
        return runCatching { entry.snapshot.toSnapshot() }.getOrNull()
    }

    public suspend fun save(
        bootId: String,
        kernelRelease: String,
        snapshot: CapabilitySnapshot,
    ) {
        if (bootId.isBlank()) return
        val entry = Entry(
            bootId = bootId,
            kernelRelease = kernelRelease,
            savedAtEpochMs = System.currentTimeMillis(),
            snapshot = snapshot.toStored(),
        )
        runCatching {
            context.scanCacheStore.edit { prefs ->
                prefs[KEY_PAYLOAD] = json.encodeToString(entry)
            }
        }
    }

    public suspend fun clear() {
        runCatching { context.scanCacheStore.edit { it.remove(KEY_PAYLOAD) } }
    }

    private companion object {
        val KEY_PAYLOAD = stringPreferencesKey("snapshot_v1")
    }
}

// --------------------------------------------------------------------- mapping

private fun CapabilitySnapshot.toStored(): ScanCache.StoredSnapshot = ScanCache.StoredSnapshot(
    rootAvailable = rootAvailable,
    rootProvider = rootProvider,
    deviceFingerprint = deviceFingerprint,
    notes = notes,
    bindings = bindings.values.flatten().map { it.toStored() },
)

private fun Binding.toStored(): ScanCache.StoredBinding = ScanCache.StoredBinding(
    capability = capability.name,
    path = path,
    availabilityKind = when (availability) {
        is Availability.Supported -> "SUPPORTED"
        Availability.NotPresent -> "NOT_PRESENT"
        is Availability.Unreadable -> "UNREADABLE"
        is Availability.Unrecognised -> "UNRECOGNISED"
        Availability.NoRoot -> "NO_ROOT"
    },
    availabilityDetail = when (availability) {
        is Availability.Supported -> availability.detail
        is Availability.Unreadable -> availability.reason
        is Availability.Unrecognised -> availability.sample
        else -> null
    },
    valueKind = valueKind.name,
    current = current,
    samples = samples,
    rangeMin = numericRange?.min,
    rangeMax = numericRange?.max,
    rangeStep = numericRange?.step,
    units = units,
    scope = scope,
    looksWritable = looksWritable,
)

private fun ScanCache.StoredSnapshot.toSnapshot(): CapabilitySnapshot {
    val mapped = bindings.mapNotNull { stored ->
        val id = CapabilityId.entries.firstOrNull { it.name == stored.capability } ?: return@mapNotNull null
        val availability = when (stored.availabilityKind) {
            "SUPPORTED" -> Availability.Supported(stored.availabilityDetail)
            "NOT_PRESENT" -> Availability.NotPresent
            "UNREADABLE" -> Availability.Unreadable(stored.availabilityDetail ?: "unreadable")
            "UNRECOGNISED" -> Availability.Unrecognised(stored.availabilityDetail ?: "?")
            "NO_ROOT" -> Availability.NoRoot
            else -> return@mapNotNull null
        }
        val kind = ValueKind.entries.firstOrNull { it.name == stored.valueKind } ?: ValueKind.UNKNOWN
        val range = if (stored.rangeMin != null && stored.rangeMax != null) {
            NumericRange(stored.rangeMin, stored.rangeMax, stored.rangeStep ?: 1L)
        } else {
            null
        }
        id to Binding(
            capability = id,
            path = stored.path,
            availability = availability,
            valueKind = kind,
            current = stored.current,
            samples = stored.samples,
            numericRange = range,
            units = stored.units,
            scope = stored.scope,
            looksWritable = stored.looksWritable,
        )
    }
    return CapabilitySnapshot(
        bindings = mapped.groupBy({ it.first }, { it.second }),
        scannedAtEpochMs = System.currentTimeMillis(),
        rootAvailable = rootAvailable,
        rootProvider = rootProvider,
        deviceFingerprint = deviceFingerprint,
        notes = notes + "Controls restored from this boot's cached scan; values were re-read.",
    )
}
