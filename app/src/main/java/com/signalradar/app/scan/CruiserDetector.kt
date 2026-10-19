package com.signalradar.app.scan

import android.net.wifi.ScanResult
import java.util.Locale

enum class ThreatConfidence(val label: String) {
    HIGH("HIGH - Confirmed Public Safety / Cruiser Hub"),
    MEDIUM("MEDIUM - Fleet Router Hardware / SSID")
}

data class CruiserHotspot(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val frequencyMHz: Int,
    val hardwareVendor: String,
    val confidence: ThreatConfidence
)

object CruiserDetector {

    // Ruggedized vehicle router & dashcam/bodycam OUI prefixes
    private val CRUISER_OUIS = mapOf(
        "00:25:DF" to "Axon Fleet / Bodycam Hub",
        "00:30:44" to "Cradlepoint Vehicle Router",
        "00:E0:1C" to "Cradlepoint Vehicle Router",
        "00:A0:D5" to "Sierra Wireless AirLink",
        "28:A3:31" to "Sierra Wireless AirLink",
        "50:13:9D" to "Sierra Wireless AirLink",
        "64:CE:6E" to "Sierra Wireless AirLink",
        "84:DB:2F" to "Sierra Wireless AirLink",
        "CC:93:4A" to "Sierra Wireless AirLink",
        "00:1A:DD" to "Pepwave Mobile Router",
        "10:56:CA" to "Peplink Mobile Router",
        "A8:C0:EA" to "Pepwave Mobile Router",
        "00:11:6E" to "Peplink Mobile Router"
    )

    private val CRUISER_SSID_REGEX = Regex(
        pattern = """(?i)^(axon|fleet[-_]|ibr(900|1700)|r1900|r2400|cradlepoint|airlink|mp70|mg90|xr[89]0|pepwave|peplink|firstnet[-_]|.*[-_](mdt|patrol|cruiser|cad|alpr|lpr|pd|sheriff|trooper)\b)"""
    )

    fun analyze(scanResult: ScanResult): CruiserHotspot? {
        val rawSsid = scanResult.SSID?.removeSurrounding("\"")?.trim().orEmpty()
        val displaySsid = rawSsid.ifEmpty { "<Hidden Fleet SSID>" }
        val bssid = scanResult.BSSID?.uppercase(Locale.US) ?: return null

        val oui = bssid.take(8)
        val vendor = CRUISER_OUIS[oui]
        val matchesSsidPattern = rawSsid.isNotEmpty() && CRUISER_SSID_REGEX.containsMatchIn(rawSsid)

        return when {
            oui == "00:25:DF" || (vendor != null && matchesSsidPattern) -> {
                CruiserHotspot(displaySsid, bssid, scanResult.level, scanResult.frequency, vendor ?: "Public Safety VAN", ThreatConfidence.HIGH)
            }
            vendor != null -> {
                CruiserHotspot(displaySsid, bssid, scanResult.level, scanResult.frequency, vendor, ThreatConfidence.MEDIUM)
            }
            matchesSsidPattern -> {
                CruiserHotspot(displaySsid, bssid, scanResult.level, scanResult.frequency, "Matched Cruiser/MDT SSID", ThreatConfidence.MEDIUM)
            }
            else -> null
        }
    }
}