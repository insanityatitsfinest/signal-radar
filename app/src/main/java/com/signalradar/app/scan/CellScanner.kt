package com.signalradar.app.scan

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.CellIdentityNr
import android.telephony.TelephonyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * Nearby cell towers (serving + neighbours) with signal strength, from TelephonyManager.
 * Needs ACCESS_FINE_LOCATION; tower identifiers also need READ_PHONE_STATE (without it we still
 * get the radio type and signal). Towers are environmental and far, so their radar distance is
 * only a rough "stronger = nearer" placement — flagged as such in the detail view, and the radar
 * layer is off by default.
 */
@SuppressLint("MissingPermission")
object CellScanner {
    val count = MutableStateFlow(0)
    val serving = MutableStateFlow<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private lateinit var app: Context

    fun start(ctx: Context) {
        app = ctx.applicationContext
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                poll()
                delay(8000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun poll() = withContext(Dispatchers.IO) {
        val tm = app.getSystemService(TelephonyManager::class.java) ?: return@withContext
        val infos = try { tm.allCellInfo ?: emptyList() } catch (_: Exception) { return@withContext }
        val now = System.currentTimeMillis()
        val out = ArrayList<Device>()
        var servingText: String? = null
        for (info in infos) {
            if (!info.isRegistered && out.size > 24) continue
            val d = toDevice(info, now) ?: continue
            out += d
            if (info.isRegistered && servingText == null) servingText = "${d.category.removePrefix("Cell tower · ")} · ${d.rssi} dBm"
        }
        serving.value = servingText
        count.value = out.size
        ScanEngine.replaceSource(Source.CELL, out)
    }

    private fun toDevice(info: CellInfo, now: Long): Device? {
        val reg = info.isRegistered
        val details = ArrayList<Pair<String, String>>()
        var tech: String
        var dbm: Int
        var key: String
        var name: String?

        when (info) {
            is CellInfoLte -> {
                tech = "LTE"
                dbm = info.cellSignalStrength.dbm
                val id = info.cellIdentity
                val ci = safe(id.ci); val pci = safe(id.pci); val tac = safe(id.tac); val earfcn = safe(id.earfcn)
                key = "N:LTE:${ci ?: pci ?: earfcn ?: id.hashCode()}"
                name = "LTE" + (pci?.let { " PCI $it" } ?: "")
                ci?.let { details += "Cell ID" to it.toString() }
                pci?.let { details += "PCI" to it.toString() }
                tac?.let { details += "TAC" to it.toString() }
                earfcn?.let { details += "EARFCN" to it.toString() }
                if (Build.VERSION.SDK_INT >= 30) safe(id.bandwidth)?.let { details += "Bandwidth" to "${it / 1000} MHz" }
                operatorName(id.operatorAlphaLong, id.operatorAlphaShort)?.let { details += "Carrier" to it; name = "$it LTE" }
                details += "RSRP" to "$dbm dBm"
                details += "Level" to "${info.cellSignalStrength.level}/4"
            }
            is CellInfoNr -> {
                tech = "5G NR"
                val ss = info.cellSignalStrength as? CellSignalStrengthNr
                dbm = ss?.ssRsrp ?: info.cellSignalStrength.dbm
                val id = info.cellIdentity as? CellIdentityNr
                val pci = id?.let { safe(it.pci) }; val tac = id?.let { safe(it.tac) }; val nrarfcn = id?.let { safe(it.nrarfcn) }
                key = "N:NR:${id?.nci?.takeIf { it != Long.MAX_VALUE } ?: pci ?: nrarfcn ?: info.hashCode()}"
                name = "5G" + (pci?.let { " PCI $it" } ?: "")
                id?.nci?.takeIf { it != Long.MAX_VALUE }?.let { details += "NCI" to it.toString() }
                pci?.let { details += "PCI" to it.toString() }
                tac?.let { details += "TAC" to it.toString() }
                nrarfcn?.let { details += "NR-ARFCN" to it.toString() }
                operatorName(id?.operatorAlphaLong, id?.operatorAlphaShort)?.let { details += "Carrier" to it; name = "$it 5G" }
                details += "SS-RSRP" to "$dbm dBm"
            }
            is CellInfoWcdma -> {
                tech = "3G WCDMA"
                dbm = info.cellSignalStrength.dbm
                val id = info.cellIdentity
                key = "N:WCDMA:${safe(id.cid) ?: safe(id.psc) ?: id.hashCode()}"
                name = "3G"
                safe(id.cid)?.let { details += "CID" to it.toString() }
                safe(id.lac)?.let { details += "LAC" to it.toString() }
                operatorName(id.operatorAlphaLong, id.operatorAlphaShort)?.let { details += "Carrier" to it; name = "$it 3G" }
                details += "RSCP" to "$dbm dBm"
            }
            is CellInfoGsm -> {
                tech = "2G GSM"
                dbm = info.cellSignalStrength.dbm
                val id = info.cellIdentity
                key = "N:GSM:${safe(id.cid) ?: id.hashCode()}"
                name = "2G"
                safe(id.cid)?.let { details += "CID" to it.toString() }
                safe(id.lac)?.let { details += "LAC" to it.toString() }
                operatorName(id.operatorAlphaLong, id.operatorAlphaShort)?.let { details += "Carrier" to it; name = "$it 2G" }
                details += "RSSI" to "$dbm dBm"
            }
            is CellInfoCdma -> {
                tech = "CDMA"
                dbm = info.cellSignalStrength.dbm
                key = "N:CDMA:${info.cellIdentity.basestationId}"
                name = "CDMA"
                details += "RSSI" to "$dbm dBm"
            }
            else -> return null
        }
        if (dbm == Int.MAX_VALUE || dbm == 0) return null
        details.add(0, "Type" to (tech + if (reg) " · serving (your tower)" else " · neighbour"))
        details += "Note" to "Cell distance on the radar is approximate — it comes from signal strength, and towers are far away."

        return Device(
            key = key, source = Source.CELL, address = key.substringAfter("N:"),
            name = (name ?: tech) + if (reg) " (serving)" else "",
            vendor = null, category = "Cell tower · $tech",
            rssi = dbm, smoothRssi = dbm.toFloat(),
            // towers are environmental; use a far measured-power so they sit toward the outer rings
            measuredPower = -40,
            firstSeen = now, lastSeen = now, seenCount = 1, randomizedAddress = false,
            details = details, flags = emptyList(),
        )
    }

    private fun safe(v: Int): Int? = if (v == Int.MAX_VALUE || v == Int.MIN_VALUE) null else v

    private fun operatorName(long: CharSequence?, short: CharSequence?): String? =
        (long?.toString()?.takeIf { it.isNotBlank() } ?: short?.toString()?.takeIf { it.isNotBlank() })
}
