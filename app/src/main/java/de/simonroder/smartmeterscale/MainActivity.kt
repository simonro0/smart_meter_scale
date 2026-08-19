package de.simonroder.smartmeterscale

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import de.simonroder.smartmeterscale.data.MeterType
import de.simonroder.smartmeterscale.ha.HaPreferences
import de.simonroder.smartmeterscale.ocr.GeminiOcrClient
import de.simonroder.smartmeterscale.ocr.GeminiRateLimitException
import de.simonroder.smartmeterscale.ocr.OcrProcessor
import de.simonroder.smartmeterscale.ocr.OcrValueParser
import de.simonroder.smartmeterscale.ui.*
import de.simonroder.smartmeterscale.ui.theme.SmartMeterScaleTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* handled via composable state */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SmartMeterScaleTheme { AppContent() } }
    }

    @Composable
    private fun AppContent() {
        var screen by remember { mutableStateOf<Screen>(Screen.Home) }
        var pendingMeterType by remember { mutableStateOf<MeterType?>(null) }
        val mlKitProcessor = remember { OcrProcessor() }
        val parser = remember { OcrValueParser() }
        val scope = rememberCoroutineScope()

        val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            val type = pendingMeterType ?: return@rememberLauncherForActivityResult
            uri?.let {
                scope.launch {
                    val imagePath = copyUriToMedia(uri)
                    screen = Screen.Processing(type, imagePath)
                    copyToBackup(imagePath, type)
                    val bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(imagePath) }
                    screen = if (bitmap != null) {
                        processImage(bitmap, imagePath, type, mlKitProcessor, parser)
                    } else {
                        Screen.Result(type, null, null, imagePath, "Fehler: Bild konnte nicht geladen werden")
                    }
                }
            }
        }

        val cameraPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) pendingMeterType?.let { screen = Screen.Camera(it) }
        }

        when (val s = screen) {
            is Screen.Home -> HomeScreen(
                onOpenCamera = { type ->
                    pendingMeterType = type
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        screen = Screen.Camera(type)
                    } else {
                        cameraPermLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
                onOpenGallery = { type ->
                    pendingMeterType = type
                    galleryLauncher.launch("image/*")
                },
                onOpenSettings = { screen = Screen.Settings }
            )
            is Screen.Camera -> CameraScreen(
                onImageCaptured = { bitmap: Bitmap, rotationDegrees: Int ->
                    scope.launch {
                        val rotated = rotateBitmap(bitmap, rotationDegrees)
                        val imagePath = saveBitmapToMedia(rotated)
                        screen = Screen.Processing(s.meterType, imagePath)
                        copyToBackup(imagePath, s.meterType)
                        screen = processImage(rotated, imagePath, s.meterType, mlKitProcessor, parser)
                    }
                },
                onBack = { screen = Screen.Home }
            )
            is Screen.Processing -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(56.dp))
                    Text("${s.meterType.displayName} wird erkannt…", style = MaterialTheme.typography.bodyLarge)
                    val usingGemini = HaPreferences(this@MainActivity).geminiApiKey.isNotBlank()
                    Text(
                        if (usingGemini) "Gemini AI analysiert das Bild" else "ML Kit läuft…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            is Screen.Result -> ResultScreen(
                meterType = s.meterType,
                scaleReading = s.scaleReading,
                meterValue = s.meterValue,
                imagePath = s.imagePath,
                rawOcrText = s.rawOcrText,
                onRotateFile = { degrees ->
                    val path = s.imagePath ?: return@ResultScreen
                    withContext(Dispatchers.IO) {
                        val bitmap = BitmapFactory.decodeFile(path) ?: return@withContext
                        val rotated = rotateBitmap(bitmap, degrees)
                        FileOutputStream(path).use { rotated.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    }
                },
                onRetryOcr = {
                    scope.launch {
                        val path = s.imagePath ?: return@launch
                        screen = Screen.Processing(s.meterType, path)
                        val bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) } ?: return@launch
                        screen = processImage(bitmap, path, s.meterType, mlKitProcessor, parser)
                    }
                },
                onBack = { screen = Screen.Home }
            )
            is Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Home })
        }
    }

    private suspend fun processImage(
        bitmap: Bitmap,
        imagePath: String?,
        type: MeterType,
        mlKitProcessor: OcrProcessor,
        parser: OcrValueParser
    ): Screen.Result {
        val geminiKey = HaPreferences(this).geminiApiKey
        if (geminiKey.isNotBlank()) {
            try {
                return processWithGemini(bitmap, imagePath, type, geminiKey, parser)
            } catch (e: GeminiRateLimitException) {
                Log.w("SmartMeter", "Gemini rate limit — falling back to ML Kit")
                val result = processWithMlKit(bitmap, imagePath, type, mlKitProcessor, parser)
                return result.copy(rawOcrText = "⚠ ${e.message}\n\nML Kit: ${result.rawOcrText}")
            }
        }
        return processWithMlKit(bitmap, imagePath, type, mlKitProcessor, parser)
    }

    private suspend fun processWithMlKit(
        bitmap: Bitmap,
        imagePath: String?,
        type: MeterType,
        processor: OcrProcessor,
        parser: OcrValueParser
    ): Screen.Result {
        return try {
            val text = processor.processBitmapToText(bitmap)
            Log.d("SmartMeter", "ML Kit OCR [${type.name}]: $text")
            buildResultFromRaw(type, text, parser, imagePath)
        } catch (e: Exception) {
            Log.e("SmartMeter", "ML Kit error: ${e.message}", e)
            Screen.Result(type, null, null, imagePath, "Fehler: ${e.message}")
        }
    }

    private suspend fun processWithGemini(
        bitmap: Bitmap,
        imagePath: String?,
        type: MeterType,
        apiKey: String,
        parser: OcrValueParser
    ): Screen.Result {
        return try {
            val gemini = GeminiOcrClient(apiKey)
            val response = withContext(Dispatchers.IO) { gemini.recognizeText(bitmap, type) }
            Log.d("SmartMeter", "Gemini OCR [${type.name}]: $response")
            if (type == MeterType.Scale) {
                val reading = parser.parseGeminiScale(response)
                Screen.Result(type, reading, null, imagePath, "Gemini: $response")
            } else {
                val value = parser.parseGeminiMeter(response)
                Screen.Result(type, null, value, imagePath, "Gemini: $response")
            }
        } catch (e: Exception) {
            Log.e("SmartMeter", "Gemini error: ${e.message}", e)
            Screen.Result(type, null, null, imagePath, "Gemini-Fehler: ${e.message}")
        }
    }

    private fun buildResultFromRaw(type: MeterType, text: String, parser: OcrValueParser, imagePath: String?): Screen.Result {
        val trimmed = text.trim()
        return if (type == MeterType.Scale) {
            val reading = parser.parse(trimmed)
            Log.d("SmartMeter", "ML Kit Scale parsed: $reading")
            Screen.Result(type, reading, null, imagePath, trimmed)
        } else {
            val value = parser.parseMeterValue(trimmed)
            Log.d("SmartMeter", "ML Kit Meter parsed: $value")
            Screen.Result(type, null, value, imagePath, trimmed)
        }
    }

    private fun capturesDir(): File =
        File(getExternalMediaDirs().firstOrNull(), "captures").also { it.mkdirs() }

    private fun saveBitmapToMedia(bitmap: Bitmap): String {
        val file = File(capturesDir(), "last_capture.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        Log.d("SmartMeter", "Image saved: ${file.absolutePath}")
        return file.absolutePath
    }

    private fun copyUriToMedia(uri: Uri): String {
        val file = File(capturesDir(), "last_capture.jpg")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(file).use { input.copyTo(it) }
        }
        return file.absolutePath
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    // Backup via SAF (DocumentFile) so it works on Android 10+ without MANAGE_EXTERNAL_STORAGE.
    // Timestamp is always in the filename. Called regardless of OCR result.
    private fun copyToBackup(sourcePath: String, meterType: MeterType) {
        val uriString = HaPreferences(this).backupUri
        if (uriString.isBlank()) return
        try {
            val treeUri = Uri.parse(uriString)
            val dir = DocumentFile.fromTreeUri(this, treeUri) ?: run {
                Log.w("SmartMeter", "Backup: could not open tree URI")
                return
            }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "${timestamp}_${meterType.entityBase}.jpg"
            val newFile = dir.createFile("image/jpeg", fileName) ?: run {
                Log.w("SmartMeter", "Backup: could not create file $fileName")
                return
            }
            contentResolver.openOutputStream(newFile.uri)?.use { out ->
                File(sourcePath).inputStream().use { it.copyTo(out) }
            }
            Log.d("SmartMeter", "Backup saved: $fileName")
        } catch (e: Exception) {
            Log.e("SmartMeter", "Backup failed: ${e.message}", e)
        }
    }
}
