package dev.kernelis.core.tuning

import dev.kernelis.core.capability.Binding
import dev.kernelis.core.capability.CapabilityId
import dev.kernelis.core.capability.NumericRange
import dev.kernelis.core.capability.ValueKind

/**
 * A validated request to change one kernel value.
 *
 * Constructing a [TuningRequest] does **not** touch the device. It is a description of
 * intent that must pass [TuningValidator] before [KernelWriter] will apply it.
 */
public data class TuningRequest(
    val capability: CapabilityId,
    val path: String,
    val rawValue: String,
    val scope: String? = null,
    /** Previous value captured at request time, so rollback needs no re-read. */
    val previousValue: String? = null,
)

/** Result of validating a request. Failure carries an actionable message, never a guess. */
public sealed interface ValidationResult {
    public data class Valid(val normalised: String) : ValidationResult
    public data class Invalid(val reason: String) : ValidationResult

    val isValid: Boolean get() = this is Valid
}

/**
 * Validates a proposed value against what the scanner actually observed for that node.
 *
 * Rules, in order:
 *  1. The capability must currently be available.
 *  2. The target path must be the exact path the scanner bound (no path substitution).
 *  3. The value must be typed correctly for the node's [ValueKind].
 *  4. Enumerated values must be one of the options the kernel listed.
 *  5. Numeric values must fall inside the observed range, and are snapped to a valid
 *     frequency notch when the kernel published one.
 *  6. Values are rejected if they contain anything that is not a single token — this is
 *     the last line of defence against shell injection through a profile file.
 */
public class TuningValidator {

    public fun validate(request: TuningRequest, bindings: List<Binding>): ValidationResult {
        val binding = bindings.firstOrNull { it.path == request.path }
            ?: return ValidationResult.Invalid(
                "Path ${request.path} is not a discovered ${request.capability.label} control on this device.",
            )

        if (!binding.isAvailable) {
            return ValidationResult.Invalid("${request.capability.label} is unavailable: ${binding.availability.reasonText}")
        }

        val value = request.rawValue.trim()
        if (value.isEmpty()) return ValidationResult.Invalid("Value is empty.")
        if (value.length > MAX_VALUE_LENGTH) return ValidationResult.Invalid("Value is implausibly long.")
        if (!SINGLE_TOKEN.matches(value)) {
            return ValidationResult.Invalid("Value must be a single alphanumeric token.")
        }

        return when (binding.valueKind) {
            ValueKind.ENUM -> validateEnum(binding, value)
            ValueKind.NUMBER -> validateNumber(binding, value)
            ValueKind.BOOLEAN -> validateBoolean(value)
            ValueKind.STRING -> ValidationResult.Valid(value)
            ValueKind.UNKNOWN -> ValidationResult.Invalid("This control does not report a usable value type.")
        }
    }

    private fun validateEnum(binding: Binding, value: String): ValidationResult {
        if (binding.samples.isEmpty()) {
            return ValidationResult.Invalid("The kernel did not list valid options for this control.")
        }
        if (value !in binding.samples) {
            return ValidationResult.Invalid(
                "'$value' is not offered by this kernel. Valid: ${binding.samples.joinToString(", ")}",
            )
        }
        return ValidationResult.Valid(value)
    }

    private fun validateNumber(binding: Binding, value: String): ValidationResult {
        val number = value.toLongOrNull()
            ?: return ValidationResult.Invalid("'$value' is not a whole number.")

        val range: NumericRange? = binding.numericRange
        // Frequency controls publish their exact notch table; anything else is snapped.
        val isFrequency = binding.units == "kHz" &&
            (binding.capability == CapabilityId.CPU_MIN_FREQ || binding.capability == CapabilityId.CPU_MAX_FREQ)

        if (isFrequency && binding.samples.isNotEmpty()) {
            val notches = binding.samples.mapNotNull { it.toLongOrNull() }
            val snapped = notches.minByOrNull { kotlin.math.abs(it - number) }
            if (snapped == null) return ValidationResult.Invalid("No frequency table available for this cluster.")
            if (snapped != number) {
                return ValidationResult.Valid(snapped.toString())
            }
            return ValidationResult.Valid(snapped.toString())
        }

        if (range != null && !range.contains(number)) {
            return ValidationResult.Invalid("Value must be between ${range.min} and ${range.max}.")
        }
        return ValidationResult.Valid(range?.snap(number)?.toString() ?: number.toString())
    }

    private fun validateBoolean(value: String): ValidationResult {
        val normalised = when (value.lowercase()) {
            "1", "true", "on", "y", "enabled" -> "1"
            "0", "false", "off", "n", "disabled" -> "0"
            else -> return ValidationResult.Invalid("Expected a boolean value (0 or 1).")
        }
        return ValidationResult.Valid(normalised)
    }

    private companion object {
        const val MAX_VALUE_LENGTH = 64
        /** Letters, digits, separators and a few symbols used by real sysfs values. */
        val SINGLE_TOKEN = Regex("^[A-Za-z0-9_.+:\\-/,]+$")
    }
}
