package de.simonroder.smartmeterscale.ui

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.simonroder.smartmeterscale.ha.HaPreferences
import de.simonroder.smartmeterscale.ha.UserPreferences

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val haPrefs = remember { HaPreferences(context) }
    val userPrefs = remember { UserPreferences(context) }

    var baseUrl by remember { mutableStateOf(haPrefs.baseUrl) }
    var token by remember { mutableStateOf(haPrefs.token) }
    var backupPath by remember { mutableStateOf(haPrefs.backupPath) }
    var geminiApiKey by remember { mutableStateOf(haPrefs.geminiApiKey) }
    var saved by remember { mutableStateOf(false) }
    var users by remember { mutableStateOf(userPrefs.getUsers()) }
    var newUserName by remember { mutableStateOf("") }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            // Persist read+write permission across app restarts
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            haPrefs.backupUri = it.toString()
            backupPath = it.toFilePath() ?: it.toString()
            saved = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- Home Assistant ---
            Text("Home Assistant", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it; saved = false },
                label = { Text("Base URL") },
                placeholder = { Text("https://yourname.duckdns.org") },
                supportingText = { Text("Die externe Adresse deiner HA-Instanz (DuckDNS, Nabu Casa o.ä.)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = token,
                onValueChange = { token = it; saved = false },
                label = { Text("Long-Lived Access Token") },
                supportingText = { Text("HA → Profil → Sicherheit → Token erstellen") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )

            Button(
                onClick = {
                    haPrefs.baseUrl = baseUrl.trimEnd('/')
                    haPrefs.token = token.trim()
                    haPrefs.backupPath = backupPath.trim()
                    haPrefs.geminiApiKey = geminiApiKey.trim()
                    saved = true
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Speichern") }

            if (saved) Text("Gespeichert.", color = MaterialTheme.colorScheme.primary)

            HorizontalDivider()

            // --- Gemini OCR ---
            Text("Gemini OCR (optional)", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = geminiApiKey,
                onValueChange = { geminiApiKey = it; saved = false },
                label = { Text("Gemini API Key") },
                supportingText = { Text("Kostenloser Key unter aistudio.google.com → \"Get API key\". Wenn eingetragen, wird Gemini statt ML Kit für die Texterkennung verwendet — deutlich zuverlässiger für LCD-Anzeigen.") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )

            HorizontalDivider()

            // --- Backup-Ordner ---
            Text("Backup / Syncthing", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = backupPath,
                onValueChange = { backupPath = it; saved = false },
                label = { Text("Backup-Ordner (optional)") },
                placeholder = { Text("/storage/emulated/0/SmartMeter") },
                supportingText = { Text("Aufnahmen werden zusätzlich hier gespeichert und können per Syncthing synchronisiert werden.") },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(onClick = { folderPickerLauncher.launch(null) }) {
                        Icon(Icons.Default.Folder, contentDescription = "Ordner auswählen")
                    }
                },
                singleLine = true
            )

            HorizontalDivider()

            // --- Nutzer ---
            Text("Waagen-Nutzer", style = MaterialTheme.typography.titleMedium)

            users.forEach { user ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(user.name, style = MaterialTheme.typography.bodyLarge)
                    IconButton(onClick = {
                        userPrefs.removeUser(user.id)
                        users = userPrefs.getUsers()
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Entfernen")
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newUserName,
                    onValueChange = { newUserName = it },
                    label = { Text("Neuer Nutzer") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                IconButton(
                    onClick = {
                        if (newUserName.isNotBlank()) {
                            userPrefs.addUser(newUserName)
                            users = userPrefs.getUsers()
                            newUserName = ""
                        }
                    }
                ) {
                    Icon(Icons.Default.PersonAdd, contentDescription = "Hinzufügen")
                }
            }

            Text(
                "Sensor-IDs: sensor.scale_weight_vorname (Waage), sensor.gas_meter, sensor.electricity_meter, sensor.water_meter",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

private fun Uri.toFilePath(): String? {
    return try {
        val docId = DocumentsContract.getTreeDocumentId(this)
        val parts = docId.split(":")
        if (parts.size >= 2 && parts[0] == "primary") {
            "/storage/emulated/0/${parts[1]}"
        } else null
    } catch (e: Exception) {
        null
    }
}
