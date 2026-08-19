package de.simonroder.smartmeterscale.data

data class ScaleReading(
    val weightKg: Double,
    val bodyFatPercent: Double?,
    val bodyWaterPercent: Double?
)
