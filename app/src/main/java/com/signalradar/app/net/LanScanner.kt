package com.signalradar.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import com.signalradar.app.scan.Classifier
import com.signalradar.app.scan.Confidence
import com.signalradar.app.scan.Device
import com.signalradar.app.scan.Flag
import com.signalradar.app.scan.ScanEngine
import com.signalradar.app.scan.Source
import com.signalradar.app.scan.ThreatKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Looks for cameras on the Wi-Fi network you're connected to (hotel / rental check):
 *  - ONVIF WS-Discovery: IP cameras answer a multicast "who is a video transmitter?" probe
 *  - SSDP/UPnP discovery
 *  - TCP sweep of camera ports (RTSP 554/8554, Dahua 37777, XMeye 34567, ONVIF 8899, web UIs …)
 *  - banner grab of web / RTSP servers
 */
object LanScanner {
    val running = MutableStateFlow(false)
    val progress = MutableStateFlow(0f)
    val status = MutableStateFlow("")
    val results = MutableStateFlow<List<Device>>(emptyList())

    private val PORTS = listOf(554, 8554, 80, 81, 88, 443, 8000, 8080, 8081, 8899, 37777, 34567, 9000, 1935, 5000, 23)

    private class Host(val ip: String) {
        val ports: MutableSet<Int> = ConcurrentHashMap.newKeySet()
        val notes: MutableMap<String, String> = ConcurrentHashMap()
        val flags: MutableList<Flag> = java.util.Collections.synchronizedList(ArrayList())
        @Volatile var name: String? = null
        @Volatile var onvif = false
        @Volatile var rtsp = false
    }

    suspend fun scan(ctx: Context) = withContext(Dispatchers.IO) {
        if (running.value) return@withContext
        running.value = true
        progress.value = 0f
        val wifi = ctx.applicationContext.getSystemService(WifiManager::class.java)
        val mlock = wifi?.createMulticastLock("signalradar")?.apply { setReferenceCounted(false) }
        try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val lp = cm?.getLinkProperties(cm.activeNetwork)
            val la = lp?.linkAddresses?.firstOrNull { it.address is Inet4Address }
            if (la == null) {
                status.value = "Not connected to a Wi-Fi network. Join the network you want to check first."
                return@withContext
            }
            val me = la.address as Inet4Address
            val prefix = la.prefixLength.coerceIn(22, 30) // at most ~1000 hosts
            val myInt = toInt(me)
            val mask = -1 shl (32 - prefix)
            val base = myInt and mask
            val count = (1 shl (32 - prefix)) - 2
            val targets = (1..count).map { base + it }.filter { it != myInt }.map { fromInt(it) }
            status.value = "Scanning ${targets.size} addresses on ${fromInt(base)}/$prefix…"

            mlock?.acquire()
            val hosts = ConcurrentHashMap<String, Host>()
            fun host(ip: String) = hosts.getOrPut(ip) { Host(ip) }
            val done = AtomicInteger(0)

            coroutineScope {
                launch { wsDiscovery { ip, name, hw, types ->
                    val h = host(ip)
                    h.onvif = true
                    if (name != null) h.name = name
                    hw?.let { h.notes["ONVIF hardware"] = it }
                    h.notes["ONVIF"] = types
                } }
                launch { ssdp { ip, server, st ->
                    val h = host(ip)
                    server?.let { h.notes["UPnP server"] = it }
                    st?.let { if (h.notes["UPnP type"] == null) h.notes["UPnP type"] = it }
                } }
                val sem = Semaphore(96)
                targets.map { ip ->
                    launch {
                        sem.withPermit {
                            for (p in PORTS) {
                                if (open(ip, p, 300)) host(ip).ports += p
                            }
                        }
                        progress.value = done.incrementAndGet().toFloat() / targets.size * 0.85f
                    }
                }
            }

            status.value = "Checking ${hosts.size} devices…"
            coroutineScope {
                hosts.values.map { h ->
                    async {
                        for (p in h.ports) {
                            when (p) {
                                554, 8554 -> rtspBanner(h, p)
                                80, 81, 88, 8000, 8080, 8081 -> httpBanner(h, p)
                            }
                        }
                        if (h.name == null) {
                            val rdns = try { InetAddress.getByName(h.ip).canonicalHostName } catch (_: Exception) { null }
                            if (rdns != null && rdns != h.ip) h.name = rdns
                        }
                    }
                }.awaitAll()
            }
            progress.value = 1f

            val now = System.currentTimeMillis()
            val devices = hosts.values.map { h -> toDevice(h, now) }
                .sortedWith(compareByDescending<Device> { d -> d.topFlag?.confidence?.ordinal ?: -1 }.thenBy { it.address })
            results.value = devices
            ScanEngine.replaceSource(Source.LAN, devices)
            val cams = devices.count { d -> d.flags.any { it.kind == ThreatKind.CAMERA || it.kind == ThreatKind.HIDDEN_CAMERA } && d.hasThreat(Confidence.MEDIUM) }
            status.value = "Found ${devices.size} devices on this network — " +
                if (cams == 0) "no cameras detected." else "$cams look like cameras."
        } catch (e: Exception) {
            status.value = "Scan error: ${e.message}"
        } finally {
            try { mlock?.release() } catch (_: Exception) {}
            running.value = false
        }
    }

    private fun toDevice(h: Host, now: Long): Device {
        val flags = ArrayList<List<Flag>>()
        val details = ArrayList<Pair<String, String>>()
        details += "IP address" to h.ip
        if (h.ports.isNotEmpty()) details += "Open ports" to h.ports.sorted().joinToString(", ") { portName(it) }
        h.notes.forEach { (k, v) -> details += k to v }
        if (h.onvif) flags += listOf(Flag(ThreatKind.CAMERA, Confidence.HIGH, "Answered ONVIF discovery as a network video camera"))
        if (h.rtsp) flags += listOf(Flag(ThreatKind.CAMERA, Confidence.HIGH, "Runs an RTSP video-streaming server"))
        else if (554 in h.ports || 8554 in h.ports) flags += listOf(Flag(ThreatKind.CAMERA, Confidence.MEDIUM, "RTSP video port is open"))
        if (37777 in h.ports) flags += listOf(Flag(ThreatKind.CAMERA, Confidence.MEDIUM, "Port 37777 is open (Dahua / white-label camera & DVR protocol)"))
        if (34567 in h.ports) flags += listOf(Flag(ThreatKind.HIDDEN_CAMERA, Confidence.MEDIUM, "Port 34567 is open (XMeye — used by cheap Wi-Fi cameras and DVRs)"))
        if (8899 in h.ports) flags += listOf(Flag(ThreatKind.CAMERA, Confidence.LOW, "Port 8899 is open (common alternate ONVIF port)"))
        for (v in h.notes.values) {
            flags += Classifier.textFlags(v)
            flags += Classifier.vendorFlags(v)
            flags += bannerFlags(v)
        }
        flags += Classifier.textFlags(h.name)
        val category = when {
            h.onvif || h.rtsp -> "Network camera"
            h.notes["UPnP type"]?.contains("MediaRenderer", true) == true -> "TV / media player"
            h.notes["UPnP type"]?.contains("InternetGatewayDevice", true) == true -> "Router"
            80 in h.ports || 443 in h.ports -> "Device with a web interface"
            else -> "Network device"
        }
        return Device(
            key = "L:${h.ip}", source = Source.LAN, address = h.ip, name = h.name, vendor = h.notes["ONVIF hardware"],
            category = category, rssi = null, smoothRssi = null, measuredPower = 0,
            firstSeen = now, lastSeen = now, seenCount = 1, randomizedAddress = false,
            details = details, flags = Classifier.merge(*flags.toTypedArray()),
        )
    }

    private val BANNER_RULES = listOf(
        Regex("(?i)uc-httpd|xmeye|netsurveillance") to Flag(ThreatKind.HIDDEN_CAMERA, Confidence.MEDIUM, "Web server used by XMeye cheap cameras / DVRs"),
        Regex("(?i)hikvision|app-webs|dnvrs-webs|davinci") to Flag(ThreatKind.CAMERA, Confidence.HIGH, "Hikvision camera / NVR web server"),
        Regex("(?i)dahua|webs/|dh_") to Flag(ThreatKind.CAMERA, Confidence.MEDIUM, "Dahua-style camera web server"),
        Regex("(?i)ipcam|ip camera|netwave|hipcam|webcam|network camera|video server|dvr|nvr") to Flag(ThreatKind.CAMERA, Confidence.MEDIUM, "Web page identifies a camera / recorder"),
        Regex("(?i)goahead|boa/|thttpd|mini_httpd|lighttpd/1\\.4\\.(2|3)") to Flag(ThreatKind.CAMERA, Confidence.LOW, "Embedded web server common on cheap cameras (also routers)"),
        Regex("(?i)gstreamer|live555|rtsp") to Flag(ThreatKind.CAMERA, Confidence.MEDIUM, "Video streaming server"),
    )

    private fun bannerFlags(v: String): List<Flag> = BANNER_RULES.filter { it.first.containsMatchIn(v) }.map { it.second.copy(reason = it.second.reason + ": \"" + v.take(60) + "\"") }

    private fun portName(p: Int) = when (p) {
        554, 8554 -> "$p (RTSP video)"
        80, 81, 88, 8000, 8080, 8081 -> "$p (web)"
        443 -> "443 (https)"
        8899 -> "8899 (ONVIF)"
        37777 -> "37777 (Dahua)"
        34567 -> "34567 (XMeye)"
        9000 -> "9000"
        1935 -> "1935 (RTMP stream)"
        5000 -> "5000 (UPnP/NAS)"
        23 -> "23 (telnet)"
        else -> "$p"
    }

    private fun open(ip: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs); true }
    } catch (_: Exception) {
        false
    }

    private fun exchange(ip: String, port: Int, request: String, max: Int = 6000): String? = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, port), 1500)
            s.soTimeout = 2000
            s.getOutputStream().write(request.toByteArray())
            s.getOutputStream().flush()
            val buf = ByteArray(max)
            var n = 0
            val input = s.getInputStream()
            while (n < max) {
                val r = try { input.read(buf, n, max - n) } catch (_: Exception) { -1 }
                if (r <= 0) break
                n += r
            }
            String(buf, 0, n, Charsets.ISO_8859_1)
        }
    } catch (_: Exception) {
        null
    }

    private fun header(resp: String, name: String): String? =
        Regex("(?im)^$name:\\s*(.+)$").find(resp)?.groupValues?.get(1)?.trim()

    private fun rtspBanner(h: Host, port: Int) {
        val r = exchange(h.ip, port, "OPTIONS rtsp://${h.ip}:$port/ RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: SignalRadar\r\n\r\n", 2000) ?: return
        if (r.startsWith("RTSP/1.0")) {
            h.rtsp = true
            header(r, "Server")?.let { h.notes["RTSP server"] = it }
        }
    }

    private fun httpBanner(h: Host, port: Int) {
        val r = exchange(h.ip, port, "GET / HTTP/1.0\r\nHost: ${h.ip}\r\nUser-Agent: Mozilla/5.0 SignalRadar\r\nConnection: close\r\n\r\n") ?: return
        header(r, "Server")?.let { h.notes["Web server ($port)"] = it }
        Regex("(?is)<title>(.*?)</title>").find(r)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            h.notes["Web page title ($port)"] = it.take(80)
            if (h.name == null) h.name = it.take(40)
        }
        Regex("(?i)realm=\"([^\"]+)\"").find(r)?.groupValues?.get(1)?.let { h.notes["Login realm ($port)"] = it }
    }

    private fun wsDiscovery(onFound: (ip: String, name: String?, hw: String?, types: String) -> Unit) {
        val probe = """<?xml version="1.0" encoding="UTF-8"?><e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dn="http://www.onvif.org/ver10/network/wsdl"><e:Header><w:MessageID>uuid:${UUID.randomUUID()}</w:MessageID><w:To e:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action e:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header><e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>"""
        multicast("239.255.255.250", 3702, probe, 5000) { ip, text ->
            if (!text.contains("ProbeMatch")) return@multicast
            fun scope(k: String) = Regex("onvif://www\\.onvif\\.org/$k/([^\\s<]+)").find(text)?.groupValues?.get(1)
                ?.let { try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it } }
            val types = Regex("<[^>]*Types>([^<]+)<").find(text)?.groupValues?.get(1)?.trim() ?: "NetworkVideoTransmitter"
            onFound(ip, scope("name"), scope("hardware"), types)
        }
    }

    private fun ssdp(onFound: (ip: String, server: String?, st: String?) -> Unit) {
        val req = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n"
        multicast("239.255.255.250", 1900, req, 4000) { ip, text ->
            onFound(ip, header(text, "SERVER"), header(text, "ST"))
        }
    }

    private fun multicast(group: String, port: Int, payload: String, listenMs: Long, onPacket: (String, String) -> Unit) {
        try {
            DatagramSocket().use { s ->
                s.soTimeout = 400
                val data = payload.toByteArray()
                val addr = InetAddress.getByName(group)
                repeat(2) { s.send(DatagramPacket(data, data.size, addr, port)) }
                val end = System.currentTimeMillis() + listenMs
                val buf = ByteArray(16 * 1024)
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(p)
                        onPacket(p.address.hostAddress ?: continue, String(p.data, 0, p.length, Charsets.UTF_8))
                    } catch (_: java.net.SocketTimeoutException) {
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun toInt(a: Inet4Address): Int {
        val b = a.address
        return ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
    }

    private fun fromInt(i: Int) = "${(i ushr 24) and 0xFF}.${(i ushr 16) and 0xFF}.${(i ushr 8) and 0xFF}.${i and 0xFF}"
}
