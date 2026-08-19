package de.simonroder.smartmeterscale.ocr

import de.simonroder.smartmeterscale.data.ScaleReading

class OcrValueParser {

    companion object {
        // Physical button labels printed on the scale case, not on the display
        private val BUTTON_LABELS = setOf("ON/OFF", "UP", "DOWN", "SET", "ON", "OFF")
        private val NUMBER = Regex("""(\d+[.,]\d+|\d+)""")
        private val WEIGHT_WITH_KG = Regex("""(\d+[.,]\d+|\d+)\s*[kK][gG]""")
        private val PERCENT_VALUE = Regex("""(\d+[.,]\d+|\d+)\s*%""")
    }

    fun parse(ocrText: String): ScaleReading? {
        val cleaned = filterButtonLabels(ocrText)
        val lines = cleaned.lines()

        val weight = WEIGHT_WITH_KG.find(cleaned)
            ?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
            ?: return null

        val fat = extractFat(cleaned, lines)
        val water = extractWater(cleaned, fat)

        return ScaleReading(weight, fat, water)
    }

    private fun filterButtonLabels(text: String): String =
        text.lines()
            .filter { line -> BUTTON_LABELS.none { label -> line.trim().equals(label, ignoreCase = true) } }
            .joinToString("\n")

    private fun extractFat(cleaned: String, lines: List<String>): Double? {
        // Prefer: number on same line as "F" or on lines immediately after a standalone "F"
        for (i in lines.indices) {
            val trimmed = lines[i].trim()
            if (trimmed.equals("F", ignoreCase = true)) {
                for (j in (i + 1)..minOf(i + 3, lines.lastIndex)) {
                    NUMBER.find(lines[j])?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
                        ?.let { if (it in 3.0..50.0) return it }
                }
            }
            if (trimmed.startsWith("F", ignoreCase = true) && trimmed.length > 1) {
                NUMBER.findAll(trimmed).mapNotNull { it.groupValues[1].replace(',', '.').toDoubleOrNull() }
                    .firstOrNull { it in 3.0..50.0 }?.let { return it }
            }
        }
        // Fallback: first % value in fat range
        return PERCENT_VALUE.findAll(cleaned)
            .mapNotNull { it.groupValues[1].replace(',', '.').toDoubleOrNull() }
            .firstOrNull { it in 3.0..50.0 }
    }

    private fun extractWater(cleaned: String, fat: Double?): Double? {
        // Prefer: number near "W" label
        val lines = cleaned.lines()
        for (i in lines.indices) {
            val trimmed = lines[i].trim()
            if (trimmed.equals("W", ignoreCase = true)) {
                for (j in (i + 1)..minOf(i + 3, lines.lastIndex)) {
                    NUMBER.find(lines[j])?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
                        ?.let { if (it in 30.0..80.0) return it }
                }
            }
        }
        // Fallback: first % value in water range that differs enough from fat
        return PERCENT_VALUE.findAll(cleaned)
            .mapNotNull { it.groupValues[1].replace(',', '.').toDoubleOrNull() }
            .filter { it in 30.0..80.0 && (fat == null || Math.abs(it - fat) > 2.0) }
            .firstOrNull()
    }

    // Parses Gemini response "weight=67.8 fat=14.4 water=60.5"
    fun parseGeminiScale(text: String): ScaleReading? {
        val weight = Regex("""weight=([\d.]+)""").find(text)
            ?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val fat = Regex("""fat=([\d.]+)""").find(text)
            ?.groupValues?.get(1)?.toDoubleOrNull()
        val water = Regex("""water=([\d.]+)""").find(text)
            ?.groupValues?.get(1)?.toDoubleOrNull()
        return ScaleReading(weight, fat, water)
    }

    // Parses Gemini response for meter (plain number)
    fun parseGeminiMeter(text: String): Double? =
        NUMBER.find(text.trim())?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()

    fun parseMeterValue(ocrText: String): Double? =
        filterButtonLabels(ocrText).let { cleaned ->
            NUMBER.findAll(cleaned)
                .mapNotNull { it.groupValues[1].replace(',', '.').toDoubleOrNull() }
                .filter { it > 0 }
                .maxOrNull()
        }
}
