package de.simonroder.smartmeterscale.ocr

import android.graphics.Bitmap
import android.util.Base64
import de.simonroder.smartmeterscale.data.MeterType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class GeminiRateLimitException(message: String) : Exception(message)

class GeminiOcrClient(private val apiKey: String, private val model: String = "gemini-3.6-flash") {

    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        private const val MAX_RETRIES = 2
    }

    private val scalePrompt = """
        What values are shown on this body scale display?
        Write exactly three lines, nothing else:
        weight=<number>
        fat=<number>
        water=<number>
        Replace <number> with the actual value from the display. Use a dot as decimal separator.
    """.trimIndent()

    // Gas and water meters use mechanical rolling drum displays (Rollenzählwerk).
    // The decimal digits are typically printed on red drums.
    private val gasPrompt = """
        This is a gas meter with a mechanical rolling drum display.
        Read the total gas consumption. Include ALL digit drums — both the black (whole cubic metres) and red/orange (decimal) drums.
        Write only the numeric value in m³, nothing else. Use a dot as decimal separator.
        Example format: 1234.567
    """.trimIndent()

    private val waterPrompt = """
        This is a water meter with a mechanical rolling drum display.
        Read the total water consumption. Include ALL digit drums — both the black (whole cubic metres) and red (decimal/litre) drums.
        Write only the numeric value in m³, nothing else. Use a dot as decimal separator.
        Example format: 0567.123
    """.trimIndent()

    // Electricity meters may show HT/NT tariffs or a single total.
    private val electricityPrompt = """
        This is an electricity meter. Read the total energy consumption shown on the display.
        If multiple readings are visible (e.g. HT/NT or Tariff 1/2), read the main total or the currently active tariff.
        Write only the numeric value in kWh, nothing else. Use a dot as decimal separator.
        Example format: 12345.6
    """.trimIndent()

    private val odometerPrompt = """
        This is a vehicle odometer showing total kilometres driven.
        Read the odometer value. Ignore trip meters or any secondary reading — only the total odometer.
        Write only the numeric value in km as an integer, nothing else.
        Example format: 123456
    """.trimIndent()

    private fun systemInstructionFor(meterType: MeterType): String = when (meterType) {
        MeterType.Gas, MeterType.Water ->
            "You are a precise OCR system for reading mechanical meter displays with rolling digit drums. " +
            "The image pixels are already correctly oriented — do not attempt to mentally re-rotate. " +
            "Read every digit exactly as it appears, including decimal drums. " +
            "Never guess or hallucinate values. Return only what is explicitly visible."
        else ->
            "You are a precise OCR system for reading electronic displays. " +
            "Analyze the image carefully. The image pixels are already correctly oriented — " +
            "do not attempt to mentally re-rotate. " +
            "Read numbers and text with maximum accuracy. " +
            "Never guess or hallucinate values. " +
            "Return only what is explicitly visible on the display."
    }

    fun recognizeText(bitmap: Bitmap, meterType: MeterType): String {
        val base64 = bitmapToBase64(bitmap)
        val prompt = when (meterType) {
            MeterType.Scale -> scalePrompt
            MeterType.Gas -> gasPrompt
            MeterType.Water -> waterPrompt
            MeterType.Electricity -> electricityPrompt
            MeterType.Odometer -> odometerPrompt
        }
        val body = buildRequestBody(base64, prompt, systemInstructionFor(meterType))

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        var lastError: Exception? = null
        repeat(MAX_RETRIES + 1) { attempt ->
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 429 ->
                        throw GeminiRateLimitException("Gemini-Limit erreicht (5 RPM / 20 RPD). Fallback auf ML Kit.")
                    response.code == 503 && attempt < MAX_RETRIES -> {
                        lastError = IllegalStateException("Gemini API error ${response.code}: ${response.body?.string()}")
                        Thread.sleep(2000L * (attempt + 1))
                        return@use
                    }
                    !response.isSuccessful ->
                        throw IllegalStateException("Gemini API error ${response.code}: ${response.body?.string()}")
                    else -> {
                        val json = JSONObject(response.body?.string() ?: "")
                        return json
                            .getJSONArray("candidates")
                            .getJSONObject(0)
                            .getJSONObject("content")
                            .getJSONArray("parts")
                            .getJSONObject(0)
                            .getString("text")
                            .trim()
                    }
                }
            }
        }
        throw lastError ?: IllegalStateException("Gemini request failed after retries")
    }

    private fun buildRequestBody(base64Image: String, prompt: String, systemInstructionText: String): JSONObject {
        val imagePart = JSONObject().apply {
            put("inline_data", JSONObject().apply {
                put("mime_type", "image/jpeg")
                put("data", base64Image)
            })
        }
        val textPart = JSONObject().apply { put("text", prompt) }
        val content = JSONObject().apply {
            put("parts", JSONArray().apply {
                put(imagePart)
                put(textPart)
            })
        }
        val systemInstruction = JSONObject().apply {
            put("parts", JSONArray().apply {
                put(JSONObject().apply { put("text", systemInstructionText) })
            })
        }
        return JSONObject().apply {
            put("systemInstruction", systemInstruction)
            put("contents", JSONArray().apply { put(content) })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.0)
                put("maxOutputTokens", 1024)
                put("mediaResolution", "MEDIA_RESOLUTION_HIGH")
            })
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val output = ByteArrayOutputStream()
        // High quality to preserve fine LCD digit details
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }
}
