package com.signalradar.app.scan

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Listens on the mic and watches the near-ultrasonic band (18–22 kHz) for the kind of inaudible
 * tones used by cross-device tracking beacons, plus a live spectrum. 48 kHz sampling gives headroom
 * to ~24 kHz; a phone mic's response rolls off up the top, so treat this as "something is pinging up
 * here", not lab-grade.
 */
@SuppressLint("MissingPermission")
object Ultrasonic {
    private const val RATE = 48000
    private const val N = 4096 // FFT size → ~11.7 Hz per bin
    const val DISPLAY_BINS = 64

    val running = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val bins = MutableStateFlow(FloatArray(DISPLAY_BINS)) // 0..1 magnitudes, 0..24 kHz
    val peakHz = MutableStateFlow(0)
    val ultraScore = MutableStateFlow(0f) // near-ultrasonic energy vs broadband floor
    val detected = MutableStateFlow(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val window = FloatArray(N) { 0.5f - 0.5f * cos(2.0 * Math.PI * it / (N - 1)).toFloat() }

    fun start() {
        if (job?.isActive == true) return
        error.value = null
        detected.value = false
        running.value = true
        job = scope.launch {
            try {
                loop()
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) error.value = e.message ?: "audio error"
            } finally {
                running.value = false
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        running.value = false
    }

    private fun newRecorder(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        val bufSize = maxOf(min, N * 4)
        // UNPROCESSED has the flattest high-frequency response; fall back if the device lacks it.
        for (src in intArrayOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            try {
                val r = AudioRecord(src, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
                if (r.state == AudioRecord.STATE_INITIALIZED) return r
                r.release()
            } catch (_: Exception) {
            }
        }
        return null
    }

    private suspend fun loop() {
        val rec = newRecorder() ?: run { error.value = "Couldn't open the microphone."; return }
        val buf = ShortArray(N)
        val re = FloatArray(N)
        val im = FloatArray(N)
        var hits = 0
        try {
            rec.startRecording()
            while (scope.isActive && running.value) {
                var off = 0
                while (off < N) {
                    val r = rec.read(buf, off, N - off)
                    if (r <= 0) break
                    off += r
                }
                if (off < N) continue

                for (i in 0 until N) {
                    re[i] = buf[i] / 32768f * window[i]
                    im[i] = 0f
                }
                fft(re, im)

                val half = N / 2
                val mag = FloatArray(half)
                for (i in 0 until half) mag[i] = hypot(re[i], im[i])

                // display bins (linear 0..24 kHz), log-scaled for visibility
                val out = FloatArray(DISPLAY_BINS)
                val per = half / DISPLAY_BINS
                var dispMax = 1e-6f
                for (b in 0 until DISPLAY_BINS) {
                    var s = 0f
                    for (k in 0 until per) s += mag[b * per + k]
                    val v = ln(1f + s)
                    out[b] = v
                    if (v > dispMax) dispMax = v
                }
                for (b in out.indices) out[b] = (out[b] / dispMax).coerceIn(0f, 1f)
                bins.value = out

                // peak frequency overall
                var pk = 0; var pkv = 0f
                for (i in 2 until half) if (mag[i] > pkv) { pkv = mag[i]; pk = i }
                peakHz.value = pk * RATE / N

                // near-ultrasonic 18–22 kHz vs a mid-band floor (1–8 kHz median-ish)
                val nearMax = bandMax(mag, 18000, 22000)
                val floor = bandMean(mag, 1000, 8000) + 1e-6f
                val score = (nearMax / floor).coerceIn(0f, 50f)
                ultraScore.value = score
                if (score > 6f) hits++ else hits = (hits - 1).coerceAtLeast(0)
                detected.value = hits >= 3
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
    }

    private fun bin(hz: Int) = (hz.toLong() * N / RATE).toInt().coerceIn(0, N / 2 - 1)
    private fun bandMax(mag: FloatArray, loHz: Int, hiHz: Int): Float {
        var m = 0f
        for (i in bin(loHz)..bin(hiHz)) if (mag[i] > m) m = mag[i]
        return m
    }
    private fun bandMean(mag: FloatArray, loHz: Int, hiHz: Int): Float {
        var s = 0f; var n = 0
        for (i in bin(loHz)..bin(hiHz)) { s += mag[i]; n++ }
        return if (n == 0) 0f else s / n
    }

    /** In-place iterative radix-2 Cooley–Tukey FFT. N must be a power of two. */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wRe = cos(ang).toFloat()
            val wIm = sqrt(1f - wRe * wRe) * -1f // sin(ang), negative for -2π/len
            var i = 0
            while (i < n) {
                var curRe = 1f; var curIm = 0f
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val tRe = re[b] * curRe - im[b] * curIm
                    val tIm = re[b] * curIm + im[b] * curRe
                    re[b] = re[a] - tRe; im[b] = im[a] - tIm
                    re[a] += tRe; im[a] += tIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
