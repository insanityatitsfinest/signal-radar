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
import java.util.Locale
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

    fun start() {
        if (running) return
        running = true
        _status.value = _status.value.copy(running = true, message = null)
        registerReceivers()
        startBle()
        if (classicEnabled.value) startDiscovery()

        jobs += scope.launch {
            while (isActive) {
                publish()
                delay(500)
            }
        }
        jobs += scope.launch {
            readWifi()
            while (isActive) {
                requestWifiScan()
                delay(30_000)
            }
        }
        jobs += scope.launch {
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
            list.forEach { d -> devices[d.key] = d }
        }
        publish()
    }

    private fun publish() {
        val now = System.currentTimeMillis()
        val snap = synchronized(lock) {
            devices.entries.removeAll { 
                it.value.source != Source.ALPR && 
                it.value.source != Source.LAN && 
                it.value.source != Source.PINEAPPLE && 
                now - it.value.lastSeen > 30 * 60_000L 
            }
            devices.values.toList()
        }
        _devices.value = snap.sortedWith(compareByDescending<Device> { it.smoothRssi ?: -999f }.thenBy { it.geoDistanceM ?: 1e9 })
    }

    private fun checkAlert(d: Device) {
        if (d.hasThreat(Confidence.MEDIUM) && alerted.add(d.key)) _newThreat.tryEmit(d)
    }

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

        rec?.bytes?.let { appearance(it) }?.let { (raw, label) ->
            details += "Appearance" to String.format("%s (0x%04X)", label, raw)
            if (category == null) category = label
            if ((raw shr 6) == 7) flagLists += listOf(Flag(ThreatKind.SMART_GLASSES, Confidence.LOW, "Advertises itself as eyeglasses"))
        }
        if (category == null) category = categoryFromServices(uuids16, name)

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
            if (type == 0x19 && len >= 3) {
                val v = (raw[i + 2].toInt() and 0xFF) or ((raw[i + 3].toInt() and 0xFF) shl 8)
                return v to appearanceLabel(v)
            }
            i += len + 1
        }
        return null
    }

    private fun appearanceLabel(v: Int): String = when (v shr 6) {
        0 -> "Unknown"
        1 -> "Phone"
        2 -> "Computer"
        3 -> "Watch"
        4 -> "Clock"
        5 -> "Display / TV"
        6 -> "Remote control"
        7 -> "Eyeglasses"
        8 -> "Tag"
        9 -> "Keyring"
        10 -> "Media player"
        11 -> "Barcode scanner"
        12 -> "Thermometer"
        13 -> "Heart rate sensor"
        14 -> "Blood pressure monitor"
        15 -> "Keyboard / mouse / controller"
        16 -> "Glucose meter"
        17 -> "Running sensor"
        18 -> "Cycling sensor"
        19 -> "Control device"
        20 -> "Network device"
        21 -> "Sensor"
        22, 31 -> "Light"
        23 -> "Fan"
        24, 25, 27 -> "HVAC"
        26 -> "Humidifier"
        28 -> "Access control / lock"
        29 -> "Motorized device"
        30 -> "Power device"
        32 -> "Window covering"
        33 -> "Audio sink / speaker"
        34 -> "Audio source"
        35 -> "Vehicle"
        36 -> "Appliance"
        37 -> "Earbuds / headphones"
        38 -> "Aircraft / drone"
        39 -> "AV equipment"
        40 -> "Display equipment"
        41 -> "Hearing aid"
        42 -> "Gaming"
        43 -> "Signage"
        49 -> "Pulse oximeter"
        else -> "Category ${v shr 6}"
    }

    private fun categoryFromServices(u: Set<Int>, name: String?): String? {
        val n = name?.lowercase() ?: ""
        return when {
            0xFE2C in u -> "Fast Pair device (earbuds / speaker)"
            0xFD5A in u -> "Samsung Find device / SmartTag"
            0xFEED in u || 0xFEEC in u -> "Tile tracker"
            0x180D in u -> "Heart rate sensor"
            0x1812 in u -> "Keyboard / mouse / controller"
            0x184E in u || 0x1850 in u || 0x1853 in u -> "LE Audio device"
            0xFD6F in u -> "Phone (exposure notification)"
            0xFE9F in u -> "Google device"
            0xFE95 in u -> "Xiaomi device"
            0xFEAA in u -> "Eddystone beacon"
            "buds" in n || "airpods" in n || "headphone" in n || "earbud" in n -> "Earbuds / headphones"
            "watch" in n -> "Watch"
            n.startsWith("[tv]") || " tv" in n || n.startsWith("tv") -> "TV"
            "speaker" in n || "jbl" in n || "bose" in n || "sonos" in n -> "Speaker"
            else -> null
        }
    }

    // ---------------------------------------------------------------- Classic Bluetooth

    private fun startDiscovery() {
        val a = adapter ?: return
        if (!a.isEnabled) return
        try {
            if (!a.isDiscovering) a.startDiscovery()
            _status.value = _status.value.copy(classicActive = true)
        } catch (_: SecurityException) {
        }
    }

    private fun stopDiscovery() {
        try { adapter?.cancelDiscovery() } catch (_: SecurityException) {}
        _status.value = _status.value.copy(classicActive = false)
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val dev = IntentCompat.getParcelableExtra(i, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                    val rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    val cls = IntentCompat.getParcelableExtra(i, BluetoothDevice.EXTRA_CLASS, BluetoothClass::class.java)
                    val name = i.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: safeName(dev)
                    handleClassic(dev, if (rssi == Short.MIN_VALUE.toInt()) null else rssi, cls, name)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> if (running && classicEnabled.value) {
                    scope.launch { delay(3000); if (running && classicEnabled.value) startDiscovery() }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val st = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                    if (st == BluetoothAdapter.STATE_ON && running) {
                        stopBle(); startBle()
                        if (classicEnabled.value) startDiscovery()
                    } else if (st == BluetoothAdapter.STATE_OFF) {
                        _status.value = _status.value.copy(bluetoothOn = false, bleActive = false, classicActive = false)
                    }
                }
            }
        }
    }

    private fun handleClassic(dev: BluetoothDevice, rssi: Int?, cls: BluetoothClass?, name: String?) {
        val addr = dev.address ?: return
        val now = System.currentTimeMillis()
        val vendor = Vendors.ouiVendor(addr)
        val flagLists = ArrayList<List<Flag>>()
        val details = ArrayList<Pair<String, String>>()
        details += "Address" to addr
        vendor?.let { details += "MAC vendor" to it }
        var category = "Bluetooth device"
        if (cls != null) {
            val dc = cls.deviceClass
            category = classLabel(cls)
            details += "Device class" to String.format("%s (0x%04X)", category, dc)
            val major = cls.majorDeviceClass
            if (major == BluetoothClass.Device.Major.IMAGING && (dc and 0x20) != 0) {
                flagLists += listOf(Flag(ThreatKind.CAMERA, Confidence.MEDIUM, "Bluetooth class says it's a camera"))
            }
            if (dc == BluetoothClass.Device.AUDIO_VIDEO_VIDEO_CAMERA || dc == BluetoothClass.Device.AUDIO_VIDEO_CAMCORDER) {
                flagLists += listOf(Flag(ThreatKind.CAMERA, Confidence.MEDIUM, "Bluetooth class says it's a video camera"))
            }
        }
        flagLists += Classifier.textFlags(name)
        flagLists += Classifier.vendorFlags(vendor)
        flagLists += Classifier.macFlags(addr)

        val key = "C:$addr"
        synchronized(lock) {
            val old = devices[key]
            val d = Device(
                key = key, source = Source.CLASSIC, address = addr, name = name ?: old?.name, vendor = vendor,
                category = category, rssi = null, smoothRssi = null, measuredPower = -55,
                firstSeen = now, lastSeen = now, seenCount = 1, randomizedAddress = false,
                details = details, flags = Classifier.merge(*flagLists.toTypedArray()),
            )
            store(old, d, rssi)
        }
    }

    private fun classLabel(c: BluetoothClass): String {
        val dc = c.deviceClass
        return when (c.majorDeviceClass) {
            BluetoothClass.Device.Major.PHONE -> "Phone"
            BluetoothClass.Device.Major.COMPUTER -> if (dc == BluetoothClass.Device.COMPUTER_LAPTOP) "Laptop" else "Computer"
            BluetoothClass.Device.Major.AUDIO_VIDEO -> when (dc) {
                BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET, BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES -> "Headphones"
                BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> "Hands-free / car kit"
                BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> "Car audio"
                BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER, BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO -> "Speaker"
                BluetoothClass.Device.AUDIO_VIDEO_VIDEO_CAMERA -> "Video camera"
                BluetoothClass.Device.AUDIO_VIDEO_CAMCORDER -> "Camcorder"
                BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER, BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR -> "TV / display"
                BluetoothClass.Device.AUDIO_VIDEO_SET_TOP_BOX -> "Set-top box"
                else -> "Audio / video device"
            }
            BluetoothClass.Device.Major.IMAGING -> when {
                dc and 0x20 != 0 -> "Camera"
                dc and 0x80 != 0 -> "Printer"
                dc and 0x40 != 0 -> "Scanner"
                else -> "Imaging device"
            }
            BluetoothClass.Device.Major.PERIPHERAL -> "Keyboard / mouse / controller"
            BluetoothClass.Device.Major.WEARABLE -> "Wearable"
            BluetoothClass.Device.Major.TOY -> "Toy"
            BluetoothClass.Device.Major.HEALTH -> "Health device"
            BluetoothClass.Device.Major.NETWORKING -> "Network access point"
            else -> "Bluetooth device"
        }
    }

    // ---------------------------------------------------------------- Wi-Fi

    @Suppress("DEPRECATION")
    fun requestWifiScan() {
        val w = wifi ?: return
        val ok = try { w.startScan() } catch (_: Exception) { false }
        val s = _status.value
        _status.value = if (ok) s.copy(wifiScans = s.wifiScans + 1) else s.copy(wifiThrottled = s.wifiThrottled + 1)
    }

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            scope.launch { readWifi() }
        }
    }

    private fun readWifi() {
        val list = try { wifi?.scanResults ?: emptyList() } catch (_: SecurityException) { emptyList() }
        val now = System.currentTimeMillis()
        val bootUs = SystemClock.elapsedRealtime() * 1000
        for (r in list) {
            val ageMs = ((bootUs - r.timestamp) / 1000).coerceAtLeast(0)
            if (ageMs > 3 * 60_000L) continue
            handleWifi(r, now - ageMs)
        }
        _status.value = _status.value.copy(lastWifiResults = now)
    }

    @Suppress("DEPRECATION")
    private fun handleWifi(r: WifiScanResult, seenAt: Long) {
        val bssid = r.BSSID ?: return
        val ssid = r.SSID?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        val local = Vendors.isLocallyAdministered(bssid)
        val vendor = if (!local) Vendors.ouiVendor(bssid) else null
        
        val cruiserMatch = CruiserDetector.analyze(r)
        val pineappleMatch = PineappleDetector.analyze(r)
        
        val details = ArrayList<Pair<String, String>>()
        val flagLists = ArrayList<List<Flag>>()
        details += "BSSID" to bssid + if (local) "  (locally administered / virtual)" else ""
        vendor?.let { details += "MAC vendor" to it }
        details += "SSID" to (ssid ?: "(hidden)")
        
        if (cruiserMatch != null) {
            details += "Public Safety / Fleet" to "${cruiserMatch.hardwareVendor} (${cruiserMatch.confidence})"
        }
        if (pineappleMatch != null) {
            details += "Threat Detected" to "${pineappleMatch.hardwareVendor} (${pineappleMatch.confidence})"
        }
        
        val f = r.frequency
        val band = when {
            f < 2500 -> "2.4 GHz"
            f < 5925 -> "5 GHz"
            else -> "6 GHz"
        }
        val channel = when {
            f == 2484 -> 14
            f < 2500 -> (f - 2407) / 5
            f < 5925 -> (f - 5000) / 5
            else -> (f - 5950) / 5
        }
        details += "Channel" to "$channel ($band, $f MHz)"
        details += "Security" to security(r.capabilities ?: "")
        if (Build.VERSION.SDK_INT >= 30) {
            details += "Standard" to when (r.wifiStandard) {
                4 -> "Wi-Fi 4 (n)"
                5 -> "Wi-Fi 5 (ac)"
                6 -> "Wi-Fi 6/6E (ax)"
                8 -> "Wi-Fi 7 (be)"
                1 -> "Legacy (a/b/g)"
                else -> "Unknown"
            }
        }
        if (r.is80211mcResponder) details += "Wi-Fi RTT" to "Supported — precise distance available in Find"

        var wpsVendor: String? = null
        if (Build.VERSION.SDK_INT >= 30) {
            val ieVendors = LinkedHashSet<String>()
            for (ie in r.informationElements) {
                if (ie.id != 221) continue
                val bb = ie.bytes.duplicate()
                val b = ByteArray(bb.remaining())
                bb.get(b)
                if (b.size < 4) continue
                val oui = String.format("%02X:%02X:%02X", b[0], b[1], b[2])
                if (oui == "00:50:F2" && b[3].toInt() == 4) {
                    val wps = parseWps(b)
                    for ((k, v) in wps) details += "WPS $k" to v
                    wpsVendor = wps["Manufacturer"]
                    val model = listOfNotNull(wps["Manufacturer"], wps["Model"], wps["Model number"], wps["Device name"]).joinToString(" ")
                    flagLists += Classifier.textFlags(model, "WPS info:")
                    flagLists += Classifier.vendorFlags(wps["Manufacturer"])
                } else {
                    Vendors.ouiVendor("$oui:00:00:00")?.let { ieVendors += shortVendor(it) }
                }
            }
            if (ieVendors.isNotEmpty()) details += "Vendor tags" to ieVendors.joinToString(", ")
        }

        val category = when {
            cruiserMatch != null -> "Law Enforcement Cruiser"
            pineappleMatch != null -> "Rogue AP / Wi-Fi Pineapple"
            ssid == null -> "Hidden Wi-Fi network"
            ssid.startsWith("DIRECT-") -> "Wi-Fi Direct (printer / TV / camera)"
            Regex("(?i)iphone|galaxy|android|pixel|hotspot|moto|oneplus").containsMatchIn(ssid) -> "Phone hotspot"
            Regex("(?i)tesla|vw|toyota|ford|chevy|gmc|honda|hyundai|kia|subaru|bmw|audi|nissan|mazda|jeep|ram|dodge|lexus|uconnect|onstar|carplay|android ?auto").containsMatchIn(ssid) -> "Vehicle Wi-Fi"
            else -> "Wi-Fi access point"
        }

        flagLists += Classifier.textFlags(ssid)
        flagLists += Classifier.vendorFlags(vendor)
        if (!local) flagLists += Classifier.macFlags(bssid)
        
        if (cruiserMatch != null) {
            flagLists += listOf(Flag(ThreatKind.TRACKER, Confidence.HIGH, "CRUISER: ${cruiserMatch.hardwareVendor}"))
        }
        if (pineappleMatch != null) {
            flagLists += listOf(Flag(ThreatKind.ROGUE_AP, Confidence.HIGH, "PINEAPPLE: ${pineappleMatch.hardwareVendor}"))
        }

        val key = "W:$bssid"
        synchronized(lock) {
            val old = devices[key]
            if (old != null && old.lastSeen >= seenAt) return 
            wifiResults[key] = r
            val d = Device(
                key = key, source = Source.WIFI, address = bssid, name = ssid,
                vendor = cruiserMatch?.hardwareVendor ?: pineappleMatch?.hardwareVendor ?: vendor ?: wpsVendor, category = category,
                rssi = null, smoothRssi = null,
                measuredPower = if (f < 2500) -38 else -45,
                firstSeen = seenAt, lastSeen = seenAt, seenCount = 1, randomizedAddress = local,
                details = details, flags = Classifier.merge(*flagLists.toTypedArray()),
                rttCapable = r.is80211mcResponder,
            )
            store(old, d, r.level)
        }
    }

    private fun security(c: String): String = when {
        "SAE" in c -> "WPA3"
        "OWE" in c -> "Enhanced Open (OWE)"
        "EAP" in c -> "Enterprise (802.1X)"
        "WPA2" in c || "RSN" in c -> "WPA2"
        "WPA" in c -> "WPA"
        "WEP" in c -> "WEP (insecure)"
        else -> "Open"
    }

    private fun parseWps(b: ByteArray): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        var i = 4
        while (i + 4 <= b.size) {
            val t = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            val l = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            i += 4
            if (i + l > b.size) break
            val label = when (t) {
                0x1021 -> "Manufacturer"
                0x1023 -> "Model"
                0x1024 -> "Model number"
                0x1011 -> "Device name"
                else -> null
            }
            if (label != null && l > 0) {
                val v = String(b, i, l, Charsets.UTF_8).filter { it >= ' ' }.trim()
                if (v.isNotEmpty() && v != "0") m[label] = v
            }
            i += l
        }
        return m
    }
}

data class CruiserHotspot(
    val hardwareVendor: String,
    val confidence: String
)

object CruiserDetector {
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
        pattern = "(?i)^(axon|fleet[-_]|ibr(900|1700)|r1900|r2400|cradlepoint|airlink|mp70|mg90|xr[89]0|pepwave|peplink|firstnet[-_]|.*[-_](mdt|patrol|cruiser|cad|alpr|lpr|pd|sheriff|trooper)\\b)"
    )

    fun analyze(scanResult: WifiScanResult): CruiserHotspot? {
        val rawSsid = scanResult.SSID?.removeSurrounding("\"")?.trim().orEmpty()
        val bssid = scanResult.BSSID?.uppercase(Locale.US) ?: return null

        val oui = bssid.take(8)
        val vendor = CRUISER_OUIS[oui]
        val matchesSsidPattern = rawSsid.isNotEmpty() && CRUISER_SSID_REGEX.containsMatchIn(rawSsid)

        return when {
            oui == "00:25:DF" || (vendor != null && matchesSsidPattern) -> CruiserHotspot(vendor ?: "Public Safety VAN", "HIGH")
            vendor != null -> CruiserHotspot(vendor, "MEDIUM")
            matchesSsidPattern -> CruiserHotspot("Matched Cruiser/MDT SSID", "MEDIUM")
            else -> null
        }
    }
}

data class PineappleHotspot(
    val hardwareVendor: String,
    val confidence: String
)

object PineappleDetector {
    private val HAK5_OUIS = mapOf(
        "00:13:37" to "Hak5 Wi-Fi Pineapple"
    )

    private val PINEAPPLE_SSID_REGEX = Regex("(?i)^(Pineapple.*)")

    fun analyze(scanResult: WifiScanResult): PineappleHotspot? {
        val rawSsid = scanResult.SSID?.removeSurrounding("\"")?.trim().orEmpty()
        val bssid = scanResult.BSSID?.uppercase(Locale.US) ?: return null

        val oui = bssid.take(8)
        val vendor = HAK5_OUIS[oui]
        val matchesSsidPattern = rawSsid.isNotEmpty() && PINEAPPLE_SSID_REGEX.containsMatchIn(rawSsid)

        return when {
            oui == "00:13:37" -> PineappleHotspot("Hak5 Wi-Fi Pineapple", "HIGH")
            matchesSsidPattern -> PineappleHotspot("Potential Wi-Fi Pineapple", "MEDIUM")
            else -> null
        }
    }
}
