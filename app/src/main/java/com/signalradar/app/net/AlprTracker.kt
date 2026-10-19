package com.signalradar.app.net

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.GeomagneticField
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.signalradar.app.scan.Confidence
import com.signalradar.app.scan.Device
import com.signalradar.app.scan.Flag
import com.signalradar.app.scan.Heading
import com.signalradar.app.scan.ScanEngine
import com.signalradar.app.scan.Source
import com.signalradar.app.scan.ThreatKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume

/**
 * License-plate (ALPR) cameras near you, from the crowdsourced map behind deflock.me
 * (OpenStreetMap nodes tagged surveillance:type=ALPR). This finds Flock cameras that stay radio-silent.
 */
@SuppressLint("MissingPermission")
object AlprTracker {
    data class Cam(val id: Long, val lat: Double, val lon: Double, val tags: Map<String, String>)

    val cams = MutableStateFlow<List<Cam>>(emptyList())
    val status = MutableStateFlow("")
    val loading = MutableStateFlow(false)
    val location = MutableStateFlow<Location?>(null)
    val tracking = MutableStateFlow(false)
    private var listener: LocationListener? = null

    private val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )

    suspend fun load(ctx: Context, radiusM: Int) {
        loading.value = true
        try {
            status.value = "Getting your location…"
            val loc = currentLocation(ctx)
            if (loc == null) {
                status.value = "Couldn't get a location fix. Turn on Location and try again outdoors."
                return
            }
            setLocation(loc)
            status.value = "Downloading ALPR camera map…"
            val q = "[out:json][timeout:25];node[\"surveillance:type\"=\"ALPR\"](around:$radiusM,${loc.latitude},${loc.longitude});out body;"
            var body: String? = null
            var lastErr: String? = null
            for (ep in ENDPOINTS) {
                try {
                    body = post(ep, q)
                    break
                } catch (e: Exception) {
                    lastErr = e.message
                }
            }
            if (body == null) {
                status.value = "Map servers didn't answer ($lastErr). Try again in a minute."
                return
            }
            val els = JSONObject(body).optJSONArray("elements")
            val out = ArrayList<Cam>()
            if (els != null) for (i in 0 until els.length()) {
                val e = els.getJSONObject(i)
                val tags = HashMap<String, String>()
                e.optJSONObject("tags")?.let { t -> t.keys().forEach { k -> tags[k] = t.optString(k) } }
                out += Cam(e.getLong("id"), e.getDouble("lat"), e.getDouble("lon"), tags)
            }
            cams.value = out
            status.value = if (out.isEmpty()) "No mapped ALPR cameras within ${radiusM / 1000.0} km." else "${out.size} mapped ALPR cameras within ${radiusM / 1000.0} km."
            publish()
        } catch (e: Exception) {
            status.value = "Error: ${e.message}"
        } finally {
            loading.value = false
        }
    }

    private suspend fun post(endpoint: String, query: String): String = withContext(Dispatchers.IO) {
        val c = URL(endpoint).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 15_000
            c.readTimeout = 40_000
            c.setRequestProperty("User-Agent", "SignalRadar/1.0 (Android; personal ALPR awareness)")
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
            if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private suspend fun currentLocation(ctx: Context): Location? {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { p -> try { lm.isProviderEnabled(p) } catch (_: Exception) { false } }
        val recent = providers.mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }
            .filter { System.currentTimeMillis() - it.time < 2 * 60_000L }
            .minByOrNull { it.accuracy }
        if (recent != null) return recent
        for (p in providers) {
            val l = withTimeoutOrNull(12_000) {
                suspendCancellableCoroutine<Location?> { cont ->
                    try {
                        lm.getCurrentLocation(p, null, ctx.mainExecutor) { loc -> if (cont.isActive) cont.resume(loc) }
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
            if (l != null) return l
        }
        return providers.mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }.maxByOrNull { it.time }
    }

    private fun setLocation(loc: Location) {
        location.value = loc
        Heading.declination = GeomagneticField(
            loc.latitude.toFloat(), loc.longitude.toFloat(), loc.altitude.toFloat(), System.currentTimeMillis()
        ).declination
    }

    /** Keep distances / bearings updated while you move. */
    fun startTracking(ctx: Context) {
        if (listener != null) return
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        val l = LocationListener { loc ->
            setLocation(loc)
            publish()
        }
        val provider = if (lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)) LocationManager.FUSED_PROVIDER else LocationManager.GPS_PROVIDER
        try {
            lm.requestLocationUpdates(provider, 2000L, 3f, l, Looper.getMainLooper())
            listener = l
            tracking.value = true
        } catch (_: Exception) {
        }
    }

    fun stopTracking(ctx: Context) {
        val l = listener ?: return
        try { ctx.getSystemService(LocationManager::class.java)?.removeUpdates(l) } catch (_: Exception) {}
        listener = null
        tracking.value = false
    }

    fun clear() {
        cams.value = emptyList()
        status.value = ""
        ScanEngine.replaceSource(Source.ALPR, emptyList())
    }

    private fun publish() {
        val loc = location.value ?: return
        val now = System.currentTimeMillis()
        val list = cams.value.map { c ->
            val res = FloatArray(2)
            Location.distanceBetween(loc.latitude, loc.longitude, c.lat, c.lon, res)
            val bearing = ((res[1] % 360f) + 360f) % 360f
            val maker = c.tags["manufacturer"] ?: c.tags["brand"]
            val isFlock = maker?.contains("flock", ignoreCase = true) == true
            val details = ArrayList<Pair<String, String>>()
            details += "Location" to String.format("%.6f, %.6f", c.lat, c.lon)
            maker?.let { details += "Manufacturer" to it }
            c.tags["operator"]?.let { details += "Operator" to it }
            (c.tags["camera:direction"] ?: c.tags["direction"])?.let { details += "Camera faces" to "$it°" }
            c.tags["surveillance:zone"]?.let { details += "Watches" to it }
            c.tags["camera:mount"]?.let { details += "Mount" to it }
            details += "Source" to "OpenStreetMap / DeFlock (node ${c.id})"
            Device(
                key = "A:${c.id}", source = Source.ALPR, address = "osm:${c.id}",
                name = if (isFlock) "Flock ALPR camera" else (maker?.let { "$it ALPR camera" } ?: "ALPR camera"),
                vendor = maker, category = "License-plate camera (mapped)",
                rssi = null, smoothRssi = null, measuredPower = 0,
                firstSeen = now, lastSeen = now, seenCount = 1, randomizedAddress = false,
                details = details,
                flags = listOf(
                    Flag(
                        ThreatKind.FLOCK, if (isFlock) Confidence.HIGH else Confidence.MEDIUM,
                        if (isFlock) "Mapped Flock Safety license-plate camera" else "Mapped license-plate reader"
                    )
                ),
                geoLat = c.lat, geoLon = c.lon, geoDistanceM = res[0].toDouble(), geoBearingDeg = bearing.toDouble(),
            )
        }
        ScanEngine.replaceSource(Source.ALPR, list)
    }
}
