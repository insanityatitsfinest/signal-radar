package com.signalradar.app.ui

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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

