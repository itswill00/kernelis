package dev.kernelis.ui.theme

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Locks the launcher-icon geometry so a future edit cannot silently break it.
 *
 * The icon is the one asset with no runtime behaviour to test, so the only way to catch
 * a regression is to assert on the shipped XML itself. Every threshold here was a real
 * failure at some point during the design, not a hypothetical:
 *
 *  - `fillType` must be nonZero. An earlier version used evenOdd with a K contour that
 *    poked outside the body, which filled the protruding arms instead of cutting them:
 *    212 wrong pixels out of 11664, on the exact geometry that was about to ship.
 *  - The path must be already subtracted. Two contours where one is a letter is the
 *    broken shape; the correct result is two separate ink islands.
 *  - Every point must sit inside the adaptive-icon safe circle (radius 33 of 108), or
 *    the launcher crops the mark.
 *  - The K must actually be a hole: some point near the stem's centre must be outside
 *    the ink, otherwise the counter was filled in and the mark is a blob.
 */
class LauncherIconTest {

    private val drawableDir = File("src/main/res/drawable")

    private fun read(name: String): String {
        val f = File(drawableDir, name)
        assertThat(f.exists()).isTrue()
        return f.readText()
    }

    private fun pathData(xml: String): String {
        val m = Regex("""android:pathData="([^"]+)"""").find(xml)
        assertThat(m).isNotNull()
        return m!!.groupValues[1]
    }

    /** Contours as point lists, mirroring how the vector is filled. */
    private fun contours(d: String): List<List<Pair<Double, Double>>> =
        Regex("M[^M]*").findAll(d).map { seg ->
            val nums = Regex("""-?\d+\.?\d*""").findAll(seg.value).map { it.value.toDouble() }.toList()
            nums.chunked(2).map { it[0] to it[1] }
        }.toList()

    /** Even-odd point-in-polygon across all contours, which is how a path renders. */
    private fun inside(x: Double, y: Double, polys: List<List<Pair<Double, Double>>>): Boolean {
        var c = false
        for (poly in polys) {
            for (i in poly.indices) {
                val (x1, y1) = poly[i]
                val (x2, y2) = poly[(i + 1) % poly.size]
                if ((y1 <= y) != (y2 <= y)) {
                    val xin = x1 + (y - y1) * (x2 - x1) / (y2 - y1)
                    if (xin > x) c = !c
                }
            }
        }
        return c
    }

    @Test
    fun foreground_usesNonZeroFill() {
        val xml = read("ic_launcher_foreground.xml")
        // Check the attribute, not the file text: the explanatory comment legitimately
        // mentions evenOdd when describing the bug it avoids.
        val fill = Regex("""android:fillType="([^"]+)"""").find(xml)?.groupValues?.get(1)
        assertThat(fill).isEqualTo("nonZero")
    }

    @Test
    fun monochrome_usesSameSilhouette() {
        val fg = pathData(read("ic_launcher_foreground.xml"))
        val mono = pathData(read("ic_launcher_monochrome.xml"))
        assertThat(mono).isEqualTo(fg)
    }

    @Test
    fun path_isAlreadySubtracted_notLetterOverBody() {
        // Two contours: the body island and the wedge between the K's arms. If someone
        // reintroduces "body + K as two contours", the point counts change and this
        // fails, because the K contour alone had 11 points and the boolean output does
        // not reproduce it.
        val polys = contours(pathData(read("ic_launcher_foreground.xml")))
        assertThat(polys).hasSize(2)
        assertThat(polys.map { it.size }).containsExactly(14, 5)
    }

    @Test
    fun everyPoint_isInsideAdaptiveSafeCircle() {
        val polys = contours(pathData(read("ic_launcher_foreground.xml")))
        for (poly in polys) {
            for ((x, y) in poly) {
                val r = Math.hypot(x - 54.0, y - 54.0)
                assertThat(r).isLessThan(33.0)
            }
        }
    }

    @Test
    fun counter_isOpen_theKReadsAsAHole() {
        val polys = contours(pathData(read("ic_launcher_foreground.xml")))
        // Sample the middle of the K's stem and the mouth of its counter.
        assertThat(inside(54.0, 54.0, polys)).isFalse()
        assertThat(inside(54.0, 40.0, polys)).isFalse()
    }

    @Test
    fun ink_isPresent_butNotABlob() {
        val polys = contours(pathData(read("ic_launcher_foreground.xml")))
        var ink = 0
        val n = 216
        for (yi in 0 until n) {
            for (xi in 0 until n) {
                val x = (xi + 0.5) * 108.0 / n
                val y = (yi + 0.5) * 108.0 / n
                if (inside(x, y, polys)) ink++
            }
        }
        val coverage = ink.toDouble() / (n * n)
        assertThat(coverage).isGreaterThan(0.12)
        assertThat(coverage).isLessThan(0.40)
    }
}
