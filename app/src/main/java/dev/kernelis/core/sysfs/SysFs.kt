package dev.kernelis.core.sysfs

/**
 * A stable, testable projection of a sysfs/procfs tree.
 *
 * The scanner never touches [java.io.File] directly. Everything it needs is expressed
 * here, which makes the whole discovery pipeline unit-testable against a synthetic
 * device layout (see `FakeSysFs` in tests). This is what lets KERNELIS honour
 * "never assume a node exists": a missing node is just a missing map entry.
 */
public interface SysFs {
    /** Raw contents of [path] (trimmed), or null when absent/unreadable. */
    public suspend fun read(path: String): String?

    /** True when [path] exists and is readable. */
    public suspend fun exists(path: String): Boolean

    /** Entries directly under [path]; empty when the directory is absent. */
    public suspend fun list(path: String): List<String>

    /**
     * Batch read. Callers use this to avoid one shell round-trip per node, which on a
     * rooted device is the dominant cost of a scan. Missing paths are simply absent
     * from the returned map.
     */
    public suspend fun readMany(paths: Collection<String>): Map<String, String>

    /** Access metadata for [path], used to decide whether a write is even possible. */
    public suspend fun access(path: String): PathInfo

    /**
     * Batched access probe. Default implementation loops [access] for compatibility with
     * simple fakes, but the shell implementation overrides it with a single process —
     * this is the difference between a 4-second scan and a 300 ms one.
     */
    public suspend fun accessMany(paths: Collection<String>): Map<String, PathInfo> {
        val out = LinkedHashMap<String, PathInfo>(paths.size)
        for (path in paths) out[path] = access(path)
        return out
    }

    /** Batched directory listing; same rationale as [accessMany]. */
    public suspend fun listMany(paths: Collection<String>): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>(paths.size)
        for (path in paths) out[path] = list(path)
        return out
    }
}

/** Minimal stat()-style information needed to reason about a node. */
public data class PathInfo(
    val path: String,
    val exists: Boolean,
    val canRead: Boolean,
    val canWrite: Boolean,
    val isSymlink: Boolean = false,
    val mode: String? = null,
    val owner: String? = null,
    val group: String? = null,
) {
    public companion object {
        public val MISSING: PathInfo = PathInfo(
            path = "",
            exists = false,
            canRead = false,
            canWrite = false,
        )

        public fun present(path: String, canRead: Boolean = true, canWrite: Boolean = false): PathInfo =
            PathInfo(path = path, exists = true, canRead = canRead, canWrite = canWrite)
    }
}

/** Convenience: interpret sysfs booleans ("1"/"Y"/"true"/"enabled"). */
public fun String?.asSysBool(): Boolean? = when (this?.trim()?.lowercase()) {
    "1", "y", "yes", "true", "enabled", "on" -> true
    "0", "n", "no", "false", "disabled", "off" -> false
    else -> null
}

public fun String?.asSysInt(): Int? = this?.trim()?.toIntOrNull()

public fun String?.asSysLong(): Long? = this?.trim()?.toLongOrNull()

/** Splits a whitespace/newline separated sysfs list into tokens. */
public fun String?.asSysList(): List<String> =
    this?.split('\n', ' ', '\t')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
