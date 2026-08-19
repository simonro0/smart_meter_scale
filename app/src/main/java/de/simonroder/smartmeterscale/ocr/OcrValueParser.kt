package de.simonroder.smartmeterscale.ocr

import de.simonroder.smartmeterscale.data.ScaleReading

class OcrValueParser {

    private val weightPattern = Regex("""(\d+[.,]\d+|\d+)\s*[kK][gG]""")
    private val percentPattern = Regex("""(\d+[.,]\d+|\d+)\s*%""")

    fun parse(ocrText: String): ScaleReading? {
        val weightMatch = weightPattern.find(ocrText) ?: return null
        val weight = weightMatch.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null

        val percentages = percentPattern.findAll(ocrText)
            .mapNotNull { it.groupValues[1].replace(',', '.').toDoubleOrNull() }
            .toList()

        // body fat: typically 3–50%, body water: typically 40–80%
        // when ranges overlap, the lower value is fat, higher is water
        val bodyFat = percentages.firstOrNull { it in 3.0..50.0 }
        val bodyWater = percentages.firstOrNull { it in 40.0..80.0 && it != bodyFat }

        return ScaleReading(weight, bodyFat, bodyWater)
    }

    fun parseMeterValue(ocrText: String): Double? =
        Regex("""(\d+[.,]\d+|\d+)""").findAll(ocrText)
            .mapNotNull { it.groupValues[1].replace(',', '.').toDoubleOrNull() }
            .filter { it > 0 }
            .maxOrNull()
}
