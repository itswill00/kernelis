package dev.kernelis.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal GitHub Releases client for in-app updates.
 *
 * Uses the plain releases list (`/releases`) rather than `/releases/latest`, because
 * the latter excludes pre-releases — and KERNELIS ships pre-release tags first. The
 * latest non-draft release is picked, pre-release or not, which matches how the
 * project actually publishes.
 *
 * No network happens inside the pure functions: parsing and version comparison are
 * unit tested, the fetch is a thin suspend wrapper.
 */
public object UpdateChecker {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    public data class Asset(
        val name: String,
        val browser_download_url: String,
        val size: Long = 0,
    )

    @Serializable
    public data class Release(
        val tag_name: String,
        val name: String? = null,
        val body: String? = null,
        val html_url: String,
        val prerelease: Boolean = false,
        val draft: Boolean = false,
        val assets: List<Asset> = emptyList(),
    ) {
        val displayTitle: String get() = name ?: tag_name
    }

    public fun parseReleases(payload: String): List<Release> =
        runCatching { json.decodeFromString(ListSerializer(Release.serializer()), payload) }
            .getOrDefault(emptyList())

    /** Newest non-draft release; drafts are never offered for install. */
    public fun pickLatest(releases: List<Release>): Release? =
        releases.filterNot { it.draft }.maxByOrNull { versionKey(it.tag_name) }

    /** First .apk asset, preferring the debug/universal naming used by this project. */
    public fun apkAsset(release: Release): Asset? =
        release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
            .maxByOrNull { it.size }

    public fun isNewer(candidateTag: String, currentVersion: String): Boolean =
        compare(candidateTag.removePrefix("v"), currentVersion.removePrefix("v")) > 0

    /**
     * Compares two KERNELIS-style versions ("0.1.1-alpha.1").
     *
     * Rules: numeric segments compare numerically (0.10 > 0.9); a segment with an
     * identifier ("alpha") sorts *below* the same version without one, matching semver
     * pre-release semantics; equal prefixes resolve by the extra token (numeric extra
     * wins, identifier extra loses).
     */
    public fun compare(a: String, b: String): Int {
        val ta = tokenize(a)
        val tb = tokenize(b)
        val common = minOf(ta.size, tb.size)
        for (i in 0 until common) {
            val cmp = compareToken(ta[i], tb[i])
            if (cmp != 0) return cmp
        }
        // Longer tail: a numeric extra raises the version; an identifier extra lowers it.
        return when {
            ta.size == tb.size -> 0
            ta.size > tb.size -> if (ta[common].second.isEmpty()) 1 else -1
            else -> if (tb[common].second.isEmpty()) -1 else 1
        }
    }

    private fun versionKey(tag: String): Long {
        // Deterministic ordering key for pickLatest: numeric segments, prerelease last.
        val tokens = tokenize(tag.removePrefix("v"))
        var key = 0L
        for (i in 0 until 4) {
            val (isNumeric, value) = tokens.getOrElse(i) { true to "0" }
            key = key * 10_000 + if (isNumeric) (value.toLongOrNull() ?: 0) else 0
        }
        if (tokens.any { !it.first }) key -= 1
        return key
    }

    private fun compareToken(a: Pair<Boolean, String>, b: Pair<Boolean, String>): Int = when {
        a.first && b.first -> (a.second.toLongOrNull() ?: 0).compareTo(b.second.toLongOrNull() ?: 0)
        !a.first && !b.first -> a.second.compareTo(b.second)
        // Numeric beats identifier within the same position (semver rule 11 analogue).
        a.first -> 1
        else -> -1
    }

    /** "0.1.1-alpha.1" -> [(true,"0"),(true,"1"),(true,"1"),(false,"alpha"),(true,"1")] */
    internal fun tokenize(version: String): List<Pair<Boolean, String>> {
        val tokens = mutableListOf<Pair<Boolean, String>>()
        val digits = StringBuilder()
        val letters = StringBuilder()
        fun flushDigits() {
            if (digits.isNotEmpty()) {
                tokens += true to digits.toString()
                digits.clear()
            }
        }
        fun flushLetters() {
            if (letters.isNotEmpty()) {
                tokens += false to letters.toString()
                letters.clear()
            }
        }
        for (c in version) {
            when {
                c.isDigit() -> {
                    flushLetters()
                    digits.append(c)
                }
                c.isLetter() -> {
                    flushDigits()
                    letters.append(c.lowercase())
                }
                else -> {
                    flushDigits()
                    flushLetters()
                }
            }
        }
        flushDigits()
        flushLetters()
        return tokens
    }
}

/** Thin network wrapper so the check can be faked in tests and moved off main thread. */
public class ReleaseFetcher(
    public val url: String = DEFAULT_URL,
) {
    public suspend fun fetch(): Result<List<UpdateChecker.Release>> = withContext(Dispatchers.IO) {
        runCatching {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "KERNELIS-app")
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                check(code in 200..299) { "GitHub API HTTP $code" }
                UpdateChecker.parseReleases(body).also { parsed ->
                    check(parsed.isNotEmpty()) { "No releases published yet." }
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    public companion object {
        public const val DEFAULT_URL: String =
            "https://api.github.com/repos/itswill00/kernelis/releases?per_page=10"
    }
}
