package de.simonroder.smartmeterscale.ui

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.MutableState
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
import de.simonroder.smartmeterscale.data.MeterType
import de.simonroder.smartmeterscale.ha.HaPreferences
import de.simonroder.smartmeterscale.ha.UserPreferences

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themeModeState: MutableState<String>,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haPrefs = remember { HaPreferences(context) }
    val userPrefs = remember { UserPreferences(context) }

    var themeMode by themeModeState
    var baseUrl by remember { mutableStateOf(haPrefs.baseUrl) }
    var token by remember { mutableStateOf(haPrefs.token) }
    var geminiApiKey by remember { mutableStateOf(haPrefs.geminiApiKey) }
    var geminiModel by remember { mutableStateOf(haPrefs.geminiModel) }
    var mqttHost by remember { mutableStateOf(haPrefs.mqttHost) }
    var mqttPort by remember { mutableStateOf(haPrefs.mqttPort.toString()) }
    var mqttUsername by remember { mutableStateOf(haPrefs.mqttUsername) }
    var mqttPassword by remember { mutableStateOf(haPrefs.mqttPassword) }
    var saved by remember { mutableStateOf(false) }
    var enabledMeterTypes by remember { mutableStateOf(haPrefs.enabledMeterTypes) }
    var typeBackupUris by remember {
        mutableStateOf(MeterType.entries.associate { it.name to haPrefs.backupUriForType(it) })
    }
    var pendingPickerType by remember { mutableStateOf<MeterType?>(null) }
    var users by remember { mutableStateOf(userPrefs.getUsers()) }
    var newUserName by remember { mutableStateOf("") }

    val typeBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val type = pendingPickerType ?: return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        haPrefs.setBackupUriForType(type, uri.toString())
        typeBackupUris = typeBackupUris + (type.name to uri.toString())
    }

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingsSection("Appearance") {
                Text("Theme", style = MaterialTheme.typography.bodyMedium)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf("System" to "system", "Light" to "light", "Dark" to "dark")
                        .forEachIndexed { i, (label, value) ->
                            SegmentedButton(
                                selected = themeMode == value,
                                onClick = { themeMode = value; saved = false },
                                shape = SegmentedButtonDefaults.itemShape(i, 3),
                                label = { Text(label) }
                            )
                        }
                }
            }

            SettingsSection("Active Meters") {
                Text(
                    "Disable unused meters to keep the home screen clean. Set a backup folder per meter for Syncthing sync.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                MeterType.entries.forEach { type ->
                    val isEnabled = type.name in enabledMeterTypes
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(type.displayName, style = MaterialTheme.typography.bodyLarge)
                            Switch(
                                checked = isEnabled,
                                onCheckedChange = { on ->
                                    enabledMeterTypes = if (on) enabledMeterTypes + type.name
                                                      else (enabledMeterTypes - type.name)
                                                          .ifEmpty { enabledMeterTypes }
                                    saved = false
                                }
                            )
                        }
                        if (isEnabled) {
                            val uriStr = typeBackupUris[type.name] ?: ""
                            val displayPath = if (uriStr.isNotBlank())
                                Uri.parse(uriStr).toFilePath() ?: "Custom folder" else ""
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    if (displayPath.isNotBlank()) displayPath else "No backup folder",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = {
                                    pendingPickerType = type
                                    typeBackupLauncher.launch(null)
                                }) {
                                    Icon(Icons.Default.Folder, contentDescription = "Pick folder")
                                }
                            }
                        }
                    }
                    if (type != MeterType.entries.last()) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }

            SettingsSection("Home Assistant") {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; saved = false },
                    label = { Text("Base URL") },
                    placeholder = { Text("https://yourname.duckdns.org") },
                    supportingText = { Text("External address of your HA instance") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it; saved = false },
                    label = { Text("Long-Lived Access Token") },
                    supportingText = { Text("HA → Profile → Security → Create token") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
            }

            SettingsSection("MQTT (optional)") {
                Text(
                    "Requires Mosquitto add-on in HA. Sensors survive HA restarts. Leave empty for REST fallback.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                OutlinedTextField(
                    value = mqttHost,
                    onValueChange = { mqttHost = it; saved = false },
                    label = { Text("Host") },
                    placeholder = { Text("192.168.1.x or homeassistant.local") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = mqttPort,
                        onValueChange = { mqttPort = it; saved = false },
                        label = { Text("Port") },
                        modifier = Modifier.width(100.dp),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = mqttUsername,
                        onValueChange = { mqttUsername = it; saved = false },
                        label = { Text("Username") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                OutlinedTextField(
                    value = mqttPassword,
                    onValueChange = { mqttPassword = it; saved = false },
                    label = { Text("Password") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
            }

            SettingsSection("Gemini OCR (optional)") {
                OutlinedTextField(
                    value = geminiApiKey,
                    onValueChange = { geminiApiKey = it; saved = false },
                    label = { Text("API Key") },
                    supportingText = { Text("Free key at aistudio.google.com → \"Get API key\". Much more reliable than ML Kit for LCD displays.") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = geminiModel,
                    onValueChange = { geminiModel = it; saved = false },
                    label = { Text("Model") },
                    placeholder = { Text("gemini-3.6-flash") },
                    supportingText = { Text("Model ID from Google AI Studio") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            SettingsSection("Scale Users") {
                Text(
                    "Entity IDs: sensor.scale_weight_<name>, sensor.gas_meter, sensor.electricity_meter, sensor.water_meter, sensor.odometer",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
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
                            Icon(Icons.Default.Delete, contentDescription = "Remove")
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
                        label = { Text("New user") },
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
                        Icon(Icons.Default.PersonAdd, contentDescription = "Add")
                    }
                }
            }

            Button(
                onClick = {
                    haPrefs.themeMode = themeMode
                    haPrefs.enabledMeterTypes = enabledMeterTypes
                    haPrefs.baseUrl = baseUrl.trimEnd('/')
                    haPrefs.token = token.trim()
                    haPrefs.geminiApiKey = geminiApiKey.trim()
                    haPrefs.geminiModel = geminiModel.trim().ifBlank { "gemini-3.6-flash" }
                    haPrefs.mqttHost = mqttHost.trim()
                    haPrefs.mqttPort = mqttPort.trim().toIntOrNull() ?: 1883
                    haPrefs.mqttUsername = mqttUsername.trim()
                    haPrefs.mqttPassword = mqttPassword
                    saved = true
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save") }

            if (saved) {
                Text(
                    "Saved.",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

private fun Uri.toFilePath(): String? {
    return try {
        val docId = DocumentsContract.getTreeDocumentId(this)
        val parts = docId.split(":")
        if (parts.size >= 2 && parts[0] == "primary") "/storage/emulated/0/${parts[1]}" else null
    } catch (e: Exception) { null }
}
