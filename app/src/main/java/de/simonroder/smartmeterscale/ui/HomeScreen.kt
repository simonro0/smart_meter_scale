package de.simonroder.smartmeterscale.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.simonroder.smartmeterscale.data.MeterType
import de.simonroder.smartmeterscale.data.TransmissionRecord
import de.simonroder.smartmeterscale.ha.TransmissionHistory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenCamera: (MeterType) -> Unit,
    onOpenGallery: (MeterType) -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val history = remember { TransmissionHistory(context) }
    val lastSent = remember { MeterType.entries.associateWith { history.getLast(it) } }
    var selectedType by remember { mutableStateOf<MeterType?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("SmartMeterScale")
                        Text(
                            "Capture · Recognize · Transmit",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(MeterType.entries) { type ->
                    MeterTypeCard(
                        type = type,
                        lastRecord = lastSent[type],
                        selected = selectedType == type,
                        onClick = { selectedType = if (selectedType == type) null else type }
                    )
                }
            }

            AnimatedVisibility(
                visible = selectedType != null,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                val type = selectedType ?: return@AnimatedVisibility
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { onOpenCamera(type) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PhotoCamera, null, Modifier.padding(end = 6.dp))
                        Text("Camera")
                    }
                    OutlinedButton(
                        onClick = { onOpenGallery(type) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PhotoLibrary, null, Modifier.padding(end = 6.dp))
                        Text("Gallery")
                    }
                }
            }
        }
    }
}

@Composable
private fun MeterTypeCard(
    type: MeterType,
    lastRecord: TransmissionRecord?,
    selected: Boolean,
    onClick: () -> Unit
) {
    val icon = when (type) {
        MeterType.Scale -> Icons.Default.MonitorWeight
        MeterType.Gas -> Icons.Default.LocalFireDepartment
        MeterType.Electricity -> Icons.Default.ElectricBolt
        MeterType.Water -> Icons.Default.Water
    }
    val containerColor = when (type) {
        MeterType.Scale -> MaterialTheme.colorScheme.surfaceVariant
        MeterType.Gas -> MaterialTheme.colorScheme.tertiaryContainer
        MeterType.Electricity -> MaterialTheme.colorScheme.secondaryContainer
        MeterType.Water -> MaterialTheme.colorScheme.primaryContainer
    }
    val displayTime = remember(lastRecord?.timestamp) {
        lastRecord?.timestamp?.let { ts ->
            runCatching {
                val odt = java.time.OffsetDateTime.parse(ts)
                java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm").format(odt)
            }.getOrNull()
        }
    }

    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(type.displayName, style = MaterialTheme.typography.labelLarge)
                if (displayTime != null) {
                    Text(
                        displayTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                } else {
                    Text(
                        "No data yet",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }
    }
}
