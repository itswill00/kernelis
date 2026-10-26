package dev.kernelis.core.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Version comparison drives whether users see an update prompt at all, so it is tested
 * against the exact tag shapes this project publishes, including pre-releases.
 */
class UpdateCheckerTest {

    @Test
    fun `patch bump is newer`() {
        assertThat(UpdateChecker.isNewer("v0.1.2", "0.1.1")).isTrue()
    }

    @Test
    fun `same version is not newer`() {
        assertThat(UpdateChecker.isNewer("v0.1.1", "0.1.1")).isFalse()
        assertThat(UpdateChecker.isNewer("0.1.1", "v0.1.1")).isFalse()
    }

    @Test
    fun `older tag is not newer`() {
        assertThat(UpdateChecker.isNewer("v0.1.0", "0.1.1")).isFalse()
    }

    @Test
    fun `numeric segments compare numerically not lexically`() {
        // 0.10 must beat 0.9; lexical comparison would say otherwise.
        assertThat(UpdateChecker.isNewer("v0.10.0", "0.9.0")).isTrue()
        assertThat(UpdateChecker.isNewer("v0.9.0", "0.10.0")).isFalse()
    }

    @Test
    fun `pre-release sorts below its release`() {
        assertThat(UpdateChecker.isNewer("v0.1.1", "0.1.1-alpha.1")).isTrue()
        assertThat(UpdateChecker.isNewer("v0.1.1-alpha.1", "0.1.1")).isFalse()
    }

    @Test
    fun `later pre-release beats earlier pre-release`() {
        assertThat(UpdateChecker.isNewer("v0.1.1-alpha.2", "0.1.1-alpha.1")).isTrue()
        assertThat(UpdateChecker.isNewer("v0.1.1-alpha.1", "0.1.1-alpha.2")).isFalse()
    }

    @Test
    fun `alpha is older than beta at same position`() {
        assertThat(UpdateChecker.isNewer("v1.0.0-beta.1", "1.0.0-alpha.9")).isTrue()
    }

    @Test
    fun `tokenize splits numeric and identifier segments`() {
        val tokens = UpdateChecker.tokenize("0.1.1-alpha.2")
        assertThat(tokens.map { it.second }).containsExactly("0", "1", "1", "alpha", "2").inOrder()
        assertThat(tokens.map { it.first }).containsExactly(true, true, true, false, true).inOrder()
    }

    @Test
    fun `parseReleases tolerates unknown fields and bad payloads`() {
        val payload = """
            [{"tag_name":"v0.1.1","html_url":"https://x","prerelease":true,
              "assets":[{"name":"app-debug.apk","browser_download_url":"https://x/a.apk","size":10}],
              "unknown_field":"ignored"}]
        """.trimIndent()

        val releases = UpdateChecker.parseReleases(payload)

        assertThat(releases).hasSize(1)
        assertThat(releases[0].tag_name).isEqualTo("v0.1.1")
        assertThat(UpdateChecker.parseReleases("not json")).isEmpty()
    }

    @Test
    fun `pickLatest skips drafts and picks highest version`() {
        val releases = listOf(
            UpdateChecker.Release(tag_name = "v0.1.0", html_url = "u"),
            UpdateChecker.Release(tag_name = "v0.2.0", html_url = "u", draft = true),
            UpdateChecker.Release(tag_name = "v0.1.2", html_url = "u"),
        )

        assertThat(UpdateChecker.pickLatest(releases)?.tag_name).isEqualTo("v0.1.2")
    }

    @Test
    fun `apkAsset picks the apk among other assets`() {
        val release = UpdateChecker.Release(
            tag_name = "v1",
            html_url = "u",
            assets = listOf(
                UpdateChecker.Asset("notes.txt", "https://x/n.txt", 5),
                UpdateChecker.Asset("app-debug.apk", "https://x/a.apk", 100),
            ),
        )
        assertThat(UpdateChecker.apkAsset(release)?.name).isEqualTo("app-debug.apk")
    }

    @Test
    fun `release without apk yields null asset`() {
        val release = UpdateChecker.Release(tag_name = "v1", html_url = "u")
        assertThat(UpdateChecker.apkAsset(release)).isNull()
    }
}
