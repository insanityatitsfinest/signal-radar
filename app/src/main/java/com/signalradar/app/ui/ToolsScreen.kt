package com.signalradar.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.signalradar.app.net.AlprTracker
import com.signalradar.app.net.LanScanner
import com.signalradar.app.net.Pineapple
import com.signalradar.app.scan.Device
import com.signalradar.app.scan.ScanEngine

@Composable
fun ToolsScreen(devices: List<Device>, onOpen: (String) -> Unit, onLens: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current

    // ScanEngine states
    val classic by ScanEngine.classicEnabled.collectAsState()

    // LAN Scanner states
    val lanRunning by LanScanner.running.collectAsState()
    val lanStatus by LanScanner.status.collectAsState()

    // ALPR states
    val alprLoading by AlprTracker.loading.collectAsState()
    val alprStatus by AlprTracker.status.collectAsState()
    var radiusIdx by rememberSaveable { mutableIntStateOf(1) }
    val radii = listOf(1000, 3000, 10000, 25000)

    // Pineapple states
    val pineappleRunning by Pineapple.running.collectAsState()
    val pineappleStatus by Pineapple.status.collectAsState()
    val pineappleConnected by Pineapple.connected.collectAsState()
    var pineappleToken by rememberSaveable { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text("Tools", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.green, modifier = Modifier.padding(16.dp))
        }

        // Bluetooth Classic
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Palette.surface2)
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Bluetooth Classic Scanner", fontWeight = FontWeight.SemiBold)
                        Text("Slower, but finds older car kits and TVs.", fontSize = 12.sp, color = Palette.muted)
                    }
                    Switch(checked = classic, onCheckedChange = { ScanEngine.classicEnabled.value = it })
                }
            }
        }

        // Local Network
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Palette.surface2)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Local Network Scanner", fontWeight = FontWeight.SemiBold)
                    Text("Finds cameras and devices sharing your current Wi-Fi network.", fontSize = 12.sp, color = Palette.muted)
                    Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !lanRunning, onClick = { LanScanner.start(ctx) }) { Text(if (lanRunning) "Scanning…" else "Scan Wi-Fi") }
                        OutlinedButton(onClick = { LanScanner.clear() }) { Text("Clear") }
                    }
                    if (lanStatus.isNotEmpty()) Text(lanStatus, fontSize = 13.sp, color = Palette.green, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        // ALPR
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Palette.surface2)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("ALPR Camera Map", fontWeight = FontWeight.SemiBold)
                    Text("Loads OpenStreetMap license-plate cameras (DeFlock) near you.", fontSize = 12.sp, color = Palette.muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                        radii.forEachIndexed { i, r ->
                            FilterChip(selected = radiusIdx == i, onClick = { radiusIdx = i }, label = { Text("${r / 1000} km") })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(enabled = !alprLoading, onClick = { AlprTracker.load(ctx, radii[radiusIdx]) }) {
                            Text(if (alprLoading) "Loading…" else "Download Map")
                        }
                        OutlinedButton(onClick = { AlprTracker.clear(ctx) }) { Text("Clear") }
                    }
                    if (alprStatus.isNotEmpty()) Text(alprStatus, fontSize = 13.sp, color = Palette.alpr, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        // Wi-Fi Pineapple
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Palette.surface2)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Hak5 Wi-Fi Pineapple (USB-C)", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Plug a Pineapple Mark VII into your phone via USB-C, enable USB Tethering, " +
                        "and enter an API token to pull its monitor-mode Recon scans onto the radar.",
                        fontSize = 12.sp, color = Palette.muted
                    )
                    if (!pineappleConnected) {
                        OutlinedTextField(
                            value = pineappleToken, onValueChange = { pineappleToken = it },
                            label = { Text("API Token") },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            singleLine = true
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        if (!pineappleRunning) {
                            Button(onClick = { Pineapple.start(pineappleToken, 2) }) { Text("Start Recon") }
                        } else {
                            Button(onClick = { Pineapple.stop() }, colors = ButtonDefaults.buttonColors(containerColor = Palette.red)) { Text("Stop") }
                        }
                        if (pineappleConnected) {
                            OutlinedButton(onClick = { Pineapple.clear() }) { Text("Disconnect") }
                        }
                    }
                    if (pineappleStatus.isNotEmpty()) {
                        Text(pineappleStatus, fontSize = 13.sp, color = Palette.green, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }

        // Hidden Lens Finder
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Palette.surface2)
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Hidden Lens Finder", fontWeight = FontWeight.SemiBold)
                        Text("Uses the phone's camera and flash to reflect off hidden camera lenses.", fontSize = 12.sp, color = Palette.muted)
                    }
                    Button(onClick = onLens) { Text("Open") }
                }
            }
        }
    }
}
