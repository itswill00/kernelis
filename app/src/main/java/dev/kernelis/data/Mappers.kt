package dev.kernelis.data

import dev.kernelis.core.tuning.ConfigSnapshot
import dev.kernelis.core.tuning.KernelProfile
import dev.kernelis.core.tuning.ProfileEntry
import dev.kernelis.data.db.ProfileEntity
import dev.kernelis.data.db.SnapshotEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Serialisation boundary for profiles and snapshots.
 *
 * Payloads are JSON lists of [ProfileEntry]. Unknown fields are ignored on read so a
 * payload written by a newer build never crashes an older one, and malformed payloads
 * degrade to an empty list with the error surfaced by [decodeEntriesStrict].
 */
private val entryListSerializer = ListSerializer(ProfileEntry.serializer())

internal fun SnapshotEntity.toModel(json: Json): ConfigSnapshot = ConfigSnapshot(
    id = id,
    label = label,
    createdAtMs = createdAtMs,
    entries = decodeEntries(json, payload),
    deviceFingerprint = deviceFingerprint,
    automatic = automatic,
)

internal fun ConfigSnapshot.toEntity(json: Json): SnapshotEntity = SnapshotEntity(
    id = id,
    createdAtMs = createdAtMs,
    label = label,
    payload = json.encodeToString(entryListSerializer, entries),
    entryCount = entries.size,
    deviceFingerprint = deviceFingerprint,
    automatic = automatic,
)

internal fun ProfileEntity.toModel(json: Json): KernelProfile = KernelProfile(
    id = id,
    name = name,
    description = description,
    entries = decodeEntries(json, payload),
    builtIn = builtIn,
    createdAtMs = createdAtMs,
    updatedAtMs = updatedAtMs,
)

internal fun KernelProfile.toEntity(json: Json): ProfileEntity = ProfileEntity(
    id = id,
    name = name,
    description = description,
    payload = json.encodeToString(entryListSerializer, entries),
    createdAtMs = createdAtMs,
    updatedAtMs = updatedAtMs,
    builtIn = builtIn,
)

internal fun decodeEntries(json: Json, payload: String): List<ProfileEntry> =
    runCatching { json.decodeFromString(entryListSerializer, payload) }.getOrDefault(emptyList())
