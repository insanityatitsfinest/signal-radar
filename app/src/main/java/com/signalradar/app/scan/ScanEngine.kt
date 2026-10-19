package com.signalradar.app.scan

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.net.wifi.ScanResult as WifiScanResult

@SuppressLint("MissingPermission")
object ScanEngine {
    data class Status(
        val running: Boolean = false,
        val bluetoothOn: Boolean = true,
        val bleActive: Boolean = false,
        val classicActive: Boolean = false,
        val wifiScans: Int = 0,
        val wifiThrottled: Int = 0,
        val lastWifiResults: Long = 0,
        val message: String? = null,
    )

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val devices = HashMap<String, Device>()
    private val history = HashMap<String, ArrayDeque<Int>>()
    private val wifiResults = HashMap<String, WifiScanResult>()
    private val alerted = HashSet<String>()

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devicesFlow: StateFlow<List<Device>> = _devices
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status
    private val _newThreat = MutableSharedFlow<Device>(extraBufferCapacity = 16)
    val newThreat: SharedFlow<Device> = _newThreat

    val classicEnabled = MutableStateFlow(true)

    @Volatile private var running = false
    private var jobs = ArrayList<Job>()
    private var receiversRegistered = false

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        scope.launch(Dispatchers.IO) { Vendors.load(app) }
    }

    private val adapter: BluetoothAdapter?
        get() = app.getSystemService(BluetoothManager::class.java)?.adapter

    private val wifi: WifiManager?
        get() = app.getSystemService(WifiManager::class.java)

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        if (running) return
        running = true
        _status.value = _status.value.copy(running = true, message = null)
        registerReceivers()
        startBle()
        if (classicEnabled.value) startDiscovery()

        jobs += scope.launch { // publish snapshots to the UI twice a second
            while (isActive) {
                publish()
                delay(500)
            }
        }
        jobs += scope.launch { // Android allows ~4 Wi-Fi scans / 2 min unless throttling is disabled in Developer options
            readWifi()
            while (isActive) {
                requestWifiScan()
                delay(30_000)
            }
        }
        jobs += scope.launch { // restart BLE now and then; Android quietly degrades scans that run for 30+ min
            while (isActive) {
                delay(10 * 60_000L)
                stopBle(); delay(500); startBle()
            }
        }
        jobs += scope.launch {
            var last = classicEnabled.value
            classicEnabled.collect { on ->
                if (on != last) {
                    last = on
                    if (on) startDiscovery() else stopDiscovery()
                }
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        jobs.forEach { it.cancel() }
        jobs.clear()
        stopBle()
        stopDiscovery()
        if (receiversRegistered) {
            try { app.unregisterReceiver(btReceiver) } catch (_: Exception) {}
            try { app.unregisterReceiver(wifiReceiver) } catch (_: Exception) {}
            receiversRegistered = false
        }
        _status.value = _status.value.copy(running = false, bleActive = false, classicActive = false)
    }

    fun clear() {
        synchronized(lock) {
            devices.entries.removeAll { it.value.source != Source.ALPR }
            history.clear()
            wifiResults.clear()
            alerted.clear()
        }
        publish()
    }

    private fun registerReceivers() {
        if (receiversRegistered) return
        val bt = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(app, btReceiver, bt, ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(
            app, wifiReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_EXPORTED
        )
        receiversRegistered = true
    }

    // ---------------------------------------------------------------- shared state

    fun history(key: String): List<Int> = synchronized(lock) { history[key]?.toList() ?: emptyList() }

    fun wifiScanResult(key: String): WifiScanResult? = synchronized(lock) { wifiResults[key] }

    fun setEstBearing(key: String, deg: Float?) = synchronized(lock) {
        devices[key]?.let { devices[key] = it.copy(estBearingDeg = deg) }
    }

    fun setRtt(key: String, meters: Double) = synchronized(lock) {
        devices[key]?.let { devices[key] = it.copy(rttMeters = meters, rttAt = System.currentTimeMillis()) }
    }

    fun replaceSource(source: Source, list: List<Device>) {
        synchronized(lock) {
            devices.entries.removeAll { it.value.source == source }
            // No pop-up alerts here: these lists come from tools the user just ran and shows directly.
            list.forEach { d -> devices[d.key] = d }
        }
        publish()
    }

    private fun publish() {
        val now = System.currentTimeMillis()
        val snap = synchronized(lock) {
            devices.entries.removeAll { it.value.source != Source.ALPR && it.value.source != Source.LAN && now - it.value.lastSeen > 30 * 60_000L }
            devices.values.toList()
        }
        _devices.value = snap.sortedWith(compareByDescending<Device> { it.smoothRssi ?: -999f }.thenBy { it.geoDistanceM ?: 1e9 })
    }

    private fun checkAlert(d: Device) {
        if (d.hasThreat(Confidence.MEDIUM) && alerted.add(d.key)) _newThreat.tryEmit(d)
    }

    /** Must be called with [lock] held. */
    private fun store(old: Device?, d0: Device, rssi: Int?) {
        var d = d0
        if (rssi != null) {
            val prev = old?.smoothRssi
            val s = if (prev == null) rssi.toFloat() else prev + 0.35f * (rssi - prev)
            d = d.copy(rssi = rssi, smoothRssi = s)
            val h = history.getOrPut(d.key) { ArrayDeque() }
            h.addLast(rssi)
            while (h.size > 300) h.removeFirst()
        } else if (old != null) {
            d = d.copy(rssi = old.rssi, smoothRssi = old.smoothRssi)
        }
        if (old != null) {
            d = d.copy(
                firstSeen = old.firstSeen, seenCount = old.seenCount + 1,
                rttMeters = old.rttMeters, rttAt = old.rttAt, estBearingDeg = old.estBearingDeg,
            )
        }
        devices[d.key] = d
        checkAlert(d)
    }

    // ---------------------------------------------------------------- BLE

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handleBle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { handleBle(it) }
        override fun onScanFailed(errorCode: Int) {
            _status.value = _status.value.copy(bleActive = false, message = "BLE scan failed (code $errorCode). Toggle Bluetooth and reopen the app.")
        }
    }

    private fun startBle() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            _status.value = _status.value.copy(bluetoothOn = false, bleActive = false)
            return
        }
        val scanner = a.bluetoothLeScanner ?: return
        val b = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
        if (a.isLeExtendedAdvertisingSupported) {
            b.setLegacy(false).setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
        }
        try {
            scanner.startScan(null, b.build(), bleCallback)
            _status.value = _status.value.copy(bluetoothOn = true, bleActive = true)
        } catch (e: Exception) {
            _status.value = _status.value.copy(bleActive = false, message = "BLE scan error: ${e.message}")
        }
    }

    private fun stopBle() {
        try { adapter?.bluetoothLeScanner?.stopScan(bleCallback) } catch (_: Exception) {}
    }

    private fun safeName(d: BluetoothDevice): String? = try { d.name } catch (_: SecurityException) { null }

    private fun handleBle(result: ScanResult) {
        val dev = result.device ?: return
        val addr = dev.address ?: return
        val rec = result.scanRecord
        val now = System.currentTimeMillis()
        val name = rec?.deviceName?.takeIf { it.isNotBlank() } ?: safeName(dev)

        val randomized = if (Build.VERSION.SDK_INT >= 35) {
            dev.addressType == BluetoothDevice.ADDRESS_TYPE_RANDOM
        } else {
            Vendors.ouiVendor(addr) == null
        }
        val details = ArrayList<Pair<String, String>>()
        details += "Address" to addr + if (randomized) "  (random / rotating)" else "  (public)"

        val flagLists = ArrayList<List<Flag>>()
        var vendor: String? = null
        var category: String? = null

        // Manufacturer-specific data: company ID + payload
        val mfr = rec?.manufacturerSpecificData
        if (mfr != null) {
            for (i in 0 until mfr.size()) {
                val id = mfr.keyAt(i)
                val data = mfr.valueAt(i) ?: ByteArray(0)
                val company = Vendors.company(id)
                if (vendor == null && company != null) vendor = company
                details += "Manufacturer data" to String.format("0x%04X %s  %s", id, company ?: "", hex(data))
                flagLists += Classifier.companyFlags(id)
                when (id) {
                    0x004C -> appleType(data)?.let { (label, flags) ->
                        category = label; details += "Apple type" to label; flagLists += flags
                    }
                    0x0006 -> if (category == null) category = "Windows PC / Microsoft device"
                }
            }
        }

        // Service UUIDs + service data
        val uuids16 = LinkedHashSet<Int>()
        val serviceNames = ArrayList<String>()
        rec?.serviceUuids?.forEach { pu ->
            val s = short16(pu.uuid)
            if (s != null) uuids16 += s else serviceNames += pu.uuid.toString()
        }
        rec?.serviceData?.forEach { (pu, data) ->
            val s = short16(pu.uuid)
            if (s != null) {
                uuids16 += s
                if (s == 0xFEAA && data != null && data.isNotEmpty() && (data[0].toInt() == 0x40 || data[0].toInt() == 0x41)) {
                    category = "Google Find Hub tracker"
                    flagLists += listOf(Flag(ThreatKind.TRACKER, Confidence.MEDIUM, "Google Find Hub (FMDN) tracker beacon"))
                }
                details += String.format("Service data 0x%04X", s) to hex(data ?: ByteArray(0))
            }
        }
        for (u in uuids16) {
            serviceNames += String.format("0x%04X %s", u, Vendors.uuid16(u) ?: "")
            flagLists += Classifier.serviceFlags(u)
        }
        if (serviceNames.isNotEmpty()) details += "Services" to serviceNames.joinToString("\n")

        // Appearance (what the device says it is)
        rec?.bytes?.let { appearance(it) }?.let { (raw, label) ->
            details += "Appearance" to String.format("%s (0x%04X)", label, raw)
            if (category == null) category = label
            if ((raw shr 6) == 7) flagLists += listOf(Flag(ThreatKind.SMART_GLASSES, Confidence.LOW, "Advertises itself as eyeglasses"))
        }
        if (category == null) category = categoryFromServices(uuids16, name)

        // Vendor from the MAC when it's a public address
        val ouiVendor = if (!randomized) Vendors.ouiVendor(addr) else null
        if (ouiVendor != null) {
            details += "MAC vendor" to ouiVendor
            if (vendor == null) vendor = ouiVendor
        }

        val tx = if (result.txPower != ScanResult.TX_POWER_NOT_PRESENT) result.txPower
        else rec?.txPowerLevel?.takeIf { it != Int.MIN_VALUE }
        tx?.let { details += "TX power" to "$it dBm" }
        details += "Connectable" to if (result.isConnectable) "yes" else "no"
        if (result.primaryPhy == BluetoothDevice.PHY_LE_CODED) details += "PHY" to "LE Coded (long range)"

        flagLists += Classifier.textFlags(name)
        flagLists += Classifier.vendorFlags(vendor)
        if (ouiVendor != null && ouiVendor != vendor) flagLists += Classifier.vendorFlags(ouiVendor)
        if (!randomized) flagLists += Classifier.macFlags(addr)

        val key = "B:$addr"
        synchronized(lock) {
            val old = devices[key]
            val d = Device(
                key = key, source = Source.BLE, address = addr, name = name ?: old?.name, vendor = vendor ?: old?.vendor,
                category = category ?: old?.category ?: "BLE device",
                rssi = null, smoothRssi = null,
                measuredPower = tx?.let { (it - 41).coerceIn(-90, -35) } ?: -59,
                firstSeen = now, lastSeen = now, seenCount = 1, randomizedAddress = randomized,
                details = details, flags = Classifier.carryOver(Classifier.merge(*flagLists.toTypedArray()), old?.flags),
            )
            store(old, d, result.rssi)
        }
    }

    private val BASE_LSB = java.lang.Long.parseUnsignedLong("800000805F9B34FB", 16)

    private fun short16(u: java.util.UUID): Int? {
        if (u.leastSignificantBits != BASE_LSB) return null
        val msb = u.mostSignificantBits
        if ((msb and 0xFFFFFFFFL) != 0x1000L) return null
        val v = (msb ushr 32)
        return if (v <= 0xFFFF) v.toInt() else null
    }

    private fun appleType(d: ByteArray): Pair<String, List<Flag>>? {
        if (d.isEmpty()) return null
        return when (d[0].toInt() and 0xFF) {
            0x02 -> "iBeacon" to emptyList()
            0x05 -> "Apple AirDrop" to emptyList()
            0x07 -> "AirPods / Beats" to emptyList()
            0x09 -> "AirPlay target" to emptyList()
            0x0C -> "Apple Handoff" to emptyList()
            0x0F -> "Apple device (Nearby Action)" to emptyList()
            0x10 -> "iPhone / iPad / Mac" to emptyList()
            0x12 -> if (d.size >= 20) "AirTag / Find My item (away from owner)" to listOf(
                Flag(ThreatKind.TRACKER, Confidence.MEDIUM, "Apple Find My beacon separated from its owner — AirTag or lost Apple accessory")
            ) else "Find My item (near owner)" to listOf(
                Flag(ThreatKind.TRACKER, Confidence.LOW, "Apple Find My beacon (owner nearby)")
            )
            else -> "Apple device" to emptyList()
        }
    }

    private fun appearance(raw: ByteArray): Pair<Int, String>? {
        var i = 0
        while (i < raw.size) {
            val len = raw[i].toInt() and 0xFF
            if (len == 0 || i + len >= raw.size) break
            val type = raw[i + 1].toInt() and 0xFF
