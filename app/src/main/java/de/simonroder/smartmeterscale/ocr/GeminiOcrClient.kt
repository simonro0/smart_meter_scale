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

class GeminiOcrClient(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val scalePrompt = """
        What values are shown on this body scale display?
        Write exactly three lines, nothing else:
        weight=<number>
        fat=<number>
        water=<number>
        Replace <number> with the actual value from the display. Use a dot as decimal separator.
    """.trimIndent()

    private val meterPrompt = """
        What is the meter reading shown on this display?
        Write only the numeric value, nothing else. Use a dot as decimal separator.
    """.trimIndent()

    fun recognizeText(bitmap: Bitmap, meterType: MeterType): String {
        val base64 = bitmapToBase64(bitmap)
        val prompt = if (meterType == MeterType.Scale) scalePrompt else meterPrompt
        val body = buildRequestBody(base64, prompt)

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent?key=$apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 429) {
                throw GeminiRateLimitException("Gemini-Limit erreicht (5 RPM / 20 RPD). Fallback auf ML Kit.")
            }
            if (!response.isSuccessful) {
                throw IllegalStateException("Gemini API error ${response.code}: ${response.body?.string()}")
            }
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

    private fun buildRequestBody(base64Image: String, prompt: String): JSONObject {
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
                put(JSONObject().apply {
                    put("text", "You are a precise OCR system for reading electronic displays. " +
                        "Analyze the image carefully. The image pixels are already correctly oriented — " +
                        "do not attempt to mentally re-rotate. " +
                        "Read numbers and text with maximum accuracy. " +
                        "Never guess or hallucinate values. " +
                        "Return only what is explicitly visible on the display.")
                })
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
