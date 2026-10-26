package dev.kernelis.ui.tuning

import com.google.common.truth.Truth.assertThat
import dev.kernelis.core.capability.ValueKind
import org.junit.Test

/**
 * Locks how kernel values are shown to a human.
 *
 * This is the one piece of UI logic with arithmetic in it, and getting it wrong is
 * invisible in a screenshot until a specific value happens to appear. The shipped
 * version showed raw sysfs strings ("1804800 kHz"), which no one can read or compare;
 * these tests pin the replacement, including the cases that are easy to get wrong:
 * a value that is not on a whole MHz boundary, a non-frequency unit, and a value that
 * is not numeric at all.
 */
class ValueDisplayTest {

    @Test
    fun frequency_wholeMhz_hasNoDecimals() {
        assertThat(ValueDisplay.frequency("1804800", "kHz")).isEqualTo("1804.8 MHz")
        assertThat(ValueDisplay.frequency("2208000", "kHz")).isEqualTo("2208 MHz")
        assertThat(ValueDisplay.frequency("300000", "kHz")).isEqualTo("300 MHz")
    }

    @Test
    fun frequency_underHundredMhz_keepsTwoDecimals() {
        // Below 100 MHz a whole number would lose real precision (e.g. 19.2 MHz).
        assertThat(ValueDisplay.frequency("19200", "kHz")).isEqualTo("19.20 MHz")
    }

    @Test
    fun frequency_nonKhzUnit_isNotScaled() {
        // Scaling a value whose unit is not kHz would silently change its meaning.
        assertThat(ValueDisplay.frequency("42", "°C")).isEqualTo("42 °C")
    }

    @Test
    fun frequency_nonNumeric_passesThrough() {
        assertThat(ValueDisplay.frequency("N/A", "kHz")).isEqualTo("N/A")
        assertThat(ValueDisplay.frequency(null, "kHz")).isNull()
    }

    @Test
    fun rowValue_onlyScalesNumbers() {
        // A kHz value that is an ENUM (a governor name) must not be touched.
        assertThat(ValueDisplay.rowValue("schedutil", "kHz", ValueKind.ENUM))
            .isEqualTo("schedutil kHz")
        assertThat(ValueDisplay.rowValue("1804800", "kHz", ValueKind.NUMBER))
            .isEqualTo("1804.8 MHz")
        assertThat(ValueDisplay.rowValue("1", null, ValueKind.BOOLEAN)).isEqualTo("1")
    }

    @Test
    fun sliderEndpoint_usesShortForm() {
        assertThat(ValueDisplay.mhzShort(300_000)).isEqualTo("300")
        assertThat(ValueDisplay.mhzShort(2_208_000)).isEqualTo("2208")
        assertThat(ValueDisplay.mhzShort(1_017_600)).isEqualTo("1017.6")
    }
}
