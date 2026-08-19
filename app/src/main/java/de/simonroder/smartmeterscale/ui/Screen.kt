package de.simonroder.smartmeterscale.ui

import de.simonroder.smartmeterscale.data.MeterType
import de.simonroder.smartmeterscale.data.ScaleReading

sealed class Screen {
    object Home : Screen()
    data class Camera(val meterType: MeterType) : Screen()
    object Settings : Screen()
    data class Processing(val meterType: MeterType, val imagePath: String? = null) : Screen()
    data class Result(
        val meterType: MeterType,
        val scaleReading: ScaleReading?,
        val meterValue: Double?,
        val imagePath: String?,
        val rawOcrText: String? = null,
        val capturedAt: String? = null
    ) : Screen()
}
