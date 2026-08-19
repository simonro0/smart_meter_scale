package de.simonroder.smartmeterscale.ha

import android.util.Log
import de.simonroder.smartmeterscale.data.MeterType
import de.simonroder.smartmeterscale.data.ScaleReading
import de.simonroder.smartmeterscale.data.User
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class HomeAssistantClient(private val config: HomeAssistantConfig) {

    private val client = OkHttpClient()
    private val jsonMediaType = "application/json".toMediaType()

    fun sendScaleReading(reading: ScaleReading, user: User? = null, capturedAt: String? = null) {
        val suffix = user?.entitySuffix() ?: ""
        postState("sensor.scale_weight$suffix", reading.weightKg.toString(), "kg", null, "measurement", capturedAt)
        reading.bodyFatPercent?.let {
            postState("sensor.scale_body_fat$suffix", it.toString(), "%", null, "measurement", capturedAt)
        }
        reading.bodyWaterPercent?.let {
            postState("sensor.scale_body_water$suffix", it.toString(), "%", null, "measurement", capturedAt)
        }

        if (capturedAt != null) {
            tryImportStatistic("sensor.scale_weight$suffix", "kg", reading.weightKg, capturedAt, "measurement")
            reading.bodyFatPercent?.let {
                tryImportStatistic("sensor.scale_body_fat$suffix", "%", it, capturedAt, "measurement")
            }
            reading.bodyWaterPercent?.let {
                tryImportStatistic("sensor.scale_body_water$suffix", "%", it, capturedAt, "measurement")
            }
        }
    }

    fun sendMeterReading(value: Double, meterType: MeterType, capturedAt: String? = null) {
        postState(
            "sensor.${meterType.entityBase}",
            value.toString(),
            meterType.unit,
            meterType.deviceClass,
            meterType.stateClass,
            capturedAt
        )
        if (capturedAt != null) {
            tryImportStatistic("sensor.${meterType.entityBase}", meterType.unit, value, capturedAt, meterType.stateClass)
        }
    }

    private fun postState(
        entityId: String,
        state: String,
        unit: String,
        deviceClass: String?,
        stateClass: String,
        capturedAt: String? = null
    ) {
        val attributes = JSONObject().apply {
            put("unit_of_measurement", unit)
            put("friendly_name", entityId.removePrefix("sensor.").replace('_', ' ')
                .replaceFirstChar { it.uppercase() })
            put("state_class", stateClass)
            deviceClass?.let { put("device_class", it) }
            capturedAt?.let { put("photo_taken", it) }
        }
        val body = JSONObject().apply {
            put("state", state)
            put("attributes", attributes)
        }.toString().toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("${config.baseUrl}/api/states/$entityId")
            .header("Authorization", "Bearer ${config.token}")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HA API error ${response.code}: ${response.body?.string()}")
            }
        }
    }

    // Non-throwing wrapper: statistics import is best-effort.
    // Requires the entity to be known to HA's recorder (at least one state with the correct
    // state_class must have been processed). On the very first send the recorder may not have
    // seen the entity yet — the import will start working on subsequent sends.
    private fun tryImportStatistic(
        entityId: String,
        unit: String,
        value: Double,
        capturedAt: String,
        stateClass: String
    ) {
        try {
            importStatistic(entityId, unit, value, capturedAt, stateClass)
            Log.d("SmartMeter", "import_statistics OK: $entityId @ ${toHourBoundary(capturedAt)}")
        } catch (e: Exception) {
            Log.w("SmartMeter", "import_statistics skipped for $entityId: ${e.message}")
        }
    }

    private fun toHourBoundary(isoTimestamp: String): String = try {
        java.time.OffsetDateTime.parse(isoTimestamp)
            .truncatedTo(java.time.temporal.ChronoUnit.HOURS)
            .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    } catch (e: Exception) { isoTimestamp }

    private fun importStatistic(
        entityId: String,
        unit: String,
        value: Double,
        startIso: String,
        stateClass: String
    ) {
        val hourStart = toHourBoundary(startIso)
        // measurement sensors track mean/min/max; total_increasing sensors track cumulative sum
        val hasMean = stateClass == "measurement"
        val hasSum = stateClass == "total_increasing"

        val metadata = JSONObject().apply {
            put("statistic_id", entityId)
            put("source", "recorder")
            put("has_mean", hasMean)
            put("has_sum", hasSum)
            put("unit_of_measurement", unit)
            put("name", entityId.removePrefix("sensor.").replace('_', ' ')
                .replaceFirstChar { it.uppercase() })
        }
        val stat = JSONObject().apply {
            put("start", hourStart)
            put("state", value)
            if (hasMean) {
                put("mean", value)
                put("min", value)
                put("max", value)
            }
            if (hasSum) {
                // sum = absolute cumulative meter reading; HA derives hourly delta from consecutive sums
                put("sum", value)
            }
        }
        val body = JSONObject().apply {
            put("metadata", metadata)
            put("stats", JSONArray().apply { put(stat) })
        }.toString().toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("${config.baseUrl}/api/services/recorder/import_statistics")
            .header("Authorization", "Bearer ${config.token}")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "HTTP ${response.code} from import_statistics — body: $responseBody"
                )
            }
        }
    }
}
