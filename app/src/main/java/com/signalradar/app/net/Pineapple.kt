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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Reads a WiFi Pineapple Mark VII over USB-C and merges its monitor-mode Recon scan
 * (access points AND client/station radios, which the phone itself can't see) onto the radar.
 *
 * Over USB-C the Mark VII is always at http://172.16.42.1:1471/api/. Every call is a POST of
 * {"apiToken": <token>, "module"/"system": <name>, "action": <action>, ...args}; the newest
 * firmware prefixes the JSON reply with 6 junk chars which we strip. Files (recon scans) come
 * back via a download token: downloadResults -> {download: <tok>} -> GET ?download=<tok>.
 *
 * This is READ-ONLY: it lists scans, optionally starts a Recon scan, and reads results. It does
 * not touch PineAP, associations, or any attack feature.
 */
object Pineapple {
    const val HOST = "172.16.42.1"
    const val PORT = 1471
    private val API_URL = "http://$HOST:$PORT/api/"

    val running = MutableStateFlow(false)
    val status = MutableStateFlow("")
    val connected = MutableStateFlow(false)
    val apCount = MutableStateFlow(0)
    val clientCount = MutableStateFlow(0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    /** scanType for startNormalScan: 0 = 2.4 GHz, 1 = 5 GHz, 2 = both. */
    fun start(token: String, scanType: Int) {
        if (token.isBlank()) {
            status.value = "Enter the API token from the Pineapple's Settings → API Tokens first."
            return
        }
        job?.cancel()
        running.value = true
        job = scope.launch {
            try {
                loop(token.trim(), scanType)
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
        status.value = "Stopped."
    }

    fun clear() {
        stop()
        connected.value = false
        apCount.value = 0
        clientCount.value = 0
        status.value = ""
        ScanEngine.replaceSource(Source.PINEAPPLE, emptyList())
    }

    private suspend fun loop(token: String, scanType: Int) = withContext(Dispatchers.IO) {
        status.value = "Connecting to Pineapple over USB-C…"
        // Verify the token / reachability by listing scans.
        val probe = try {
            post(token, "module", "Recon", "getScans")
        } catch (e: IOException) {
            status.value = "Can't reach the Pineapple at $HOST. Check the USB-C cable and that the interface is up.\n${e.message}"
            return@withContext
        }
        if (probe == null || probe.optBoolean("error", false) || probe.has("apiToken")) {
            status.value = "The Pineapple rejected the token. Generate one under Settings → API Tokens and paste it in."
            return@withContext
        }
        connected.value = true

        val durationSec = 30
        while (isActive) {
            try {
                status.value = "Scanning (monitor mode)…"
                post(token, "module", "Recon", "startNormalScan", JSONObject().put("scanType", scanType).put("scanDuration", durationSec))
                // Wait for the scan, polling the current scan id so we read the right one.
                val scanId = waitForScan(token, durationSec)
                val devices = readResults(token, scanId)
                if (devices != null) {
                    publish(devices)
                    val aps = devices.count { it.category.startsWith("Wi-Fi AP") }
                    apCount.value = aps
                    clientCount.value = devices.size - aps
                    status.value = "Pineapple: $aps access points, ${devices.size - aps} client radios. Rescanning…"
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                status.value = "Scan hiccup: ${e.message}. Retrying…"
            }
            delay(2500)
        }
    }

    private suspend fun waitForScan(token: String, durationSec: Int): Int? {
        var current: Int? = null
        val deadline = System.currentTimeMillis() + (durationSec + 20) * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(3000)
            val r = runCatching { post(token, "module", "Recon", "getCurrentScanID") }.getOrNull()
            val id = r?.optInt("scanID", r.optInt("scanId", -1)) ?: -1
            if (id >= 0) current = id
            // 0 / negative current id means no scan is running any more → it finished
            val runningId = r?.opt("scanID") ?: r?.opt("scanId")
            if (runningId == null && current != null) break
            if (r != null && r.optBoolean("finished", false)) break
        }
        if (current != null && current >= 0) return current
        // Fall back to the newest scan in the list.
        val scans = runCatching { post(token, "module", "Recon", "getScans") }.getOrNull()
        return newestScanId(scans)
    }

    private fun newestScanId(resp: JSONObject?): Int? {
        val arr = resp?.let { firstArray(it) } ?: return null
        var best = -1
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optInt("scanID", o.optInt("scanId", o.optInt("id", -1)))
            if (id > best) best = id
        }
        return if (best >= 0) best else null
    }

    private suspend fun readResults(token: String, scanId: Int?): List<Device>? {
        if (scanId == null) return null
        // Preferred: loadResults returns the parsed scan directly.
        val loaded = runCatching { post(token, "module", "Recon", "loadResults", JSONObject().put("scanId", scanId)) }.getOrNull()
        parseScan(loaded)?.takeIf { it.isNotEmpty() }?.let { return it }
        // Fallback: downloadResults -> download token -> GET file.
        val dl = runCatching { post(token, "module", "Recon", "downloadResults", JSONObject().put("scanId", scanId)) }.getOrNull()
        val dTok = dl?.optString("download", "")?.takeIf { it.isNotBlank() } ?: dl?.optJSONObject("download")?.optString("token")
        if (!dTok.isNullOrBlank()) {
            val text = runCatching { download(token, dTok) }.getOrNull()
            if (text != null) parseScan(parseLoose(text))?.let { return it }
        }
        return emptyList()
    }

    // ---------------------------------------------------------------- parsing (tolerant)

    private val SSID_KEYS = listOf("ssid", "SSID", "essid")
    private val MAC_KEYS = listOf("bssid", "BSSID", "mac", "MAC", "macAddress", "address")
    private val RSSI_KEYS = listOf("rssi", "RSSI", "signal", "signalStrength", "power", "dbm")
    private val CH_KEYS = listOf("channel", "ch", "chan")
    private val CLIENT_KEYS = listOf("clients", "Clients", "stations", "devices")

    private fun JSONObject.firstString(keys: List<String>): String? {
        for (k in keys) if (has(k) && !isNull(k)) optString(k).takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    private fun JSONObject.firstInt(keys: List<String>): Int? {
        for (k in keys) if (has(k) && !isNull(k)) {
            val v = opt(k)
            when (v) {
                is Int -> return v
                is Number -> return v.toInt()
                is String -> v.trim().toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun JSONObject.clientsArray(): JSONArray? {
        for (k in CLIENT_KEYS) optJSONArray(k)?.let { return it }
        return null
    }

    /** Accepts either a parsed JSON object or array; returns the devices it can recognise. */
    private fun parseScan(root: Any?): List<Device>? {
        root ?: return null
        val aps = ArrayList<JSONObject>()
        collectApObjects(root, aps)
        if (aps.isEmpty()) return null
        val now = System.currentTimeMillis()
        val out = ArrayList<Device>()
        val seen = HashSet<String>()
        for (ap in aps) {
            val bssid = ap.firstString(MAC_KEYS)?.uppercase() ?: continue
            val ssid = ap.firstString(SSID_KEYS)
            val rssi = ap.firstInt(RSSI_KEYS)
            val ch = ap.firstInt(CH_KEYS)
            if (seen.add("P:$bssid")) out += apDevice(bssid, ssid, rssi, ch, now)
            ap.clientsArray()?.let { cl ->
                for (i in 0 until cl.length()) {
                    val c = cl.optJSONObject(i) ?: continue
                    val cmac = c.firstString(MAC_KEYS)?.uppercase() ?: continue
                    if (!seen.add("P:$cmac")) continue
                    out += clientDevice(cmac, c.firstInt(RSSI_KEYS), ssid, bssid, c, now)
                }
            }
        }
        return out
    }

    /** Recursively find objects that look like an AP (have an SSID or a clients array or a MAC). */
    private fun collectApObjects(node: Any?, out: MutableList<JSONObject>) {
        when (node) {
            is JSONArray -> for (i in 0 until node.length()) collectApObjects(node.opt(i), out)
            is JSONObject -> {
                val looksAp = node.firstString(SSID_KEYS) != null || node.clientsArray() != null
                val hasMac = node.firstString(MAC_KEYS) != null
                if (looksAp && hasMac) {
                    out += node
                } else {
                    // Descend into container objects (e.g. {"aps":[...]}, {"results":{...}}).
                    val keys = node.keys()
                    while (keys.hasNext()) collectApObjects(node.opt(keys.next()), out)
                }
            }
        }
    }

    private fun apDevice(bssid: String, ssid: String?, rssi: Int?, ch: Int?, now: Long): Device {
        val vendor = Vendors.ouiVendor(bssid)
        val details = ArrayList<Pair<String, String>>()
        details += "BSSID" to bssid
        vendor?.let { details += "MAC vendor" to it }
        ch?.let { details += "Channel" to it.toString() }
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

    private fun clientDevice(mac: String, rssi: Int?, apSsid: String?, apBssid: String, raw: JSONObject, now: Long): Device {
        val local = Vendors.isLocallyAdministered(mac)
        val vendor = if (!local) Vendors.ouiVendor(mac) else null
        val probes = raw.optJSONArray("probes") ?: raw.optJSONArray("ssids")
        val probeList = probes?.let { (0 until it.length()).mapNotNull { i -> it.optString(i).takeIf { s -> s.isNotBlank() } } } ?: emptyList()
        val details = ArrayList<Pair<String, String>>()
        details += "MAC" to mac + if (local) "  (randomized)" else ""
        vendor?.let { details += "MAC vendor" to it }
        if (apSsid != null || apBssid.isNotBlank()) details += "Associated with" to (apSsid ?: apBssid)
        if (probeList.isNotEmpty()) details += "Probing for" to probeList.joinToString(", ")
        details += "Seen by" to "WiFi Pineapple (monitor mode — a device, not an AP)"
        val flagLists = ArrayList<List<Flag>>()
        flagLists += Classifier.vendorFlags(vendor)
        if (!local) flagLists += Classifier.macFlags(mac)
        probeList.forEach { flagLists += Classifier.textFlags(it, "Probes for") }
        return Device(
            key = "P:$mac", source = Source.PINEAPPLE, address = mac,
            name = probeList.firstOrNull()?.let { "client (probes \"$it\")" },
            vendor = vendor, category = "Wi-Fi client radio",
            rssi = rssi, smoothRssi = rssi?.toFloat(), measuredPower = -45,
            firstSeen = now, lastSeen = now, seenCount = 1, randomizedAddress = local,
            details = details,
            flags = Classifier.merge(*flagLists.toTypedArray()),
        )
    }

    private fun publish(devices: List<Device>) {
        ScanEngine.replaceSource(Source.PINEAPPLE, devices)
    }

    // ---------------------------------------------------------------- http

    private fun post(token: String, type: String, module: String, action: String, args: JSONObject? = null): JSONObject? {
        val body = JSONObject().put("apiToken", token).put(type, module).put("action", action)
        args?.keys()?.forEach { body.put(it, args.get(it)) }
        val c = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 60000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code")
            return parseLoose(text) as? JSONObject
        } finally {
            c.disconnect()
        }
    }

    private fun download(token: String, dToken: String): String {
        val url = API_URL + "?apiToken=" + URLEncoder.encode(token, "UTF-8") + "&download=" + URLEncoder.encode(dToken, "UTF-8")
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 60000
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** Strips the firmware's 6-char ")]}'," prefix and parses to a JSONObject or JSONArray. */
    private fun parseLoose(text: String): Any? {
        var t = text.trim()
        if (t.startsWith(")]}'")) t = t.substringAfter('\n', t.drop(6))
        t = t.trim()
        return try {
            when {
                t.startsWith("{") -> JSONObject(t)
                t.startsWith("[") -> JSONArray(t)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun firstArray(o: JSONObject): JSONArray? {
        val keys = o.keys()
        while (keys.hasNext()) {
            val v = o.opt(keys.next())
            if (v is JSONArray) return v
            if (v is JSONObject) firstArray(v)?.let { return it }
        }
        return null
    }
}
