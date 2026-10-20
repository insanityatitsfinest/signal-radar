package com.signalradar.app.net

import com.signalradar.app.scan.Classifier
import com.signalradar.app.scan.Device
import com.signalradar.app.scan.Flag
import com.signalradar.app.scan.ScanEngine
import com.signalradar.app.scan.Source
import com.signalradar.app.scan.Vendors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads a WiFi Pineapple Mark VII over USB-C and merges its monitor-mode Recon scan
 * (access points AND client/station radios, which the phone itself can't see) onto the radar.
 *
 * Current Mk7 REST API (firmware 2.x), over USB-C at http://172.16.42.1:1471:
 *   POST /api/login                     {username,password} -> {token}
 *   all other calls: header  Authorization: Bearer <token>
 *   GET  /api/recon/status              -> {scanRunning, scanID, scanPercent, ...}
 *   POST /api/recon/start               {live, scan_time, band} -> {scanRunning, scanID}
 *   POST /api/recon/stop
 *   POST /api/recon/scans/<id>/ap       {search,page,pageSize,sortCol,sortDir} -> {aps:[...]}
 * Auth is the Pineapple's own web-UI username/password — no API-token module needed.
 *
 * READ-ONLY: it logs in, runs a Recon scan, and reads the AP/client list. Nothing else.
 */
object Pineapple {
    const val HOST = "172.16.42.1"
    const val PORT = 1471
    private val BASE = "http://$HOST:$PORT"

    val running = MutableStateFlow(false)
    val status = MutableStateFlow("")
    val connected = MutableStateFlow(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    @Volatile private var token: String? = null

    /** band: 0 = 2.4 GHz, 1 = 5 GHz, 2 = both. */
    fun start(username: String, password: String, band: Int) {
        if (password.isBlank()) {
            status.value = "Enter the Pineapple's password (the one you set in its web UI) first."
            return
        }
        job?.cancel()
        running.value = true
        job = scope.launch {
            try {
                loop(username.ifBlank { "root" }.trim(), password, band)
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) status.value = "Stopped: ${e.message}"
            } finally {
                running.value = false
            }
        }
    }

    fun stop() {
        job?.cancel()
        running.value = false
        scope.launch(Dispatchers.IO) { runCatching { token?.let { post("/api/recon/stop", null) } } }
        status.value = "Stopped."
    }

    fun clear() {
        stop()
        connected.value = false
        token = null
        status.value = ""
        ScanEngine.replaceSource(Source.PINEAPPLE, emptyList())
    }

    private suspend fun loop(username: String, password: String, band: Int) = withContext(Dispatchers.IO) {
        status.value = "Logging in to the Pineapple…"
        try {
            login(username, password)
        } catch (e: IOException) {
            status.value = "Couldn't reach the Pineapple at $HOST ($BASE). Open $BASE:$PORT in a browser to confirm the USB link, then retry.\n${e.message}"
            return@withContext
        }
        if (token == null) {
            status.value = "Login failed — check the username (usually \"root\") and the password you set for the web UI."
            return@withContext
        }
        connected.value = true

        // Reuse a running scan if there is one, else start a continuous live scan.
        var scanId = currentScanId()
        if (scanId == null) {
            status.value = "Starting a monitor-mode scan…"
            scanId = startScan(band)
        }
        if (scanId == null) {
            status.value = "Logged in, but the Pineapple didn't start a scan. Is a radio interface free for Recon?"
            return@withContext
        }

        while (isActive) {
            try {
                val devices = readAps(scanId)
                publish(devices)
                val aps = devices.count { it.category.startsWith("Wi-Fi AP") }
                status.value = "Pineapple: $aps access points, ${devices.size - aps} client radios. Live."
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // token can expire; re-login once and keep going
                if ((e.message ?: "").contains("401")) {
                    runCatching { login(username, password) }
                } else {
                    status.value = "Scan read hiccup: ${e.message}. Retrying…"
                }
            }
            delay(5000)
        }
    }

    // ---------------------------------------------------------------- API calls

    private fun login(username: String, password: String) {
        val resp = postNoAuth("/api/login", JSONObject().put("username", username).put("password", password))
        token = resp?.optString("token")?.takeIf { it.isNotBlank() }
    }

    private fun currentScanId(): Int? {
        val s = runCatching { get("/api/recon/status") }.getOrNull() ?: return null
        return if (s.optBoolean("scanRunning", false)) s.optInt("scanID", -1).takeIf { it >= 0 } else null
    }

    private fun startScan(band: Int): Int? {
        val body = JSONObject()
            .put("live", true)
            .put("scan_time", 0) // 0 = continuous
            .put("band", band.coerceIn(0, 2).toString())
        val r = post("/api/recon/start", body) ?: return null
        return r.optInt("scanID", -1).takeIf { it >= 0 }
    }

    private fun readAps(scanId: Int): List<Device> {
        val body = JSONObject()
            .put("search", "").put("page", 0).put("pageSize", 99999999)
            .put("sortCol", "last_seen").put("sortDir", "asc")
        val r = post("/api/recon/scans/$scanId/ap", body) ?: return emptyList()
        val aps = r.optJSONArray("aps") ?: JSONArray()
        val now = System.currentTimeMillis()
        val out = ArrayList<Device>()
        val seen = HashSet<String>()
        for (i in 0 until aps.length()) {
            val ap = aps.optJSONObject(i) ?: continue
            val bssid = ap.optString("bssid").uppercase().takeIf { it.isNotBlank() } ?: continue
            val ssid = ap.optString("ssid").takeIf { it.isNotBlank() && it != "null" }
            if (seen.add("P:$bssid")) out += apDevice(bssid, ssid, ap, now)
            ap.optJSONArray("clients")?.let { cl ->
                for (j in 0 until cl.length()) {
                    val c = cl.optJSONObject(j) ?: continue
                    val mac = c.optString("client_mac").uppercase().takeIf { it.isNotBlank() } ?: continue
                    if (!seen.add("P:$mac")) continue
                    out += clientDevice(mac, ssid, bssid, c, now)
                }
            }
        }
        return out
    }

    private fun apDevice(bssid: String, ssid: String?, ap: JSONObject, now: Long): Device {
        val vendor = Vendors.ouiVendor(bssid)
        val rssi = ap.optInt("signal", 0).takeIf { it != 0 }
        val ch = ap.optInt("channel", 0).takeIf { it != 0 }
        val details = ArrayList<Pair<String, String>>()
        details += "BSSID" to bssid
        vendor?.let { details += "MAC vendor" to it }
        ch?.let { details += "Channel" to "$it (${if (it <= 14) "2.4" else "5"} GHz)" }
        if (ap.optInt("hidden", 0) == 1) details += "SSID" to "(hidden)"
        if (ap.optInt("wps", 0) == 1) details += "WPS" to "enabled"
        ap.optInt("num_clients", 0).takeIf { it > 0 }?.let { details += "Clients" to it.toString() }
        details += "Seen by" to "WiFi Pineapple (monitor mode)"
        val flags = Classifier.merge(
            Classifier.textFlags(ssid),
            Classifier.vendorFlags(vendor),
            Classifier.macFlags(bssid),
        )
        return Device(
            key = "P:$bssid", source = Source.PINEAPPLE, address = bssid, name = ssid,
            vendor = vendor, category = "Wi-Fi AP (Pineapple)",
            rssi = rssi, smoothRssi = rssi?.toFloat(), measuredPower = -45,
            firstSeen = now, lastSeen = now, seenCount = 1, randomizedAddress = Vendors.isLocallyAdministered(bssid),
            details = details, flags = flags,
        )
    }

    private fun clientDevice(mac: String, apSsid: String?, apBssid: String, c: JSONObject, now: Long): Device {
        val local = Vendors.isLocallyAdministered(mac)
        val vendor = if (!local) Vendors.ouiVendor(mac) else null
        val probed = c.optString("ssid").takeIf { it.isNotBlank() && it != "null" }
        // The Recon API gives client presence but usually no per-client RSSI, so these aren't range-able.
        val rssi = c.optInt("signal", 0).takeIf { it != 0 }
        val details = ArrayList<Pair<String, String>>()
        details += "MAC" to mac + if (local) "  (randomized)" else ""
        vendor?.let { details += "MAC vendor" to it }
        if (apSsid != null || apBssid.isNotBlank()) details += "Associated with" to (apSsid ?: apBssid)
        probed?.let { details += "Probing for" to it }
        details += "Seen by" to "WiFi Pineapple (a client device, not an access point)"
        val flagLists = ArrayList<List<Flag>>()
        flagLists += Classifier.vendorFlags(vendor)
        if (!local) flagLists += Classifier.macFlags(mac)
        probed?.let { flagLists += Classifier.textFlags(it, "Probes for") }
        return Device(
            key = "P:$mac", source = Source.PINEAPPLE, address = mac,
            name = probed?.let { "client (probes \"$it\")" },
            vendor = vendor, category = "Wi-Fi client radio",
            rssi = rssi, smoothRssi = rssi?.toFloat(), measuredPower = -45,
            firstSeen = now, lastSeen = now, seenCount = 1, randomizedAddress = local,
            details = details, flags = Classifier.merge(*flagLists.toTypedArray()),
        )
    }

    private fun publish(devices: List<Device>) = ScanEngine.replaceSource(Source.PINEAPPLE, devices)

    // ---------------------------------------------------------------- http

    private fun get(path: String): JSONObject? = request("GET", path, null, auth = true)
    private fun post(path: String, body: JSONObject?): JSONObject? = request("POST", path, body, auth = true)
    private fun postNoAuth(path: String, body: JSONObject): JSONObject? = request("POST", path, body, auth = false)

    private fun request(method: String, path: String, body: JSONObject?, auth: Boolean): JSONObject? {
        val c = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8000
            readTimeout = 30000
            setRequestProperty("Accept", "application/json")
            if (auth) token?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 401) throw IOException("HTTP 401 (auth)")
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() }
                throw IOException("HTTP $code" + (msg?.let { " – $it" } ?: ""))
            }
            val t = text.trim()
            return if (t.startsWith("{")) JSONObject(t)
            else if (t.startsWith("[")) JSONObject().put("aps", JSONArray(t)) // defensive: bare array
            else null
        } finally {
            c.disconnect()
        }
    }
}
