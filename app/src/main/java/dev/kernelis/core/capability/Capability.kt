package dev.kernelis.core.capability

/**
 * The vocabulary of kernel controls KERNELIS knows how to operate.
 *
 * A [CapabilityId] is a *semantic* control, not a path. The scanner binds each id to
 * whatever path the current device happens to expose, and records why a binding
 * failed when it cannot. This separation is what allows the UI to render a
 * "Unsupported on this device" state honestly instead of hiding or faking a control.
 */
public enum class CapabilityId(
    val domain: CapabilityDomain,
    val label: String,
    /** Whether changing this value can plausibly destabilise the device. */
    val risk: Risk,
) {
    // ---- CPU -------------------------------------------------------------------
    CPU_MIN_FREQ(CapabilityDomain.CPU, "Minimum frequency", Risk.LOW),
    CPU_MAX_FREQ(CapabilityDomain.CPU, "Maximum frequency", Risk.LOW),
    CPU_GOVERNOR(CapabilityDomain.CPU, "CPU governor", Risk.MEDIUM),
    CPU_GOVERNOR_TUNABLES(CapabilityDomain.CPU, "Governor tunables", Risk.MEDIUM),
    CPU_ONLINE(CapabilityDomain.CPU, "Core online state", Risk.HIGH),
    CPU_INPUT_BOOST(CapabilityDomain.CPU, "Input boost", Risk.MEDIUM),
    CPU_BOOST_FREQ(CapabilityDomain.CPU, "Boost frequency", Risk.MEDIUM),

    // ---- GPU -------------------------------------------------------------------
    GPU_GOVERNOR(CapabilityDomain.GPU, "GPU governor", Risk.MEDIUM),
    GPU_MIN_FREQ(CapabilityDomain.GPU, "GPU minimum frequency", Risk.LOW),
    GPU_MAX_FREQ(CapabilityDomain.GPU, "GPU maximum frequency", Risk.LOW),
    GPU_RENDERER(CapabilityDomain.GPU, "GPU renderer", Risk.LOW),

    // ---- I/O -------------------------------------------------------------------
    IO_SCHEDULER(CapabilityDomain.IO, "I/O scheduler", Risk.MEDIUM),
    IO_READ_AHEAD(CapabilityDomain.IO, "Read-ahead", Risk.LOW),
    IO_NOMERGES(CapabilityDomain.IO, "Merge policy", Risk.LOW),
    IO_ROTATIONAL(CapabilityDomain.IO, "Rotational flag", Risk.LOW),

    // ---- Memory ----------------------------------------------------------------
    VM_DIRTY_RATIO(CapabilityDomain.MEMORY, "Dirty ratio", Risk.MEDIUM),
    VM_SWAPPINESS(CapabilityDomain.MEMORY, "Swappiness", Risk.LOW),
    VM_VFS_CACHE_PRESSURE(CapabilityDomain.MEMORY, "VFS cache pressure", Risk.LOW),
    ZRAM_SIZE(CapabilityDomain.MEMORY, "ZRAM size", Risk.HIGH),
    ZRAM_ALGORITHM(CapabilityDomain.MEMORY, "ZRAM compression", Risk.HIGH),

    // ---- Scheduler -------------------------------------------------------------
    SCHED_STUNE(CapabilityDomain.SCHEDULER, "Scheduler boost (stune)", Risk.MEDIUM),
    SCHED_UCLAMP(CapabilityDomain.SCHEDULER, "uClamp limits", Risk.MEDIUM),
    SCHED_TASK_UTIL_CLAMP(CapabilityDomain.SCHEDULER, "Task util clamp", Risk.LOW),

    // ---- Thermal ---------------------------------------------------------------
    THERMAL_MODE(CapabilityDomain.THERMAL, "Thermal mode", Risk.HIGH),
    THERMAL_TRIP_POINT(CapabilityDomain.THERMAL, "Trip point", Risk.HIGH),
    MSM_THERMAL(CapabilityDomain.THERMAL, "MSM thermal (Sultanxda)", Risk.HIGH),
    THERMAL_ZONES(CapabilityDomain.THERMAL, "Thermal zones", Risk.NONE),

    // ---- Monitoring-only (no writable surface; presence is still probed) -------
    CPU_FREQ_TABLE(CapabilityDomain.CPU, "Frequency table", Risk.NONE),
    CPU_TIME_IN_STATE(CapabilityDomain.CPU, "Time in state", Risk.NONE),
    GPU_LOAD(CapabilityDomain.GPU, "GPU load", Risk.NONE),
    RAM_STATS(CapabilityDomain.MEMORY, "Memory stats", Risk.NONE),
    ZRAM_STATS(CapabilityDomain.MEMORY, "ZRAM stats", Risk.NONE),
    BATTERY_CHARGE_COUNTER(CapabilityDomain.MONITORING, "Charge counter", Risk.NONE),
    BATTERY_CURRENT(CapabilityDomain.MONITORING, "Battery current", Risk.NONE),
    STORAGE_STATS(CapabilityDomain.MONITORING, "Storage stats", Risk.NONE),
    KERNEL_PSI(CapabilityDomain.MONITORING, "Pressure stall info", Risk.NONE),
}

public enum class CapabilityDomain(public val label: String) {
    CPU("CPU"),
    GPU("GPU"),
    MEMORY("Memory"),
    IO("I/O"),
    SCHEDULER("Scheduler"),
    THERMAL("Thermal"),
    MONITORING("Monitoring"),
}

/**
 * Risk classification drives the confirmation flow. It is not cosmetic: the UI uses it
 * to decide whether a change requires an explicit "I understand" acknowledgement, and
 * the repository uses it to decide whether a snapshot must be taken first.
 */
public enum class Risk {
    /** Read-only or trivially reversible. Applied immediately. */
    NONE,

    /** Reversible with a known-good previous value. Applied with rollback record. */
    LOW,

    /** Can cause temporary jank or unexpected power use. Requires snapshot. */
    MEDIUM,

    /**
     * Can make the device unstable or unresponsive. Requires snapshot, explicit
     * acknowledgement and (where the platform allows) an automatic revert timer.
     */
    HIGH,
}

/** Why a capability is or is not available. Every unsupported item carries a reason. */
public sealed interface Availability {
    public data class Supported(val detail: String? = null) : Availability

    /** Node is absent on this kernel. Not an error — simply not present. */
    public data object NotPresent : Availability

    /** Node exists but the scan could not read it (permissions, SELinux, root denied). */
    public data class Unreadable(val reason: String) : Availability

    /** Node was found but its contents are not in a shape KERNELIS understands. */
    public data class Unrecognised(val sample: String) : Availability

    /** Requires root and root is not currently granted. */
    public data object NoRoot : Availability

    val isSupported: Boolean get() = this is Supported

    val reasonText: String
        get() = when (this) {
            is Supported -> detail ?: "Available"
            NotPresent -> "Not present on this kernel"
            is Unreadable -> "Unreadable: $reason"
            is Unrecognised -> "Unrecognised format: $sample"
            NoRoot -> "Root access required"
        }
}

/**
 * One discovered control surface, bound to a concrete path.
 *
 * [units] and [valueKind] tell the UI how to render a control without device-specific
 * code. [samples] holds the enumerated options the kernel actually reported.
 */
public data class Binding(
    val capability: CapabilityId,
    val path: String,
    val availability: Availability,
    val valueKind: ValueKind = ValueKind.UNKNOWN,
    val current: String? = null,
    val samples: List<String> = emptyList(),
    val numericRange: NumericRange? = null,
    val units: String? = null,
    /** Cluster/device index when the control is per-cluster or per-block. */
    val scope: String? = null,
    /** True only when the scanner observed the node accept-type; writes still re-verify. */
    val looksWritable: Boolean = false,
) {
    val isAvailable: Boolean get() = availability.isSupported
}

public enum class ValueKind {
    ENUM,
    NUMBER,
    BOOLEAN,
    STRING,
    UNKNOWN,
}

public data class NumericRange(val min: Long, val max: Long, val step: Long = 1L) {
    public fun contains(value: Long): Boolean = value in min..max

    public fun snap(value: Long): Long {
        if (step <= 1L) return value.coerceIn(min, max)
        val stepped = min + ((value - min) / step) * step
        return stepped.coerceIn(min, max)
    }
}

/** Result of one full capability scan. Immutable so it can be a Compose state value. */
public data class CapabilitySnapshot(
    val bindings: Map<CapabilityId, List<Binding>>,
    val scannedAtEpochMs: Long,
    val rootAvailable: Boolean,
    val rootProvider: String?,
    val deviceFingerprint: String,
    val notes: List<String> = emptyList(),
) {
    public fun bindingsFor(id: CapabilityId): List<Binding> = bindings[id].orEmpty()

    public fun first(id: CapabilityId): Binding? = bindingsFor(id).firstOrNull()

    public fun supports(id: CapabilityId): Boolean = bindingsFor(id).any { it.isAvailable }

    public fun unsupported(): List<Pair<CapabilityId, Availability>> =
        CapabilityId.entries.mapNotNull { id ->
            val list = bindingsFor(id)
            when {
                list.isEmpty() -> id to Availability.NotPresent
                list.none { it.isAvailable } -> id to list.first().availability
                else -> null
            }
        }

    public val supportedCount: Int get() = CapabilityId.entries.count { supports(it) }

    public val totalCount: Int get() = CapabilityId.entries.size
}
