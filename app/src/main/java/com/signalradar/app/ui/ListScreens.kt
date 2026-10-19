package com.signalradar.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.signalradar.app.scan.Confidence
import com.signalradar.app.scan.Device
import com.signalradar.app.scan.Source
import com.signalradar.app.scan.ThreatKind

@Composable
fun DevicesScreen(devices: List<Device>, onOpen: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    val now = System.currentTimeMillis()

    val q = query.trim().lowercase()
    val list = devices.filter { d ->
        (filter == null || d.source.name == filter) &&
            (q.isEmpty() || listOfNotNull(d.name, d.vendor, d.address, d.category).any { it.lowercase().contains(q) })
    }.let { l ->
        when (sort) {
            1 -> l.sortedBy { it.displayName.lowercase() }
            2 -> l.sortedByDescending { it.lastSeen }
            3 -> l.sortedByDescending { it.firstSeen }
            else -> l // already strongest-first
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text("Search name, maker, MAC…") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All ${devices.size}") })
            Source.entries.forEach { s ->
                val n = devices.count { it.source == s }
                if (n > 0) FilterChip(
                    selected = filter == s.name, onClick = { filter = if (filter == s.name) null else s.name },
                    label = { Text("${s.label} $n") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = sourceColor(s).copy(alpha = 0.25f), selectedLabelColor = sourceColor(s)),
                )
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Strongest", "Name", "Last seen", "Newest").forEachIndexed { i, label ->
                FilterChip(selected = sort == i, onClick = { sort = i }, label = { Text(label) })
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.key }) { d -> DeviceRow(d, now) { onOpen(d.key) } }
            if (list.isEmpty()) item {
                InfoCard(
                    "Nothing yet",
                    if (devices.isEmpty()) "Scanning… Bluetooth devices usually appear within seconds; Wi-Fi networks within 30 seconds."
                    else "No devices match this filter.",
                )
            }
        }
    }
}

@Composable
fun ThreatsScreen(devices: List<Device>, onOpen: (String) -> Unit) {
    var showWeak by rememberSaveable { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val min = if (showWeak) Confidence.LOW else Confidence.MEDIUM
    val flagged = devices.filter { it.hasThreat(min) }
    val groups = ThreatKind.entries.map { k ->
        k to flagged.filter { d -> d.flags.any { it.kind == k && it.confidence >= min } }
            .sortedWith(compareByDescending<Device> { d -> d.flags.filter { it.kind == k }.maxOf { it.confidence.ordinal } }.thenByDescending { it.smoothRssi ?: -200f })
    }.filter { it.second.isNotEmpty() }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Threats", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.green)
                    Text("${flagged.size} flagged of ${devices.size} devices", fontSize = 12.sp, color = Palette.muted)
                }
                Text("Weak matches", fontSize = 12.sp, color = Palette.muted)
                Switch(checked = showWeak, onCheckedChange = { showWeak = it }, modifier = Modifier.padding(start = 6.dp))
            }
        }
        if (groups.isEmpty()) item {
            InfoCard(
                "Nothing flagged right now",
                "Keep the app open while you walk the area. Cameras that only use Wi-Fi show up within ~30 s; " +
                    "for a rental or hotel room also run Tools → \"Scan this Wi-Fi for cameras\" and the Lens finder. " +
                    "For Flock cameras, load the ALPR map in Tools.",
            )
        }
        groups.forEach { (kind, list) ->
            item(key = "h-${kind.name}") { SectionTitle("${kind.label} (${list.size})") }
            items(list, key = { "${kind.name}-${it.key}" }) { d ->
                Column {
                    DeviceRow(d, now) { onOpen(d.key) }
                    d.flags.filter { it.kind == kind }.forEach { f ->
                        Row(Modifier.padding(start = 24.dp, end = 16.dp, bottom = 6.dp)) { FlagLine(f) }
                    }
                }
            }
        }
        item {
            SectionTitle("How detection works")
            InfoCard(
                "Flock cameras",
                "Strong signs: the Flock Safety MAC prefix B4:1E:52, the XUNTONG (0x09C8) battery-pack Bluetooth ID, " +
                    "Raven gunshot-detector services, and \"Flock-…\" hotspots. Newer Flock units are mostly radio-quiet " +
                    "(the best current method needs a Wi-Fi monitor-mode sniffer, which phones can't do), so the ALPR map " +
                    "in Tools is the most reliable way to know where they are.",
                Palette.alpr,
            )
            InfoCard(
                "Hidden cameras",
                "Cheap Wi-Fi spy cams usually broadcast a hotspot named after their app (V380, HDWiFiCam, LookCam, " +
                    "iCSee…) or are made by camera chip / brand OUIs. On a network you're connected to, the network " +
                    "scan finds cameras by their streaming ports. Cameras that only record to an SD card have no radio — " +
                    "use the Lens finder for those.",
                Palette.amber,
            )
            InfoCard(
                "Trackers & glasses",
                "AirTags away from their owner, Tile, SmartTag and Google Find Hub tags are flagged, as are Meta / " +
                    "Ray-Ban / Snap camera glasses (by their Bluetooth manufacturer ID).",
                Palette.ble,
            )
        }
    }
}
