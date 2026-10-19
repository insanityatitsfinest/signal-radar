package com.signalradar.app.scan

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/** Compass heading (true north once a location is known), smoothed. */
object Heading {
    private val _deg = MutableStateFlow(0f)
    val deg: StateFlow<Float> = _deg
    val available = MutableStateFlow(true)

    @Volatile var declination = 0f
    private var sm: SensorManager? = null
    private var users = 0
    private var have = false

    private val listener = object : SensorEventListener {
        private val rot = FloatArray(9)
        private val o = FloatArray(3)
        override fun onSensorChanged(e: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rot, e.values)
            SensorManager.getOrientation(rot, o)
            val a = norm(Math.toDegrees(o[0].toDouble()).toFloat() + declination)
            if (!have) {
                have = true
                _deg.value = a
                return
            }
            val prev = _deg.value
            val d = diff(a, prev)
            _deg.value = norm(prev + d * if (abs(d) > 30) 0.6f else 0.2f)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun start(ctx: Context) {
        if (users++ > 0) return
        val m = ctx.getSystemService(SensorManager::class.java) ?: return
        sm = m
        val s = m.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (s == null) {
            available.value = false
            return
        }
        m.registerListener(listener, s, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        if (users == 0) return
        if (--users == 0) sm?.unregisterListener(listener)
    }

    fun norm(a: Float): Float = ((a % 360f) + 360f) % 360f

    /** Signed smallest difference a-b in degrees (-180..180). */
    fun diff(a: Float, b: Float): Float {
        var d = (a - b) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }
}

/**
 * Finds the direction of a transmitter by turning in a circle: your body blocks signal from behind you,
 * so the reading is strongest when you face the device.
 */
class SweepRecorder(private val buckets: Int = 36) {
    private val sum = FloatArray(buckets)
    private val cnt = IntArray(buckets)
    private val step = 360f / buckets

    fun reset() {
        sum.fill(0f); cnt.fill(0)
    }

    fun add(headingDeg: Float, rssi: Float) {
        val b = (Heading.norm(headingDeg) / step).toInt().coerceIn(0, buckets - 1)
        sum[b] += rssi
        cnt[b]++
    }

    fun values(): FloatArray = FloatArray(buckets) { if (cnt[it] == 0) Float.NaN else sum[it] / cnt[it] }

    fun coverage(): Float = cnt.count { it > 0 }.toFloat() / buckets

    /** Returns the estimated bearing, or null if not enough of the circle has been covered. */
    fun estimate(): Float? {
        if (coverage() < 0.6f) return null
        val v = values()
        var best = -1
        var bestVal = -1e9f
        for (i in 0 until buckets) {
            // average with neighbours to smooth noise
            var s = 0f
            var n = 0
            for (k in -2..2) {
                val x = v[(i + k + buckets) % buckets]
                if (!x.isNaN()) { s += x; n++ }
            }
            if (n == 0) continue
            val avg = s / n
            if (avg > bestVal) { bestVal = avg; best = i }
        }
        return if (best < 0) null else best * step + step / 2
    }
}
