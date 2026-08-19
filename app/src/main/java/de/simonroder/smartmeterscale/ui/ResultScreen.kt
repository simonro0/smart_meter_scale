package de.simonroder.smartmeterscale.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    onRotateAndRetry: (() -> Unit)? = null,
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
    var debugExpanded by remember { mutableStateOf(false) }
    var rotating by remember { mutableStateOf(false) }

    // Re-read thumbnail from file so it updates after rotation
    val thumbnail = remember(imagePath, rotating) {
        imagePath?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ergebnis – ${meterType.displayName}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    if (onRotateAndRetry != null) {
                        IconButton(
                            onClick = {
                                rotating = true
                                sendStatus = ""
                                onRotateAndRetry()
                            },
                            enabled = !rotating
                        ) {
                            Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "90° drehen und neu erkennen")
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
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    contentScale = ContentScale.Fit
                )
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
                Text(
                    "Keine Werte erkannt. Bitte erneut versuchen oder Bild drehen (↻).",
                    style = MaterialTheme.typography.bodyLarge
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
                                            client.sendScaleReading(scaleReading, selectedUser)
                                        } else if (meterValue != null) {
                                            client.sendMeterReading(meterValue, meterType)
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

            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Neue Messung")
            }

            // Debug card — shows raw OCR or Gemini response
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
