package de.simonroder.smartmeterscale.ha

import de.simonroder.smartmeterscale.data.ScaleReading
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class HomeAssistantClient(private val config: HomeAssistantConfig) {

    private val client = OkHttpClient()
    private val jsonMediaType = "application/json".toMediaType()

    fun sendReading(reading: ScaleReading) {
        postState("sensor.scale_weight", reading.weightKg.toString(), "kg", "weight")
        reading.bodyFatPercent?.let { postState("sensor.scale_body_fat", it.toString(), "%", null) }
        reading.bodyWaterPercent?.let { postState("sensor.scale_body_water", it.toString(), "%", null) }
    }

    private fun postState(entityId: String, state: String, unit: String, deviceClass: String?) {
        val attributes = JSONObject().apply {
            put("unit_of_measurement", unit)
            put("friendly_name", entityId.removePrefix("sensor.").replace('_', ' ')
                .replaceFirstChar { it.uppercase() })
            deviceClass?.let { put("device_class", it) }
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
}
