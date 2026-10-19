package com.signalradar.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.signalradar.app.scan.Device
import com.signalradar.app.scan.Heading
import com.signalradar.app.scan.Rssi
import com.signalradar.app.scan.ScanEngine
import com.signalradar.app.scan.Source
import com.signalradar.app.scan.SweepRecorder
import com.signalradar.app.scan.ageText
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.sin

@SuppressLint("MissingPermission")
@Composable
fun DetailScreen(key: String, devices: List<Device>, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val d = devices.firstOrNull { it.key == key }
    val heading by Heading.deg.collectAsState()
    val latest = rememberUpdatedState(d)
    val headingNow = rememberUpdatedState(heading)
    var beep by remember { mutableStateOf(false) }
    var sweeping by remember { mutableStateOf(false) }
    val recorder = remember(key) { SweepRecorder() }
    var sweepTick by remember { mutableIntStateOf(0) }
    var rttOn by remember { mutableStateOf(false) }
    var rttMsg by remember { mutableStateOf<String?>(null) }
    val now = System.currentTimeMillis()

    // Faster Wi-Fi refresh while hunting a Wi-Fi device (works fully if scan throttling is off in Developer options)
    LaunchedEffect(key) {
        if (key.startsWith("W:")) while (isActive) {
            ScanEngine.requestWifiScan(); delay(5000)
        }
    }

    // Geiger-counter beeps: faster = stronger
    val tone = remember { try { ToneGenerator(AudioManager.STREAM_MUSIC, 80) } catch (_: Exception) { null } }
    DisposableEffect(Unit) { onDispose { tone?.release() } }
    LaunchedEffect(beep) {
        while (beep && isActive) {
            val s = latest.value?.smoothRssi
            if (s == null) { delay(500); continue }
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 40)
            val strength = Rssi.strength(s)
            delay((1300 - 1220 * strength).toLong().coerceAtLeast(70))
        }
    }

    // 360° sweep: sample RSSI by compass direction
    LaunchedEffect(sweeping) {
        if (sweeping) recorder.reset()
        while (sweeping && isActive) {
            latest.value?.rssi?.let { r ->
                if (System.currentTimeMillis() - (latest.value?.lastSeen ?: 0) < 60_000) recorder.add(headingNow.value, r.toFloat())
            }
            sweepTick++
            delay(120)
        }
    }

    // Wi-Fi RTT: real distance from round-trip time, if the access point supports it
    LaunchedEffect(rttOn) {
        if (!rttOn) return@LaunchedEffect
        val rtt = ctx.getSystemService(WifiRttManager::class.java)
        if (rtt == null || !rtt.isAvailable) {
            rttMsg = "Wi-Fi RTT isn't available right now (Wi-Fi or Location may be off)."
            rttOn = false
            return@LaunchedEffect
        }
        while (rttOn && isActive) {
            val sr = ScanEngine.wifiScanResult(key)
            if (sr == null) { rttMsg = "Waiting for a scan result…"; delay(1000); continue }
            try {
                val req = RangingRequest.Builder().addAccessPoint(sr).build()
                rtt.startRanging(req, ctx.mainExecutor, object : RangingResultCallback() {
                    override fun onRangingFailure(code: Int) { rttMsg = "RTT failed (code $code)" }
                    override fun onRangingResults(results: List<RangingResult>) {
                        val r = results.firstOrNull()
                        if (r != null && r.status == RangingResult.STATUS_SUCCESS) {
                            ScanEngine.setRtt(key, r.distanceMm / 1000.0)
                            rttMsg = String.format("RTT: %.1f m ± %.1f m", r.distanceMm / 1000.0, r.distanceStdDevMm / 1000.0)
                        } else rttMsg = "No RTT reply from this access point"
                    }
                })
            } catch (e: Exception) {
                rttMsg = "RTT error: ${e.message}"
            }
            delay(1000)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(d?.displayName ?: "Device", fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
        }
        if (d == null) {
            InfoCard("Out of range", "This device hasn't been seen for a while.")
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tag(d.source.label, sourceColor(d.source))
                    Tag(d.category, Palette.muted)
                }
                d.vendor?.let { Text(it, color = Palette.text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            }
            if (d.flags.isNotEmpty()) item {
                SectionTitle("Flags")
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    d.flags.forEach { FlagLine(it) }
                }
            }

            item { FindPanel(d, now) }

            if (d.source != Source.ALPR && d.source != Source.LAN) item {
                SectionTitle("Hunt it down")
                Card(colors = CardDefaults.cardColors(containerColor = Palette.surface), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Proximity beeper", fontWeight = FontWeight.SemiBold)
                                Text("Beeps faster as the signal gets stronger", fontSize = 12.sp, color = Palette.muted)
                            }
                            Switch(checked = beep, onCheckedChange = { beep = it })
                        }
                        if (d.rttCapable) {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Wi-Fi RTT distance", fontWeight = FontWeight.SemiBold)
                                    Text(rttMsg ?: "This access point supports precise time-of-flight ranging", fontSize = 12.sp, color = Palette.muted)
                                }
                                Switch(checked = rttOn, onCheckedChange = { rttOn = it })
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("Direction sweep", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Hold the phone flat in front of your chest and turn slowly in a full circle (about 20 s). " +
                                "Your body blocks the signal from behind, so it peaks when you face the device." +
                                if (d.source == Source.WIFI) " Wi-Fi updates slowly — this works best on Bluetooth devices." else "",
                            fontSize = 12.sp, color = Palette.muted,
                        )
                        Spacer(Modifier.height(8.dp))
                        SweepPlot(recorder, heading, d.estBearingDeg, sweepTick)
                        val est = recorder.estimate()
                        Text(
                            when {
                                sweeping && est == null -> "Covered ${(recorder.coverage() * 100).toInt()}% of the circle — keep turning"
                                sweeping -> "Strongest toward ${est!!.toInt()}° — keep turning or stop to save"
                                d.estBearingDeg != null -> "Saved direction: ${d.estBearingDeg.toInt()}° (${compass(d.estBearingDeg)}) — shown on the radar"
                                else -> "No direction yet"
                            },
                            fontSize = 13.sp, color = Palette.green,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                            Button(onClick = {
                                if (sweeping) {
                                    recorder.estimate()?.let { ScanEngine.setEstBearing(key, it) }
                                }
                                sweeping = !sweeping
                            }) { Text(if (sweeping) "Stop & save" else "Start sweep") }
                            if (d.estBearingDeg != null && !sweeping) OutlinedButton(onClick = { ScanEngine.setEstBearing(key, null) }) { Text("Clear") }
                        }
                    }
                }
            }

            if (d.source == Source.ALPR && d.geoLat != null && d.geoLon != null) item {
                Row(Modifier.padding(16.dp)) {
                    Button(onClick = {
                        val uri = Uri.parse("geo:${d.geoLat},${d.geoLon}?q=${d.geoLat},${d.geoLon}(ALPR camera)")
                        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) {}
                    }) { Text("Open in Maps") }
                }
            }

            item { SectionTitle("Details") }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Detail("Name", d.name ?: "(none broadcast)")
                    d.details.forEach { (k, v) -> Detail(k, v) }
                    if (d.source != Source.ALPR && d.source != Source.LAN) {
                        Detail("First seen", ageText(now - d.firstSeen))
                        Detail("Last seen", ageText(now - d.lastSeen))
                        Detail("Times seen", d.seenCount.toString())
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun FindPanel(d: Device, now: Long) {
    val color = sourceColor(d.source)
    val hist = remember(d.lastSeen, d.key) { ScanEngine.history(d.key) }
    Card(colors = CardDefaults.cardColors(containerColor = Palette.surface), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            if (d.source == Source.ALPR) {
                Text(Rssi.formatDistance(d.geoDistanceM), fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Palette.alpr)
                d.geoBearingDeg?.let { Text("Bearing ${it.toInt()}° (${compass(it.toFloat())}) from you", color = Palette.text) }
                return@Column
            }
            if (d.source == Source.LAN) {
                Text("On your network", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.lan)
                Text("Network devices have no signal strength. Check the IP in a browser, or look at the router's device list.", fontSize = 12.sp, color = Palette.muted)
                return@Column
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${d.smoothRssi?.toInt() ?: "—"}", fontSize = 56.sp, fontWeight = FontWeight.Bold, color = color)
                Text(" dBm", fontSize = 18.sp, color = color, modifier = Modifier.padding(bottom = 10.dp))
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text("~" + Rssi.formatDistance(d.estDistanceM), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(if (d.distanceIsMeasured) "measured (RTT)" else "estimated", fontSize = 12.sp, color = Palette.muted)
                }
            }
            LinearProgressIndicator(
                progress = { d.smoothRssi?.let { Rssi.strength(it) } ?: 0f },
                color = color, trackColor = color.copy(alpha = 0.15f),
                modifier = Modifier.fillMaxWidth().height(10.dp).padding(vertical = 2.dp),
            )
            val trend = trend(hist)
            Text(
                when {
                    now - d.lastSeen > 15_000 -> "Not heard for ${ageText(now - d.lastSeen).removeSuffix(" ago")} — move around"
                    trend > 2.5f -> "▲ Getting warmer"
                    trend < -2.5f -> "▼ Getting colder"
                    else -> "● Holding steady"
                },
                color = when {
                    trend > 2.5f -> Palette.green
                    trend < -2.5f -> Palette.red
                    else -> Palette.muted
                },
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(8.dp))
            Sparkline(hist, color)
        }
    }
}

private fun trend(h: List<Int>): Float {
    if (h.size < 12) return 0f
    val recent = h.takeLast(5).average()
    val before = h.dropLast(5).takeLast(10).average()
    return (recent - before).toFloat()
}

@Composable
private fun Sparkline(h: List<Int>, color: Color) {
    Canvas(Modifier.fillMaxWidth().height(70.dp)) {
        if (h.size < 2) return@Canvas
        val pts = h.takeLast(120)
        val min = -100f
        val max = -30f
        val p = Path()
        pts.forEachIndexed { i, v ->
            val x = size.width * i / (pts.size - 1).toFloat()
            val y = size.height * (1f - ((v - min) / (max - min)).coerceIn(0f, 1f))
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        drawPath(p, color, style = Stroke(3f))
    }
}

@Composable
private fun SweepPlot(rec: SweepRecorder, heading: Float, saved: Float?, tick: Int) {
    val values = remember(tick, heading) { rec.values() }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(200.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = size.minDimension / 2 - 8f
            drawCircle(Palette.green.copy(alpha = 0.2f), r, c, style = Stroke(2f))
            drawCircle(Palette.green.copy(alpha = 0.1f), r / 2, c, style = Stroke(1f))
            val valid = values.filter { !it.isNaN() }
            val lo = (valid.minOrNull() ?: -100f) - 3f
            val hi = (valid.maxOrNull() ?: -30f)
            val step = 360f / values.size
            // north-up polar plot of signal by direction
            values.forEachIndexed { i, v ->
                if (v.isNaN()) return@forEachIndexed
                val frac = ((v - lo) / (hi - lo).coerceAtLeast(1f)).coerceIn(0.05f, 1f)
                val a = Math.toRadians((i * step + step / 2).toDouble())
                drawLine(
                    Palette.green.copy(alpha = 0.35f + 0.65f * frac), c,
                    Offset(c.x + r * frac * sin(a).toFloat(), c.y - r * frac * cos(a).toFloat()), 10f,
                )
            }
            val h = Math.toRadians(heading.toDouble())
            drawLine(Color.White, c, Offset(c.x + r * sin(h).toFloat(), c.y - r * cos(h).toFloat()), 2f)
            (rec.estimate() ?: saved)?.let { e ->
                val a = Math.toRadians(e.toDouble())
                drawLine(Palette.red, c, Offset(c.x + (r + 6f) * sin(a).toFloat(), c.y - (r + 6f) * cos(a).toFloat()), 5f)
            }
        }
        Text("N", color = Palette.red, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopCenter))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Text("white = you're facing · red = strongest direction", fontSize = 11.sp, color = Palette.muted)
    }
    Spacer(Modifier.width(1.dp))
}

private fun compass(deg: Float): String {
    val names = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return names[(((deg % 360 + 360) % 360 + 22.5f) / 45f).toInt() % 8]
}

@Composable
private fun Detail(k: String, v: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(k, fontSize = 11.sp, color = Palette.muted)
        Text(v, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Palette.text)
    }
}
