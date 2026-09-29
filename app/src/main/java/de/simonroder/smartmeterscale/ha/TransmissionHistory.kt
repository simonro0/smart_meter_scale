package de.simonroder.smartmeterscale.ha

import android.content.Context
import de.simonroder.smartmeterscale.data.MeterType
import de.simonroder.smartmeterscale.data.TransmissionRecord
import org.json.JSONObject
import java.time.OffsetDateTime

class TransmissionHistory(context: Context) {
    private val prefs = context.getSharedPreferences("transmission_history", Context.MODE_PRIVATE)

    fun save(record: TransmissionRecord) {
        val json = JSONObject().apply {
            put("timestamp", record.timestamp)
            put("displayValue", record.displayValue)
            record.scaleWeight?.let { put("scaleWeight", it) }
            record.scaleBodyFat?.let { put("scaleBodyFat", it) }
            record.scaleBodyWater?.let { put("scaleBodyWater", it) }
            record.userId?.let { put("userId", it) }
            record.meterValue?.let { put("meterValue", it) }
        }
        prefs.edit().putString(record.meterType.name, json.toString()).apply()
    }

    fun getLast(meterType: MeterType): TransmissionRecord? {
        val raw = prefs.getString(meterType.name, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            TransmissionRecord(
                meterType = meterType,
                timestamp = json.getString("timestamp"),
                displayValue = json.getString("displayValue"),
                scaleWeight = json.optDouble("scaleWeight").takeIf { !it.isNaN() },
                scaleBodyFat = json.optDouble("scaleBodyFat").takeIf { !it.isNaN() },
                scaleBodyWater = json.optDouble("scaleBodyWater").takeIf { !it.isNaN() },
                userId = json.optString("userId").takeIf { it.isNotEmpty() },
                meterValue = json.optDouble("meterValue").takeIf { !it.isNaN() }
            )
        }.getOrNull()
    }

    fun isSameHour(record: TransmissionRecord): Boolean = runCatching {
        val last = OffsetDateTime.parse(record.timestamp)
        val now = OffsetDateTime.now()
        last.year == now.year && last.dayOfYear == now.dayOfYear && last.hour == now.hour
    }.getOrDefault(false)
}
