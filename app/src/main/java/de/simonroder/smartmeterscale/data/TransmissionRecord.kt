package de.simonroder.smartmeterscale.data

data class TransmissionRecord(
    val meterType: MeterType,
    val timestamp: String,
    val displayValue: String,
    val scaleWeight: Double? = null,
    val scaleBodyFat: Double? = null,
    val scaleBodyWater: Double? = null,
    val userId: String? = null,
    val meterValue: Double? = null
)
