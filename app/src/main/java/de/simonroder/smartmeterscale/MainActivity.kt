package de.simonroder.smartmeterscale

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import de.simonroder.smartmeterscale.data.MeterType
import de.simonroder.smartmeterscale.data.ScaleReading
import de.simonroder.smartmeterscale.data.TransmissionRecord
import de.simonroder.smartmeterscale.ha.HaPreferences
import de.simonroder.smartmeterscale.ha.TransmissionHistory
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
import androidx.exifinterface.media.ExifInterface
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

private data class RetransmitDialogInfo(
    val record: TransmissionRecord,
    val pendingMeterType: MeterType,
    val isSameHour: Boolean,
    val openGallery: Boolean = false
)

class MainActivity : ComponentActivity() {

    private val shortcutTypeState = mutableStateOf<MeterType?>(null)
    private val themeModeState = mutableStateOf("system")

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* handled via composable state */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        themeModeState.value = HaPreferences(this).themeMode
        shortcutTypeState.value = intent.getShortcutMeterType()
        setContent {
            val darkTheme = when (themeModeState.value) {
                "dark" -> true
                "light" -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            SmartMeterScaleTheme(darkTheme = darkTheme) {
                AppContent(shortcutTypeState, themeModeState)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        shortcutTypeState.value = intent.getShortcutMeterType()
    }

    @Composable
    private fun AppContent(shortcutTypeState: MutableState<MeterType?>, themeModeState: MutableState<String>) {
        var screen by remember { mutableStateOf<Screen>(Screen.Home) }
        var pendingMeterType by remember { mutableStateOf<MeterType?>(null) }
        val mlKitProcessor = remember { OcrProcessor() }
        val parser = remember { OcrValueParser() }
        val scope = rememberCoroutineScope()
        val history = remember { TransmissionHistory(this) }
        var retransmitDialog by remember { mutableStateOf<RetransmitDialogInfo?>(null) }
        var pendingGalleryUri by remember { mutableStateOf<Uri?>(null) }
        var pendingGalleryAt by remember { mutableStateOf<String?>(null) }
        var pendingGalleryName by remember { mutableStateOf<String?>(null) }

        suspend fun processGalleryFd(type: MeterType, pfd: android.os.ParcelFileDescriptor?, capturedAt: String, originalName: String? = null) {
            try {
                screen = Screen.Processing(type, null)
                if (pfd == null) {
                    screen = Screen.Result(type, null, null, null,
                        "Fehler: Galeriebild konnte nicht geöffnet werden (openFileDescriptor = null)")
                    return
                }
                val pair = withContext(Dispatchers.IO) {
                    pfd.use { copyFdToMedia(it.fileDescriptor, type, capturedAt, originalName) }
                }
                if (pair == null) {
                    screen = Screen.Result(type, null, null, null,
                        "Fehler: Galeriebild konnte nicht dekodiert werden")
                    return
                }
                val (imagePath, bitmap) = pair
                withContext(Dispatchers.IO) { copyToBackup(imagePath, type) }
                val result = processImage(bitmap, imagePath, type, mlKitProcessor, parser)
                screen = result.copy(capturedAt = capturedAt)
            } catch (e: Exception) {
                Log.e("SmartMeter", "Gallery flow exception: ${e.message}", e)
                screen = Screen.Result(type, null, null, null,
                    "Fehler: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            val type = pendingMeterType ?: return@rememberLauncherForActivityResult
            uri ?: return@rememberLauncherForActivityResult

            // Both operations happen here on the main thread while the URI permission from
            // GetContent() is guaranteed active. On Android 13+ (photo picker), URIs may
            // become inaccessible from background threads after the callback returns.
            val capturedAt = readGalleryTimestamp(uri) ?: millisToIso(System.currentTimeMillis())
            val originalName = try {
                contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            } catch (e: Exception) { null }
            val last = history.getLast(type)

            if (last != null && timestampsInSameHour(last.timestamp, capturedAt)) {
                // Gallery image timestamp matches the last successful record → warn before re-analyzing
                pendingGalleryUri = uri
                pendingGalleryAt = capturedAt
                pendingGalleryName = originalName
                retransmitDialog = RetransmitDialogInfo(last, type, isSameHour = true, openGallery = true)
            } else {
                val pfd = try { contentResolver.openFileDescriptor(uri, "r") } catch (e: Exception) {
                    Log.e("SmartMeter", "Gallery: openFileDescriptor threw for $uri: ${e.message}")
                    null
                }
                scope.launch { processGalleryFd(type, pfd, capturedAt, originalName) }
            }
        }

        val cameraPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) pendingMeterType?.let { screen = Screen.Camera(it) }
        }

        fun openCameraForType(type: MeterType) {
            pendingMeterType = type
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                screen = Screen.Camera(type)
            } else {
                cameraPermLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        fun requestCameraOrHistory(type: MeterType) {
            val last = history.getLast(type)
            if (last != null && history.isSameHour(last)) {
                // Same hour: warn before capturing a potential duplicate
                pendingMeterType = type
                retransmitDialog = RetransmitDialogInfo(last, type, isSameHour = true)
            } else {
                openCameraForType(type)
            }
        }

        LaunchedEffect(shortcutTypeState.value) {
            val type = shortcutTypeState.value ?: return@LaunchedEffect
            shortcutTypeState.value = null
            requestCameraOrHistory(type)
        }

        retransmitDialog?.let { info ->
            RetransmitDialog(
                info = info,
                onRetransmit = {
                    retransmitDialog = null
                    val r = info.record
                    val scaleReading = if (r.meterType == MeterType.Scale && r.scaleWeight != null)
                        ScaleReading(r.scaleWeight, r.scaleBodyFat, r.scaleBodyWater) else null
                    screen = Screen.Result(r.meterType, scaleReading, r.meterValue, null, capturedAt = r.timestamp)
                },
                onNewCapture = {
                    retransmitDialog = null
                    if (info.openGallery) {
                        // Re-analyze the already-selected gallery image
                        val uri = pendingGalleryUri
                        val at = pendingGalleryAt ?: millisToIso(System.currentTimeMillis())
                        val origName = pendingGalleryName
                        pendingGalleryUri = null
                        pendingGalleryAt = null
                        pendingGalleryName = null
                        if (uri != null) {
                            val pfd2 = try { contentResolver.openFileDescriptor(uri, "r") } catch (e: Exception) { null }
                            scope.launch { processGalleryFd(info.pendingMeterType, pfd2, at, origName) }
                        }
                    } else {
                        openCameraForType(info.pendingMeterType)
                    }
                },
                onDismiss = { retransmitDialog = null }
            )
        }

        when (val s = screen) {
            is Screen.Home -> HomeScreen(
                onOpenCamera = { type -> requestCameraOrHistory(type) },
                onOpenGallery = { type ->
                    pendingMeterType = type
                    galleryLauncher.launch("image/*")
                },
                onOpenSettings = { screen = Screen.Settings }
            )
            is Screen.Camera -> CameraScreen(
                meterType = s.meterType,
                onImageCaptured = { bitmap: Bitmap, rotationDegrees: Int ->
                    scope.launch {
                        val capturedAt = millisToIso(System.currentTimeMillis())
                        val rotated = rotateBitmap(bitmap, rotationDegrees)
                        val imagePath = withContext(Dispatchers.IO) { saveBitmapToMedia(rotated, s.meterType) }
                        screen = Screen.Processing(s.meterType, imagePath)
                        withContext(Dispatchers.IO) { copyToBackup(imagePath, s.meterType) }
                        val result = processImage(rotated, imagePath, s.meterType, mlKitProcessor, parser)
                        screen = result.copy(capturedAt = capturedAt)
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
                capturedAt = s.capturedAt,
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
                        val result = processImage(bitmap, path, s.meterType, mlKitProcessor, parser)
                        screen = result.copy(capturedAt = s.capturedAt)
                    }
                },
                onSendSuccess = { record -> history.save(record) },
                onBack = { screen = Screen.Home }
            )
            is Screen.Settings -> SettingsScreen(
                themeModeState = themeModeState,
                onBack = { screen = Screen.Home }
            )
        }
    }

    private suspend fun processImage(
        bitmap: Bitmap,
        imagePath: String?,
        type: MeterType,
        mlKitProcessor: OcrProcessor,
        parser: OcrValueParser
    ): Screen.Result {
        val haPrefs = HaPreferences(this)
        val geminiKey = haPrefs.geminiApiKey
        if (geminiKey.isNotBlank()) {
            try {
                return processWithGemini(bitmap, imagePath, type, geminiKey, haPrefs.geminiModel, parser)
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
        model: String,
        parser: OcrValueParser
    ): Screen.Result {
        return try {
            val gemini = GeminiOcrClient(apiKey, model)
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

    private fun capturesDir(meterType: MeterType): File =
        File(getExternalMediaDirs().firstOrNull(), "captures/${meterType.entityBase}").also { it.mkdirs() }

    private fun saveBitmapToMedia(bitmap: Bitmap, meterType: MeterType): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(capturesDir(meterType), "${timestamp}_${meterType.entityBase}.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        Log.d("SmartMeter", "Image saved: ${file.absolutePath}")
        return file.absolutePath
    }

    // Decodes a gallery image from a FileDescriptor.
    // Reads all bytes into a ByteArray first — this works for both seekable FDs (local files)
    // and non-seekable streams (e.g. Google Photos cloud content). The ByteArray then allows
    // the two-pass BitmapFactory approach (pass 1: dimensions only; pass 2: decode with
    // inSampleSize) without needing to seek or re-open the stream.
    // Note: FileInputStream(fd) does NOT close the FD when the stream is closed/GCed,
    // since the FD is owned externally by the ParcelFileDescriptor.
    private fun copyFdToMedia(fd: FileDescriptor, meterType: MeterType, capturedAt: String? = null, originalName: String? = null): Pair<String, Bitmap>? {
        val imageBytes = try {
            FileInputStream(fd).readBytes()
        } catch (e: Exception) {
            Log.e("SmartMeter", "copyFdToMedia: read failed: ${e.message}")
            return null
        }
        if (imageBytes.isEmpty()) {
            Log.e("SmartMeter", "copyFdToMedia: stream returned 0 bytes")
            return null
        }
        Log.d("SmartMeter", "Gallery: read ${imageBytes.size} bytes from FD")

        // Pass 1: dimensions only, no bitmap allocated
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, boundsOpts)
        val srcW = boundsOpts.outWidth
        val srcH = boundsOpts.outHeight
        if (srcW <= 0 || srcH <= 0) {
            Log.e("SmartMeter", "copyFdToMedia: invalid dimensions ${srcW}×${srcH} — not a valid image")
            return null
        }
        Log.d("SmartMeter", "Gallery source: ${srcW}×${srcH}")

        // Keep the longer side ≤ 2048 px — sufficient resolution for Gemini and ML Kit OCR
        val sampleSize = computeSampleSize(srcW, srcH, maxDimension = 2048)

        // Pass 2: decode at reduced resolution using the already-buffered bytes
        val bitmap = BitmapFactory.decodeByteArray(
            imageBytes, 0, imageBytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        )
        if (bitmap == null) {
            Log.e("SmartMeter", "copyFdToMedia: decodeByteArray returned null (sampleSize=$sampleSize)")
            return null
        }
        Log.d("SmartMeter", "Gallery decoded: ${bitmap.width}×${bitmap.height} (sampleSize=$sampleSize)")

        val filename = if (!originalName.isNullOrBlank()) originalName
                       else {
                           val ts = capturedAt?.let { isoToFileTimestamp(it) }
                               ?: SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                           "${ts}_gallery.jpg"
                       }
        val file = File(capturesDir(meterType), filename)
        try {
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        } catch (e: Exception) {
            Log.e("SmartMeter", "copyFdToMedia: write failed at ${file.absolutePath}: ${e.message}")
            return null
        }
        Log.d("SmartMeter", "Gallery → ${file.absolutePath} (${file.length()} bytes)")
        return Pair(file.absolutePath, bitmap)
    }

    private fun computeSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var s = 1
        while (maxOf(width, height) / s > maxDimension) s *= 2
        return s
    }

    private fun readGalleryTimestamp(uri: Uri): String? {
        // 1. EXIF DateTimeOriginal — most accurate for camera shots
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val tag = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                if (!tag.isNullOrBlank()) {
                    val sdf = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.getDefault())
                    sdf.timeZone = java.util.TimeZone.getDefault()
                    sdf.parse(tag)?.let { return millisToIso(it.time) }
                }
            }
        } catch (e: Exception) {
            Log.w("SmartMeter", "EXIF read failed for $uri: ${e.message}")
        }

        // 2. Parse yyyyMMdd_HHmmss from filename (common for Syncthing-synced files)
        try {
            contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    parseTimestampFromFilename(c.getString(0) ?: "")?.let { return millisToIso(it) }
                }
            }
        } catch (e: Exception) {
            Log.w("SmartMeter", "Filename timestamp parse failed: ${e.message}")
        }

        // 3. MediaStore DATE_TAKEN fallback
        return try {
            contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DATE_TAKEN), null, null, null)?.use { c ->
                if (c.moveToFirst()) { val ms = c.getLong(0); if (ms > 0) millisToIso(ms) else null } else null
            }
        } catch (e: Exception) {
            Log.w("SmartMeter", "readGalleryTimestamp MediaStore: ${e.message}")
            null
        }
    }

    private fun parseTimestampFromFilename(name: String): Long? {
        val match = Regex("(\\d{8})[_-](\\d{6})").find(name) ?: return null
        return try {
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            sdf.timeZone = java.util.TimeZone.getDefault()
            sdf.parse("${match.groupValues[1]}_${match.groupValues[2]}")?.time
        } catch (e: Exception) { null }
    }

    private fun timestampsInSameHour(ts1: String, ts2: String): Boolean = try {
        val a = java.time.OffsetDateTime.parse(ts1)
        val b = java.time.OffsetDateTime.parse(ts2)
        a.year == b.year && a.dayOfYear == b.dayOfYear && a.hour == b.hour
    } catch (e: Exception) { false }

    private fun millisToIso(millis: Long): String =
        Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private fun isoToFileTimestamp(iso: String): String = try {
        val instant = java.time.OffsetDateTime.parse(iso).toInstant()
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date.from(instant))
    } catch (e: Exception) {
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
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
            val rootDir = DocumentFile.fromTreeUri(this, treeUri) ?: run {
                Log.w("SmartMeter", "Backup: could not open tree URI")
                return
            }
            val subDir = rootDir.findFile(meterType.entityBase)?.takeIf { it.isDirectory }
                ?: rootDir.createDirectory(meterType.entityBase) ?: run {
                    Log.w("SmartMeter", "Backup: could not create subfolder ${meterType.entityBase}")
                    return
                }
            val fileName = File(sourcePath).name
            val newFile = subDir.createFile("image/jpeg", fileName) ?: run {
                Log.w("SmartMeter", "Backup: could not create file $fileName")
                return
            }
            contentResolver.openOutputStream(newFile.uri)?.use { out ->
                File(sourcePath).inputStream().use { it.copyTo(out) }
            }
            Log.d("SmartMeter", "Backup saved: ${meterType.entityBase}/$fileName")
        } catch (e: Exception) {
            Log.e("SmartMeter", "Backup failed: ${e.message}", e)
        }
    }
}

private fun Intent.getShortcutMeterType(): MeterType? {
    val name = getStringExtra("SHORTCUT_METER_TYPE") ?: return null
    return MeterType.entries.find { it.name == name }
}

@Composable
private fun RetransmitDialog(
    info: RetransmitDialogInfo,
    onRetransmit: () -> Unit,
    onNewCapture: () -> Unit,
    onDismiss: () -> Unit
) {
    val displayTime = remember(info.record.timestamp) {
        runCatching {
            val odt = java.time.OffsetDateTime.parse(info.record.timestamp)
            java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").format(odt)
        }.getOrDefault(info.record.timestamp)
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    if (info.openGallery) "Already transmitted this image?" else "Already sent this hour",
                    style = MaterialTheme.typography.titleLarge
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (info.openGallery)
                            "The selected image is from the same hour as your last successful transmission."
                        else
                            "A reading was already sent this hour.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("Last: ${info.record.displayValue}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "At: $displayTime",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = onNewCapture) {
                        Text(if (info.openGallery) "Re-analyze" else "Capture anyway")
                    }
                    Button(onClick = onRetransmit) { Text("Use previous") }
                }
            }
        }
    }
}
