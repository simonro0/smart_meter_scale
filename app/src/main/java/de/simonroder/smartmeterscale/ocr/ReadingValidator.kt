package de.simonroder.smartmeterscale.ocr

import de.simonroder.smartmeterscale.data.ScaleReading

sealed class ValidationResult {
    object Valid : ValidationResult()
    data class Invalid(val errors: List<String>) : ValidationResult()
}

class ReadingValidator {

    fun validate(reading: ScaleReading): ValidationResult {
        val errors = mutableListOf<String>()

        if (reading.weightKg !in 20.0..300.0) {
            errors.add("Weight ${reading.weightKg} kg is outside plausible range (20–300 kg)")
        }
        reading.bodyFatPercent?.let {
            if (it !in 3.0..50.0) errors.add("Body fat $it% is outside plausible range (3–50%)")
        }
        reading.bodyWaterPercent?.let {
            if (it !in 30.0..80.0) errors.add("Body water $it% is outside plausible range (30–80%)")
        }

        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }
}
