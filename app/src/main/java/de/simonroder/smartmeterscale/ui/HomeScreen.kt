package de.simonroder.smartmeterscale.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.simonroder.smartmeterscale.data.MeterType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenCamera: (MeterType) -> Unit,
    onOpenGallery: (MeterType) -> Unit,
    onOpenSettings: () -> Unit
) {
    var selectedType by remember { mutableStateOf<MeterType?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SmartMeterScale") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Einstellungen")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Was möchtest du aufnehmen?", style = MaterialTheme.typography.titleMedium)

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(MeterType.entries) { type ->
                    MeterTypeCard(
                        type = type,
                        selected = selectedType == type,
                        onClick = { selectedType = type }
                    )
                }
            }

            if (selectedType != null) {
                val type = selectedType!!
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { onOpenCamera(type) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text("Kamera")
                    }
                    OutlinedButton(
                        onClick = { onOpenGallery(type) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text("Galerie")
                    }
                }
            }
        }
    }
}

@Composable
private fun MeterTypeCard(type: MeterType, selected: Boolean, onClick: () -> Unit) {
    val icon = when (type) {
        MeterType.Scale -> Icons.Default.MonitorWeight
        MeterType.Gas -> Icons.Default.LocalFireDepartment
        MeterType.Electricity -> Icons.Default.ElectricBolt
        MeterType.Water -> Icons.Default.Water
    }
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth().height(100.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.height(6.dp))
            Text(type.displayName, style = MaterialTheme.typography.labelLarge)
        }
    }
}
