package com.signalradar.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Button
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sqrt

/**
 * Magnetic-field meter. Earth's field is ~25–65 µT and steady; hidden electronics (DC motors,
 * speakers, magnets in concealed cameras/mounts) push it well above that as you pass over them.
 * Calibrate against open air, then sweep.
 */
@Composable
fun MagnetometerCard() {
    val ctx = LocalContext.current
    var value by remember { mutableFloatStateOf(0f) }
    var baseline by remember { mutableFloatStateOf(45f) }
    var peak by remember { mutableFloatStateOf(0f) }
    var available by remember { mutableFloatStateOf(1f) } // 1 = present, 0 = absent (Float to keep it simple)

    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (sensor == null) {
            available = 0f
            onDispose { }
        } else {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    val m = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                    value = value + 0.25f * (m - value) // smooth
                    if (value > peak) peak = value
                }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            onDispose { sm.unregisterListener(listener) }
        }
    }

    ToolCard {
        Text("Magnetic field", fontWeight = FontWeight.Bold, color = Palette.text)
        if (available == 0f) {
            Text("This phone has no magnetometer.", fontSize = 12.sp, color = Palette.muted)
            return@ToolCard
        }
        val delta = value - baseline
        val elevated = delta > 25f
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
            Text(String.format("%.0f", value), fontSize = 44.sp, fontWeight = FontWeight.Bold, color = if (elevated) Palette.red else Palette.cell)
            Text(" µT", fontSize = 16.sp, color = Palette.muted, modifier = Modifier.padding(bottom = 8.dp))
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(if (elevated) "ELEVATED" else "steady", color = if (elevated) Palette.red else Palette.muted, fontWeight = FontWeight.SemiBold)
                Text("peak ${peak.toInt()} · base ${baseline.toInt()}", fontSize = 11.sp, color = Palette.muted)
            }
        }
        // bar: baseline..baseline+150 µT
        val frac = ((value - baseline) / 150f).coerceIn(0f, 1f)
        Box(
            Modifier.fillMaxWidth().height(10.dp).padding(vertical = 2.dp)
                .clip(RoundedCornerShape(5.dp)).background(Palette.surface2)
        ) {
            Box(Modifier.fillMaxWidth(frac).height(10.dp).background(if (elevated) Palette.red else Palette.cell))
        }
        Text(
            "Earth's field reads ~25–65 µT and barely moves. Calibrate in open air, then pass the phone slowly " +
                "over outlets, smoke detectors, clocks, vents and mounts — a steady jump means metal or powered electronics.",
            fontSize = 12.sp, color = Palette.muted, modifier = Modifier.padding(top = 6.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            OutlinedButton(onClick = { baseline = value }) { Text("Calibrate here") }
            OutlinedButton(onClick = { peak = 0f }) { Text("Reset peak") }
        }
    }
    Spacer(Modifier.height(4.dp))
}


/** Ultrasonic / audio-beacon detector card. */
@Composable
fun UltrasonicCard() {
    val ctx = LocalContext.current
    var hasPerm by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val running by com.signalradar.app.scan.Ultrasonic.running.collectAsState()
    val bins by com.signalradar.app.scan.Ultrasonic.bins.collectAsState()
    val peakHz by com.signalradar.app.scan.Ultrasonic.peakHz.collectAsState()
    val detected by com.signalradar.app.scan.Ultrasonic.detected.collectAsState()
    val err by com.signalradar.app.scan.Ultrasonic.error.collectAsState()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        hasPerm = ok
        if (ok) com.signalradar.app.scan.Ultrasonic.start()
    }

    DisposableEffect(Unit) { onDispose { com.signalradar.app.scan.Ultrasonic.stop() } }

    ToolCard {
        Text("Ultrasonic / audio beacons", fontWeight = FontWeight.Bold, color = Palette.text)
        Text(
            "Watches the inaudible 18–22 kHz band for tracking beacons, with a live spectrum. A phone mic tails " +
                "off at the very top, so treat a hit as \"look closer\", not proof.",
            fontSize = 12.sp, color = Palette.muted, modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().height(90.dp)) {
            val n = bins.size
            if (n == 0) return@Canvas
            val bw = size.width / n
            // mark the 18 kHz line (ultrasonic threshold): bin index = 18/24 * n
            val ultraStart = (18f / 24f * n)
            for (i in 0 until n) {
                val h = size.height * bins[i].coerceIn(0f, 1f)
                val ultra = i >= ultraStart
                drawRect(
                    color = if (ultra) Palette.red.copy(alpha = 0.9f) else Palette.cell.copy(alpha = 0.85f),
                    topLeft = androidx.compose.ui.geometry.Offset(i * bw, size.height - h),
                    size = androidx.compose.ui.geometry.Size(bw * 0.8f, h),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Text("0", fontSize = 10.sp, color = Palette.muted)
            Spacer(Modifier.weight(1f))
            Text("18 kHz", fontSize = 10.sp, color = Palette.red)
            Spacer(Modifier.weight(1f))
            Text("24 kHz", fontSize = 10.sp, color = Palette.muted)
        }
        if (running) {
            Text(
                if (detected) "⚠ Ultrasonic tone detected" else "Listening — peak ${peakHz / 1000}.${(peakHz % 1000) / 100} kHz",
                color = if (detected) Palette.red else Palette.cell,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp),
            )
        }
        err?.let { Text(it, color = Palette.red, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            if (!running) {
                Button(onClick = {
                    if (hasPerm) com.signalradar.app.scan.Ultrasonic.start()
                    else launcher.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text(if (hasPerm) "Start listening" else "Allow mic & listen") }
            } else {
                Button(onClick = { com.signalradar.app.scan.Ultrasonic.stop() }) { Text("Stop") }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}
