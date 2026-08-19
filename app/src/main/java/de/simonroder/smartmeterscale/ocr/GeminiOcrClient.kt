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

class GeminiOcrClient(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val scalePrompt = """
        Look at this scale display and extract the values.
        Return ONLY this format (replace numbers, write null if a value is not visible):
        weight=67.8 fat=14.4 water=60.5
    """.trimIndent()

    private val meterPrompt = """
        Look at this meter display and extract the reading.
        Return ONLY the numeric value shown, nothing else. Example: 1234.567
    """.trimIndent()

    fun recognizeText(bitmap: Bitmap, meterType: MeterType): String {
        val base64 = bitmapToBase64(bitmap)
        val prompt = if (meterType == MeterType.Scale) scalePrompt else meterPrompt
        val body = buildRequestBody(base64, prompt)

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
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
        return JSONObject().apply {
            put("contents", JSONArray().apply { put(content) })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("maxOutputTokens", 50)
            })
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }
}
