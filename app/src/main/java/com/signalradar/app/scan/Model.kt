package com.signalradar.app.scan

import kotlin.math.pow

enum class Source(val label: String) {
    BLE("BLE"),
    CLASSIC("Bluetooth"),
    WIFI("Wi-Fi"),
    LAN("Network"),
    ALPR("ALPR map"),
    PINEAPPLE("Pineapple"),
}

enum class Confidence(val label: String) { LOW("Weak"), MEDIUM("Likely"), HIGH("Strong") }

enum class ThreatKind(val label: String) {
    FLOCK("Flock / ALPR camera"),
    HIDDEN_CAMERA("Possible hidden camera"),
    CAMERA("Camera"),
    SMART_GLASSES("Camera glasses"),
    BODY_CAM("Body cam / police gear"),
    TRACKER("Tracker"),
}

data class Flag(val kind: ThreatKind, val confidence: Confidence, val reason: String)

data class Device(
    val key: String,
    val source: Source,
    val address: String,
    val name: String?,
    val vendor: String?,
    val category: String,
    val rssi: Int?,
    val smoothRssi: Float?,
    /** Expected RSSI at 1 m, used for the distance estimate. */
    val measuredPower: Int,
    val firstSeen: Long,
    val lastSeen: Long,
    val seenCount: Int,
    val randomizedAddress: Boolean,
    val details: List<Pair<String, String>>,
    val flags: List<Flag>,
    val rttCapable: Boolean = false,
    val rttMeters: Double? = null,
    val rttAt: Long = 0,
    val geoLat: Double? = null,
    val geoLon: Double? = null,
    val geoDistanceM: Double? = null,
    val geoBearingDeg: Double? = null,
    /** Direction found with a 360° sweep (true bearing, degrees). */
    val estBearingDeg: Float? = null,
) {
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() }
            ?: vendor?.let { "${shortVendor(it)} device" }
            ?: "Unknown device"

    val topFlag: Flag? get() = flags.maxByOrNull { it.confidence.ordinal }

    fun hasThreat(minConfidence: Confidence) = flags.any { it.confidence >= minConfidence }

    val estDistanceM: Double?
        get() = geoDistanceM
            ?: rttMeters?.takeIf { System.currentTimeMillis() - rttAt < 15_000 }
            ?: smoothRssi?.let { Rssi.distance(it, measuredPower) }

    val distanceIsMeasured: Boolean
        get() = geoDistanceM != null || (rttMeters != null && System.currentTimeMillis() - rttAt < 15_000)
}

fun shortVendor(v: String): String {
    val cut = v.split(",", " Co.", " Inc", " Ltd", " LLC", " Corp", " Technology", " Technologies", " GmbH", " S.p.A")[0].trim()
    return if (cut.length > 28) cut.take(26) + "…" else cut
}

object Rssi {
    /** Log-distance path loss. n≈2.7 is a reasonable indoor/outdoor average. */
    fun distance(rssi: Float, measuredPower: Int, n: Double = 2.7): Double =
        10.0.pow((measuredPower - rssi) / (10.0 * n)).coerceIn(0.1, 999.0)

    /** 0..1 strength, for bars and meters. */
    fun strength(rssi: Float): Float = ((rssi + 100f) / 65f).coerceIn(0f, 1f)

    fun formatDistance(m: Double?): String = when {
        m == null -> "—"
        m < 10 -> String.format("%.1f m", m)
        m < 1000 -> "${m.toInt()} m"
        else -> String.format("%.1f km", m / 1000)
    }
}

fun hex(b: ByteArray, max: Int = 32): String {
    val sb = StringBuilder()
    for (i in 0 until minOf(b.size, max)) {
        sb.append(String.format("%02X", b[i]))
        if (i % 2 == 1 && i < b.size - 1) sb.append(' ')
    }
    if (b.size > max) sb.append("…")
    return sb.toString()
}

fun ageText(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 2 -> "now"
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60}m ago"
        else -> "${s / 3600}h ago"
    }
}
