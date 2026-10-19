package com.signalradar.app.ui

import androidx.compose.runtime.mutableStateOf
import com.signalradar.app.net.Prefs
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.signalradar.app.net.AlprTracker
import com.signalradar.app.net.LanScanner
import com.signalradar.app.scan.Device
import com.signalradar.app.scan.Rssi
import com.signalradar.app.scan.ScanEngine
import com.signalradar.app.scan.Source
import com.signalradar.app.scan.Vendors
import kotlinx.coroutines.launch

@Composable
fun ToolsScreen(devices: List<Device>, onOpen: (String) -> Unit, onLens: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val now = System.currentTimeMillis()

    val lanRunning by LanScanner.running.collectAsState()
    val lanProgress by LanScanner.progress.collectAsState()
    val lanStatus by LanScanner.status.collectAsState()
    val lanResults by LanScanner.results.collectAsState()

    val alprLoading by AlprTracker.loading.collectAsState()
    val alprStatus by AlprTracker.status.collectAsState()
    val alprTracking by AlprTracker.tracking.collectAsState()
    var radiusIdx by rememberSaveable { mutableIntStateOf(Prefs.radiusIdx(ctx)) }
    var autoLoad by rememberSaveable { mutableStateOf(Prefs.autoLoadAlpr(ctx)) }
    val radii = Prefs.RADII

    val classic by ScanEngine.classicEnabled.collectAsState()
    val status by ScanEngine.status.collectAsState()

    val alprDevices = devices.filter { it.source == Source.ALPR }.sortedBy { it.geoDistanceM ?: 1e9 }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text("Tools", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.green, modifier = Modifier.padding(16.dp, 8.dp))
        }

        // ---------- hidden cameras on this Wi-Fi
        item {
            SectionTitle("Hidden camera check — this Wi-Fi")
            ToolCard {
                Text(
                    "Join the Wi-Fi of the room / rental first. This finds cameras on that network by asking for " +
                        "ONVIF/UPnP devices and probing video-streaming ports on every address.",
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(8.dp))
                Button(enabled = !lanRunning, onClick = { scope.launch { LanScanner.scan(ctx) } }) {
                    Text(if (lanRunning) "Scanning…" else "Scan this Wi-Fi for cameras")
                }
                if (lanRunning) LinearProgressIndicator(progress = { lanProgress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                if (lanStatus.isNotEmpty()) Text(lanStatus, fontSize = 12.sp, color = Palette.muted, modifier = Modifier.padding(top = 6.dp))
            }
        }
        items(lanResults, key = { "lan-" + it.key }) { d -> DeviceRow(d, now) { onOpen(d.key) } }

        // ---------- ALPR map
        item {
            SectionTitle("Flock / ALPR cameras near me")
       ToolCard {
                Text(
                    "License-plate cameras mapped by the DeFlock community (OpenStreetMap), shown on the radar with " +
                        "real distance and direction. Loads on its own when you open the app.",
                    fontSize = 13.sp,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Load automatically on open", fontWeight = FontWeight.SemiBold)
                        Text("Off: tap Refresh below to load it yourself.", fontSize = 12.sp, color = Palette.muted)
                    }
                    Switch(checked = autoLoad, onCheckedChange = { autoLoad = it; Prefs.setAutoLoadAlpr(ctx, it) })
                }
                Text("How far to look", fontSize = 12.sp, color = Palette.muted, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    radii.forEachIndexed { i, r ->
                        FilterChip(
                            selected = radiusIdx == i,
                            onClick = { radiusIdx = i; Prefs.setRadiusIdx(ctx, i); AlprTracker.load(ctx, radii[i]) },
                            label = { Text("${r / 1000} km") },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Button(enabled = !alprLoading, onClick = { AlprTracker.load(ctx, radii[radiusIdx]) }) {
                        Text(if (alprLoading) "Loading…" else "Refresh")
                    }
                    if (alprDevices.isNotEmpty() || alprStatus.isNotEmpty()) OutlinedButton(onClick = { AlprTracker.clear(ctx) }) { Text("Clear") }
                }
                if (alprStatus.isNotEmpty()) Text(alprStatus, fontSize = 13.sp, color = Palette.muted, modifier = Modifier.padding(top = 6.dp))
                alprDevices.firstOrNull()?.let { n ->
                    Text(
                        "Nearest: ${Rssi.formatDistance(n.geoDistanceM)} to the ${compassName(n.geoBearingDeg ?: 0.0)}. " +
                            "Open the Radar tab and pick the 500 m or 2 km range to see them all.",
                        fontSize = 13.sp, color = Palette.alpr, modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Text("Map data © OpenStreetMap contributors (ODbL) · deflock.me", fontSize = 11.sp, color = Palette.muted, modifier = Modifier.padding(top = 6.dp))
            }
        }

        // ---------- Lens finder
        item {
            SectionTitle("Lens finder")
            ToolCard {
                Text(
                    "For cameras with no radio (SD-card only). Turns on the flashlight and filters the view so a " +
                        "lens shows up as a bright pinpoint glint. The front camera can also see infrared night-vision LEDs.",
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onLens) { Text("Open lens finder") }
            }
        }

        // ---------- settings
        item {
            SectionTitle("Scanner")
            ToolCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Classic Bluetooth discovery", fontWeight = FontWeight.SemiBold)
                        Text("Finds older non-BLE devices (car kits, speakers, some cameras). Slightly slows BLE updates.", fontSize = 12.sp, color = Palette.muted)
                    }
                    Switch(checked = classic, onCheckedChange = { ScanEngine.classicEnabled.value = it })
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Wi-Fi scans: ${status.wifiScans} done, ${status.wifiThrottled} throttled by Android. " +
                        "Android limits apps to 4 Wi-Fi scans per 2 minutes. For faster Wi-Fi updates turn off " +
                        "Developer options → \"Wi-Fi scan throttling\".",
                    fontSize = 12.sp, color = Palette.muted,
                )
                OutlinedButton(onClick = {
                    try { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                }, modifier = Modifier.padding(top = 6.dp)) { Text("Open Developer options") }
                Text(
                    "Manufacturer database: ${Vendors.ouiCount} MAC prefixes, ${Vendors.companyCount} Bluetooth company IDs.",
                    fontSize = 12.sp, color = Palette.muted, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        item {
            SectionTitle("Room sweep checklist")
            InfoCard(
                "Do this in order",
                "1) Walk the room with the Radar open and Threats tab badge in view.\n" +
                    "2) Join the room's Wi-Fi and run the camera scan above.\n" +
                    "3) Turn the lights off and use the Lens finder on smoke detectors, clocks, USB chargers, " +
                    "outlets, vents, picture frames, and anything facing the bed or shower.\n" +
                    "4) For a strong unknown signal, open it and use the beeper + direction sweep to walk it down.",
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ToolCard(content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Palette.surface), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(14.dp)) { content() }
    }
}

private fun compassName(deg: Double): String {
    val names = listOf("north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest")
    return names[(((deg % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]
}
