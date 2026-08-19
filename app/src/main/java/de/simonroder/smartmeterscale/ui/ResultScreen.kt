package de.simonroder.smartmeterscale.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.simonroder.smartmeterscale.data.MeterType
import de.simonroder.smartmeterscale.data.ScaleReading
import de.simonroder.smartmeterscale.data.User
import de.simonroder.smartmeterscale.ha.HaPreferences
import de.simonroder.smartmeterscale.ha.HomeAssistantClient
import de.simonroder.smartmeterscale.ha.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    meterType: MeterType,
    scaleReading: ScaleReading?,
    meterValue: Double?,
    imagePath: String?,
    rawOcrText: String? = null,
    capturedAt: String? = null,
    onRotateFile: (suspend (degrees: Int) -> Unit)? = null,
    onRetryOcr: (() -> Unit)? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haPrefs = remember { HaPreferences(context) }
    val userPrefs = remember { UserPreferences(context) }
    val users = remember { userPrefs.getUsers() }
    val scope = rememberCoroutineScope()

    var selectedUser by remember { mutableStateOf<User?>(users.firstOrNull()) }
    var userMenuExpanded by remember { mutableStateOf(false) }
    var sendStatus by remember { mutableStateOf("") }
    val isError = remember(rawOcrText) { rawOcrText?.startsWith("Fehler") == true }
    var debugExpanded by remember(isError) { mutableStateOf(isError) }

    // Preview bitmap — reloaded from file after each rotation
    var thumbnail by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(imagePath) {
        thumbnail = withContext(Dispatchers.IO) {
            imagePath?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() }
        }
    }

    // Debounce: rotate preview immediately, start OCR only after 1.5s pause
    var pendingOcrJob by remember { mutableStateOf<Job?>(null) }
    var ocrCountdown by remember { mutableStateOf(false) }

    fun rotate(degrees: Int) {
        scope.launch {
            onRotateFile?.invoke(degrees)
            thumbnail = withContext(Dispatchers.IO) {
                imagePath?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() }
            }
            pendingOcrJob?.cancel()
            ocrCountdown = true
            pendingOcrJob = scope.launch {
                delay(1500L)
                ocrCountdown = false
                onRetryOcr?.invoke()
            }
        }
    }

    val hasRotate = onRotateFile != null && onRetryOcr != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ergebnis – ${meterType.displayName}") },
                navigationIcon = {
                    IconButton(onClick = {
                        pendingOcrJob?.cancel()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    if (hasRotate) {
                        IconButton(onClick = { rotate(-90) }) {
                            Icon(Icons.AutoMirrored.Filled.RotateLeft, contentDescription = "90° links drehen")
                        }
                        IconButton(onClick = { rotate(90) }) {
                            Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "90° rechts drehen")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            thumbnail?.let {
                Image(
                    bitmap = it,
                    contentDescription = "Aufgenommenes Bild",
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    contentScale = ContentScale.Fit
                )
            }

            if (ocrCountdown) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        "Erkennung startet gleich…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            if (meterType == MeterType.Scale && scaleReading != null) {
                ReadingCard("Gewicht", "${scaleReading.weightKg} kg")
                scaleReading.bodyFatPercent?.let { ReadingCard("Körperfett", "$it %") }
                scaleReading.bodyWaterPercent?.let { ReadingCard("Körperwasser", "$it %") }

                if (users.isNotEmpty()) {
                    Box {
                        OutlinedButton(onClick = { userMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Nutzer: ${selectedUser?.name ?: "Keiner"}")
                        }
                        DropdownMenu(expanded = userMenuExpanded, onDismissRequest = { userMenuExpanded = false }) {
                            DropdownMenuItem(text = { Text("Keiner") }, onClick = { selectedUser = null; userMenuExpanded = false })
                            users.forEach { user ->
                                DropdownMenuItem(text = { Text(user.name) }, onClick = { selectedUser = user; userMenuExpanded = false })
                            }
                        }
                    }
                }
            } else if (meterValue != null) {
                ReadingCard(meterType.displayName, "$meterValue ${meterType.unit}")
            } else {
                if (isError && rawOcrText != null) {
                    Text(
                        rawOcrText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(
                        "Keine Werte erkannt." + if (hasRotate) " Bild mit ← → drehen und neu versuchen." else "",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            if (capturedAt != null) {
                val displayTime = remember(capturedAt) {
                    try {
                        val odt = java.time.OffsetDateTime.parse(capturedAt)
                        java.time.format.DateTimeFormatter
                            .ofPattern("dd.MM.yyyy HH:mm")
                            .format(odt)
                    } catch (e: Exception) { capturedAt }
                }
                Text(
                    "Aufnahme: $displayTime",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            val hasReading = (meterType == MeterType.Scale && scaleReading != null) ||
                    (meterType != MeterType.Scale && meterValue != null)

            if (hasReading) {
                if (haPrefs.isConfigured()) {
                    Button(
                        onClick = {
                            scope.launch {
                                sendStatus = "Wird gesendet…"
                                try {
                                    val client = HomeAssistantClient(haPrefs.toConfig())
                                    withContext(Dispatchers.IO) {
                                        if (meterType == MeterType.Scale && scaleReading != null) {
                                            client.sendScaleReading(scaleReading, selectedUser, capturedAt)
                                        } else if (meterValue != null) {
                                            client.sendMeterReading(meterValue, meterType, capturedAt)
                                        }
                                    }
                                    sendStatus = "Erfolgreich gesendet"
                                } catch (e: Exception) {
                                    sendStatus = "Fehler: ${e.message}"
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("An Home Assistant senden") }
                } else {
                    Text(
                        "Home Assistant nicht konfiguriert. Bitte Einstellungen öffnen.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (sendStatus.isNotEmpty()) Text(sendStatus, style = MaterialTheme.typography.bodyMedium)
            }

            OutlinedButton(onClick = {
                pendingOcrJob?.cancel()
                onBack()
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Neue Messung")
            }

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.BugReport,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                "OCR Debug",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        TextButton(onClick = { debugExpanded = !debugExpanded }) {
                            Text(if (debugExpanded) "Ausblenden" else "Anzeigen")
                        }
                    }
                    if (debugExpanded) {
                        HorizontalDivider()
                        Text(
                            text = if (rawOcrText.isNullOrBlank()) "(kein OCR-Text)" else rawOcrText,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadingCard(label: String, value: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.headlineSmall)
        }
    }
}
