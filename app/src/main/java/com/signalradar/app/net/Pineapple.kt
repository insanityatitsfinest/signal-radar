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
    const val DEFAULT_HOST = "172.16.42.1"
    
    var hostIp = DEFAULT_HOST
    const val PORT = 1471
    private val API_URL: String get() = "http://$hostIp:$PORT/api/"
    val running = MutableStateFlow(false)
    val status = MutableStateFlow("")
    val connected = MutableStateFlow(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    @Volatile private var token: String? = null
    @Volatile private var host: String = DEFAULT_HOST
    private val base: String get() = "http://$host:$PORT"

    /**
     * host: 172.16.42.1 for both USB-C and the Pineapple's management Wi-Fi; a LAN IP if it's on your network.
     * band: 0 = 2.4 GHz, 1 = 5 GHz, 2 = both.
     */
    fun start(hostIn: String, username: String, password: String, band: Int) {
        if (password.isBlank()) {
            status.value = "Enter the Pineapple's password (the one you set in its web UI) first."
            return
        }
        host = hostIn.ifBlank { DEFAULT_HOST }.trim().removePrefix("http://").removePrefix("https://").substringBefore(':').substringBefore('/')
        job?.cancel()
        running.value = true
        job = scope.launch {
            try {
                loop(username.ifBlank { "root" }.trim(), password, band)
            } catch (e: Exception) {
