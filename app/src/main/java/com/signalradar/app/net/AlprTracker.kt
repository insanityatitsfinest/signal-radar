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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.resume

/**
 * License-plate (ALPR) cameras near you, from the crowdsourced map behind deflock.me
 * (OpenStreetMap nodes tagged surveillance:type=ALPR). This finds Flock cameras that stay radio-silent.
 *
 * Sources, tried in order:
 *  1. Overpass API servers, DeFlock's own first (the public ones are often overloaded or return HTTP 500)
 *  2. The copy saved on the phone from the last successful download
 * Everything runs in its own scope so switching tabs doesn't cancel a download.
 */
@SuppressLint("MissingPermission")
object AlprTracker {
    data class Cam(val id: Long, val lat: Double, val lon: Double, val tags: Map<String, String>)

    val cams = MutableStateFlow<List<Cam>>(emptyList())
    val status = MutableStateFlow("")
    val loading = MutableStateFlow(false)
    val location = MutableStateFlow<Location?>(null)
    val tracking = MutableStateFlow(false)
    val source = MutableStateFlow("")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var listener: LocationListener? = null

    private const val UA = "SignalRadar/1.0 (Android app; +https://github.com/insanityatitsfinest/signal-radar)"

    // Order matters: DeFlock's server answers in < 1 s; overpass-api.de is often "at capacity" (502/504);
    // maps.mail.ru is slow but works; kumi and private.coffee were returning HTTP 500 (Oct 2026).
    private val OVERPASS = listOf(
        "https://overpass.deflock.org/api/interpreter",
        "https://overpass-api.de/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )

    fun load(ctx: Context, radiusM: Int) {
        val app = ctx.applicationContext
        job?.cancel()
        job = scope.launch {
            loading.value = true
            try {
                doLoad(app, radiusM)
                if (cams.value.isNotEmpty()) startTracking(app)
            } finally {
                loading.value = false
            }
        }
    }

    private suspend fun doLoad(ctx: Context, radiusM: Int) {
        status.value = "Getting your location…"
        val loc = currentLocation(ctx)
        if (loc == null) {
            status.value = "Couldn't get a location fix. Turn on Location (and Google location accuracy) and try again."
            return
        }
        setLocation(loc)
        val km = String.format("%.0f km", radiusM / 1000.0)
        val errors = ArrayList<String>()

        // 1) Overpass servers. Busy servers ("at capacity" 502/504/429) get one more try after a short wait.
        for (round in 0..1) {
            for (ep in OVERPASS) {
                val host = URL(ep).host
                if (round == 1 && errors.none { it.startsWith(host) && busy(it) }) continue
                status.value = if (round == 0) "Asking $host…" else "Retrying $host…"
                try {
                    val list = withContext(Dispatchers.IO) { fromOverpass(ep, loc, radiusM) }
                    finish(ctx, list, host, km)
                    return
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    errors += "$host: ${e.message}"
                }
            }
            if (round == 0 && errors.any { busy(it) }) {
                status.value = "Map servers are busy — retrying in a few seconds…"
                delay(4000)
            }
        }

        // 2) Saved copy
        val saved = withContext(Dispatchers.IO) { loadSaved(ctx, loc, radiusM) }
        if (saved != null) {
            cams.value = saved.first
            source.value = "saved copy"
            status.value = "Map servers are busy — showing the copy saved ${saved.second}: ${saved.first.size} cameras within $km."
            publish()
            return
        }
        status.value = "Couldn't reach any map server. Check your internet and try again in a minute.\n" +
            errors.distinct().joinToString("\n")
    }

    private suspend fun finish(ctx: Context, list: List<Cam>, from: String, km: String) {
        cams.value = list.sortedBy { distanceTo(it) }
        source.value = from
        withContext(Dispatchers.IO) { save(ctx, list) }
        status.value = if (list.isEmpty()) "No mapped ALPR cameras within $km (source: $from)."
        else "${list.size} mapped ALPR cameras within $km (source: $from)."
        publish()
    }

    // ------------------------------------------------------------------ sources

    private fun fromOverpass(endpoint: String, loc: Location, radiusM: Int): List<Cam> {
        val q = "[out:json][timeout:25];nwr[\"surveillance:type\"=\"ALPR\"](around:$radiusM,${loc.latitude},${loc.longitude});out center;"
        val body = httpPost(endpoint, "data=" + URLEncoder.encode(q, "UTF-8"))
        val trimmed = body.trimStart()
        if (!trimmed.startsWith("{")) throw IOException("not a JSON reply")
        val json = JSONObject(trimmed)
        json.optString("remark").takeIf { it.contains("error", true) }?.let { throw IOException(it.take(80)) }
        val els = json.optJSONArray("elements") ?: JSONArray()
        val out = ArrayList<Cam>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val c = e.optJSONObject("center")
            val lat = if (e.has("lat")) e.getDouble("lat") else c?.optDouble("lat") ?: continue
            val lon = if (e.has("lon")) e.getDouble("lon") else c?.optDouble("lon") ?: continue
            val tags = HashMap<String, String>()
            e.optJSONObject("tags")?.let { t -> t.keys().forEach { k -> tags[k] = t.optString(k) } }
            out += Cam(e.getLong("id"), lat, lon, tags)
        }
        return out
    }

    // ------------------------------------------------------------------ saved copy

    private fun save(ctx: Context, list: List<Cam>) {
        try {
            val arr = JSONArray()
            list.forEach { c ->
                arr.put(JSONObject().put("id", c.id).put("lat", c.lat).put("lon", c.lon).put("tags", JSONObject(c.tags)))
            }
            // merge with what was saved before so trips to other areas keep working offline
            val f = File(ctx.filesDir, "alpr_saved.json")
            val old = try { JSONObject(f.readText()).optJSONArray("cams") } catch (_: Exception) { null }
            val seen = list.map { it.id }.toHashSet()
            if (old != null) for (i in 0 until old.length()) {
                val o = old.getJSONObject(i)
                if (o.optLong("id") !in seen && arr.length() < 20_000) arr.put(o)
            }
            f.writeText(JSONObject().put("time", System.currentTimeMillis()).put("cams", arr).toString())
        } catch (_: Exception) {
        }
    }

    private fun loadSaved(ctx: Context, loc: Location, radiusM: Int): Pair<List<Cam>, String>? {
        return try {
            val j = JSONObject(File(ctx.filesDir, "alpr_saved.json").readText())
            val arr = j.getJSONArray("cams")
            val res = FloatArray(1)
            val out = ArrayList<Cam>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val lat = o.getDouble("lat")
                val lon = o.getDouble("lon")
                Location.distanceBetween(loc.latitude, loc.longitude, lat, lon, res)
                if (res[0] > radiusM) continue
                val tags = HashMap<String, String>()
                o.optJSONObject("tags")?.let { t -> t.keys().forEach { k -> tags[k] = t.optString(k) } }
                out += Cam(o.getLong("id"), lat, lon, tags)
            }
            if (out.isEmpty()) null
            else out to DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(j.getLong("time")))
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ http

    private fun open(url: String, timeoutMs: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json")
        }

    private fun check(c: HttpURLConnection) {
        val code = c.responseCode
        if (code !in 200..299) {
            val msg = try {
                c.errorStream?.bufferedReader()?.use { it.readText() }
                    ?.replace(Regex("<[^>]+>"), " ")?.replace(Regex("\\s+"), " ")?.trim()?.take(90)
            } catch (_: Exception) { null }
            throw IOException("HTTP $code" + (msg?.takeIf { it.isNotEmpty() }?.let { " – $it" } ?: ""))
        }
    }

    private fun httpPost(url: String, form: String): String {
        val c = open(url, 45_000)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            c.outputStream.use { it.write(form.toByteArray()) }
            check(c)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    // ------------------------------------------------------------------ location

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
        val lm = ctx.applicationContext.getSystemService(LocationManager::class.java) ?: return
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
        try { ctx.applicationContext.getSystemService(LocationManager::class.java)?.removeUpdates(l) } catch (_: Exception) {}
        listener = null
        tracking.value = false
    }

    fun clear(ctx: Context) {
        job?.cancel()
        stopTracking(ctx)
        cams.value = emptyList()
        status.value = ""
        source.value = ""
        ScanEngine.replaceSource(Source.ALPR, emptyList())
    }

    private fun busy(err: String) = Regex("HTTP (429|502|503|504)|timeout|timed out", RegexOption.IGNORE_CASE).containsMatchIn(err)

    private fun distanceTo(c: Cam): Float {
        val loc = location.value ?: return 0f
        val res = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, c.lat, c.lon, res)
        return res[0]
    }

    private fun publish() {
        val loc = location.value ?: return
        val now = System.currentTimeMillis()
        val list = cams.value.map { c ->
            val res = FloatArray(2)
            Location.distanceBetween(loc.latitude, loc.longitude, c.lat, c.lon, res)
            val bearing = ((res[1] % 360f) + 360f) % 360f
            val maker = c.tags["manufacturer"] ?: c.tags["brand"] ?: c.tags["surveillance:brand"] ?: c.tags["surveillance:manufacturer"]
            val isFlock = maker?.contains("flock", ignoreCase = true) == true
            val details = ArrayList<Pair<String, String>>()
            details += "Location" to String.format("%.6f, %.6f", c.lat, c.lon)
            maker?.let { details += "Manufacturer" to it }
            (c.tags["operator"] ?: c.tags["surveillance:operator"])?.let { details += "Operator" to it }
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
