package com.signalradar.app.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.signalradar.app.scan.Confidence
import com.signalradar.app.scan.Device
import com.signalradar.app.scan.Heading
import com.signalradar.app.scan.Rssi
import com.signalradar.app.scan.ScanEngine
import com.signalradar.app.scan.Source
import com.signalradar.app.scan.shortVendor
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

private val RANGES = listOf(5.0, 10.0, 25.0, 50.0, 100.0, 500.0, 2000.0)

private data class Blip(val d: Device, val pos: Offset, val beyond: Boolean, val directional: Boolean)

/** Log scale so devices at 1 m and 40 m both stay readable. */
private fun radiusFrac(dist: Double, range: Double) = (ln(1 + dist) / ln(1 + range)).toFloat()
private fun fracToDist(f: Float, range: Double) = exp(f * ln(1 + range)) - 1

private fun hashAngle(key: String): Float = ((key.hashCode().toLong() and 0xFFFFFFFFL) % 3600L) / 10f

private fun layout(list: List<Device>, heading: Float, range: Double, cx: Float, cy: Float, radius: Float): List<Blip> =
    list.mapNotNull { d ->
        val dist = d.estDistanceM ?: return@mapNotNull null
        val trueBearing = d.geoBearingDeg?.toFloat() ?: d.estBearingDeg
        val angle = if (trueBearing != null) trueBearing - heading else hashAngle(d.key)
        val frac = radiusFrac(dist, range)
        val r = radius * frac.coerceAtMost(1f)
        val rad = Math.toRadians(angle.toDouble())
        Blip(d, Offset(cx + r * sin(rad).toFloat(), cy - r * cos(rad).toFloat()), frac > 1f, trueBearing != null)
    }

@Composable
fun RadarScreen(devices: List<Device>, onOpen: (String) -> Unit) {
    val heading by Heading.deg.collectAsState()
    val status by ScanEngine.status.collectAsState()
    var rangeIdx by rememberSaveable { mutableIntStateOf(2) }
    var showBle by rememberSaveable { mutableStateOf(true) }
    var showBt by rememberSaveable { mutableStateOf(true) }
    var showWifi by rememberSaveable { mutableStateOf(true) }
    var showAlpr by rememberSaveable { mutableStateOf(true) }
    var threatsOnly by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    val range = RANGES[rangeIdx]
    val now = System.currentTimeMillis()

    val shown = devices.filter { d ->
        val srcOk = when (d.source) {
            Source.BLE -> showBle
            Source.CLASSIC -> showBt
            Source.WIFI -> showWifi
            Source.ALPR -> showAlpr
            Source.LAN -> false
        }
        val fresh = d.source == Source.ALPR || now - d.lastSeen < 90_000
        srcOk && fresh && (!threatsOnly || d.hasThreat(Confidence.MEDIUM))
    }.sortedByDescending { it.smoothRssi ?: -50f }.take(120)

    val sweepT = rememberInfiniteTransition(label = "sweep")
    val sweep by sweepT.animateFloat(0f, 360f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "sweepAngle")
    val pulse by sweepT.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")

    val shownState = rememberUpdatedState(shown)
    val headingState = rememberUpdatedState(heading)
    val rangeState = rememberUpdatedState(range)

    val labelPaint = remember {
        Paint().apply { isAntiAlias = true; textSize = 28f; color = Palette.text.toArgb(); typeface = Typeface.DEFAULT }
    }
    val ringPaint = remember {
        Paint().apply { isAntiAlias = true; textSize = 26f; color = Palette.green.copy(alpha = 0.7f).toArgb() }
    }
    val northPaint = remember {
        Paint().apply { isAntiAlias = true; textSize = 34f; color = Palette.red.toArgb(); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Signal Radar", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.green)
                Text(
                    "${shown.size} on radar · ${devices.size} seen · heading ${heading.toInt()}°",
                    fontSize = 12.sp, color = Palette.muted,
                )
            }
            Text(
                listOfNotNull(
                    if (status.bleActive) "BLE" else null,
                    if (status.classicActive) "BT" else null,
                    if (status.running) "Wi-Fi" else null,
                ).joinToString(" · ").ifEmpty { "paused" },
                fontSize = 12.sp, color = Palette.green,
            )
        }

        Canvas(
            Modifier.fillMaxWidth().aspectRatio(1f).padding(8.dp).pointerInput(Unit) {
                detectTapGestures { tap ->
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val radius = minOf(w, h) / 2f - 24f
                    val blips = layout(shownState.value, headingState.value, rangeState.value, w / 2, h / 2, radius)
                    val hit = blips.minByOrNull { (it.pos - tap).getDistance() }
                    selected = if (hit != null && (hit.pos - tap).getDistance() < 70f) hit.d.key else null
                }
            }
        ) {
            val cx = size.width / 2
            val cy = size.height / 2
            val radius = minOf(size.width, size.height) / 2f - 24f
            val center = Offset(cx, cy)

            drawCircle(
                Brush.radialGradient(listOf(Color(0xFF0E2A1B), Color(0xFF06120C)), center, radius),
                radius, center,
            )
            // rings + labels
            for (i in 1..4) {
                val f = i / 4f
                drawCircle(Palette.green.copy(alpha = 0.25f), radius * f, center, style = Stroke(1.5f))
                drawContext.canvas.nativeCanvas.drawText(
                    Rssi.formatDistance(fracToDist(f, range)), cx + 6f, cy - radius * f + 26f, ringPaint
                )
            }
            drawLine(Palette.green.copy(alpha = 0.15f), Offset(cx - radius, cy), Offset(cx + radius, cy), 1f)
            drawLine(Palette.green.copy(alpha = 0.15f), Offset(cx, cy - radius), Offset(cx, cy + radius), 1f)

            // sweep: a fading wedge trailing the sweep line
            rotate(sweep - 90f, center) {
                drawCircle(
                    Brush.sweepGradient(
                        0f to Color.Transparent, 0.82f to Color.Transparent, 1f to Palette.green.copy(alpha = 0.38f),
                        center = center,
                    ),
                    radius, center,
                )
            }
            val sweepRad = Math.toRadians((sweep - 90).toDouble())
            drawLine(
                Palette.green.copy(alpha = 0.8f), center,
                Offset(cx + radius * cos(sweepRad).toFloat(), cy + radius * sin(sweepRad).toFloat()), 3f,
            )

            // north marker (red N)
            val nRad = Math.toRadians((-heading).toDouble())
            drawContext.canvas.nativeCanvas.drawText(
                "N", cx + (radius + 2f) * sin(nRad).toFloat(), cy - (radius + 2f) * cos(nRad).toFloat() + 12f, northPaint
            )

            val blips = layout(shown, heading, range, cx, cy, radius)
            // brightness boost when the sweep passes over a blip
            var labels = 0
            for (b in blips.sortedBy { it.d.hasThreat(Confidence.MEDIUM) }) {
                val d = b.d
                val threat = d.topFlag?.takeIf { it.confidence != Confidence.LOW }
                val base = if (threat != null) confColor(threat.confidence) else sourceColor(d.source)
                val bAngle = Math.toDegrees(kotlin.math.atan2((b.pos.x - cx).toDouble(), (cy - b.pos.y).toDouble())).toFloat()
                val lag = Heading.norm(sweep - Heading.norm(bAngle))
                val glow = if (lag < 60f) 1f - lag / 60f else 0f
                val age = (now - d.lastSeen) / 1000f
                val alpha = if (d.source == Source.ALPR) 1f else (1f - age / 90f).coerceIn(0.25f, 1f)
                val r = 7f + 9f * (d.smoothRssi?.let { Rssi.strength(it) } ?: 0.5f)
                drawBlip(b, base, r, alpha, glow, threat != null, pulse, selected == d.key)
                if (labels < 10 && (threat != null || selected == d.key || (d.smoothRssi ?: -100f) > -70f)) {
                    labels++
                    labelPaint.color = base.copy(alpha = alpha).toArgb()
                    val name = d.name ?: d.vendor?.let { shortVendor(it) } ?: continue
                    drawContext.canvas.nativeCanvas.drawText(name.take(18), b.pos.x + r + 4f, b.pos.y + 9f, labelPaint)
                }
            }
            // you
            drawCircle(Palette.green, 7f, center)
            drawCircle(Palette.green.copy(alpha = 0.4f), 14f, center, style = Stroke(2f))
        }

        // selected device card
        val sel = shown.firstOrNull { it.key == selected } ?: devices.firstOrNull { it.key == selected }
        if (sel != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Palette.surface2),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(sel.displayName, fontWeight = FontWeight.Bold)
                        Text(listOfNotNull(sel.vendor?.let { shortVendor(it) }, sel.category).joinToString(" · "), fontSize = 12.sp, color = Palette.muted)
                        Text(
                            listOfNotNull(sel.rssi?.let { "$it dBm" }, "~" + Rssi.formatDistance(sel.estDistanceM)).joinToString(" · "),
                            fontSize = 13.sp, color = sourceColor(sel.source),
                        )
                        sel.topFlag?.let { Text(it.kind.label + " — " + it.confidence.label, color = confColor(it.confidence), fontSize = 12.sp) }
                    }
                    Button(onClick = { onOpen(sel.key) }) { Text("Find") }
                }
            }
        }

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Range", color = Palette.muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterVertically))
            RANGES.forEachIndexed { i, r ->
                FilterChip(
                    selected = rangeIdx == i, onClick = { rangeIdx = i },
                    label = { Text(Rssi.formatDistance(r).replace(".0", "")) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Palette.greenDim),
                )
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LegendChip("BLE", Palette.ble, showBle) { showBle = !showBle }
            LegendChip("Bluetooth", Palette.classic, showBt) { showBt = !showBt }
            LegendChip("Wi-Fi", Palette.wifi, showWifi) { showWifi = !showWifi }
            LegendChip("ALPR map", Palette.alpr, showAlpr) { showAlpr = !showAlpr }
            LegendChip("Threats only", Palette.red, threatsOnly) { threatsOnly = !threatsOnly }
        }
        Text(
            "Distance comes from signal strength (rough: walls and bodies change it a lot). Filled dots with a ring " +
                "have a real direction — mapped ALPR cameras, or devices you've done a 360° sweep on. Other dots are " +
                "placed at a fixed spot around the ring. Hold the phone flat, top pointing ahead.",
            fontSize = 12.sp, color = Palette.muted, modifier = Modifier.padding(16.dp),
        )
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = { ScanEngine.clear(); selected = null }) { Text("Clear list") }
            Spacer(Modifier.width(4.dp))
        }
        Spacer(Modifier.padding(bottom = 16.dp))
    }
}

private fun DrawScope.drawBlip(b: Blip, color: Color, r: Float, alpha: Float, glow: Float, threat: Boolean, pulse: Float, selected: Boolean) {
    val a = (alpha * (0.55f + 0.45f * glow)).coerceIn(0f, 1f)
    if (threat) drawCircle(color.copy(alpha = 0.35f * pulse), r * 2.4f, b.pos)
    if (b.directional) {
        drawCircle(color.copy(alpha = a), r, b.pos)
        drawCircle(Color.White.copy(alpha = a * 0.8f), r + 4f, b.pos, style = Stroke(2f))
    } else {
        drawCircle(color.copy(alpha = a * 0.85f), r, b.pos)
    }
    if (b.beyond) drawCircle(color.copy(alpha = a), r + 8f, b.pos, style = Stroke(2f))
    if (selected) drawCircle(Color.White, r + 10f, b.pos, style = Stroke(3f))
}

@Composable
private fun LegendChip(label: String, color: Color, on: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = on, onClick = onClick, label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = color.copy(alpha = 0.25f), selectedLabelColor = color,
        ),
    )
}
