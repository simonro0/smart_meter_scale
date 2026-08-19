package de.simonroder.smartmeterscale

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import de.simonroder.smartmeterscale.data.MeterType
import de.simonroder.smartmeterscale.ha.HaPreferences
import de.simonroder.smartmeterscale.ocr.OcrProcessor
import de.simonroder.smartmeterscale.ocr.OcrValueParser
import de.simonroder.smartmeterscale.ui.*
import de.simonroder.smartmeterscale.ui.theme.SmartMeterScaleTheme
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* handled in composable via state */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SmartMeterScaleTheme { AppContent() } }
    }

    @Composable
    private fun AppContent() {
        var screen by remember { mutableStateOf<Screen>(Screen.Home) }
        var pendingMeterType by remember { mutableStateOf<MeterType?>(null) }
        val processor = remember { OcrProcessor() }
        val parser = remember { OcrValueParser() }
        val scope = rememberCoroutineScope()

        val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            val type = pendingMeterType ?: return@rememberLauncherForActivityResult
            uri?.let {
                scope.launch {
                    val imagePath = copyUriToMedia(uri)
                    try {
                        val text = processor.processUriToText(applicationContext, uri)
                        Log.d("SmartMeter", "Gallery OCR [${type.name}]: $text")
                        screen = buildResult(type, text, parser, imagePath)
                    } catch (e: Exception) {
                        Log.e("SmartMeter", "Gallery OCR error: ${e.message}", e)
                        screen = Screen.Result(type, null, null, imagePath, "Fehler: ${e.message}")
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
                        copyToBackupIfConfigured(imagePath, s.meterType)
                        try {
                            val text = processor.processBitmapToText(rotated)
                            Log.d("SmartMeter", "Camera OCR [${s.meterType.name}]: $text")
                            screen = buildResult(s.meterType, text, parser, imagePath)
                        } catch (e: Exception) {
                            Log.e("SmartMeter", "Camera OCR error: ${e.message}", e)
                            screen = Screen.Result(s.meterType, null, null, imagePath, "Fehler: ${e.message}")
                        }
                    }
                },
                onBack = { screen = Screen.Home }
            )
            is Screen.Result -> ResultScreen(
                meterType = s.meterType,
                scaleReading = s.scaleReading,
                meterValue = s.meterValue,
                imagePath = s.imagePath,
                rawOcrText = s.rawOcrText,
                onBack = { screen = Screen.Home }
            )
            is Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Home })
        }
    }

    private fun buildResult(type: MeterType, text: String, parser: OcrValueParser, imagePath: String?): Screen.Result {
        val trimmed = text.trim()
        return if (type == MeterType.Scale) {
            val reading = parser.parse(trimmed)
            Log.d("SmartMeter", "Scale parsed: $reading from text length ${trimmed.length}")
            Screen.Result(type, reading, null, imagePath, trimmed)
        } else {
            val value = parser.parseMeterValue(trimmed)
            Log.d("SmartMeter", "Meter parsed: $value from text length ${trimmed.length}")
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
        Log.d("SmartMeter", "Gallery image copied: ${file.absolutePath}")
        return file.absolutePath
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun copyToBackupIfConfigured(sourcePath: String, meterType: MeterType) {
        val backupPath = HaPreferences(this).backupPath
        if (backupPath.isBlank()) return
        try {
            val dir = File(backupPath).also { it.mkdirs() }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val dest = File(dir, "${timestamp}_${meterType.entityBase}.jpg")
            File(sourcePath).copyTo(dest, overwrite = true)
            Log.d("SmartMeter", "Backup copied to: ${dest.absolutePath}")
        } catch (e: Exception) {
            Log.e("SmartMeter", "Backup copy failed: ${e.message}", e)
        }
    }
}
