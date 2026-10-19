package com.signalradar.app.scan

import com.signalradar.app.scan.Confidence.HIGH
import com.signalradar.app.scan.Confidence.LOW
import com.signalradar.app.scan.Confidence.MEDIUM
import com.signalradar.app.scan.ThreatKind.BODY_CAM
import com.signalradar.app.scan.ThreatKind.CAMERA
import com.signalradar.app.scan.ThreatKind.FLOCK
import com.signalradar.app.scan.ThreatKind.HIDDEN_CAMERA
import com.signalradar.app.scan.ThreatKind.SMART_GLASSES
import com.signalradar.app.scan.ThreatKind.TRACKER

/**
 * Rules that turn names, manufacturers and radio fingerprints into flags.
 *
 * Flock indicators come from the community research behind flock-you / DeFlock:
 *  - IEEE OUI B4:1E:52 is registered to Flock Safety (strong)
 *  - BLE manufacturer ID 0x09C8 (XUNTONG) battery module seen on Flock / Raven units (strong)
 *  - Raven gunshot-detector BLE services 0x3100–0x3500 (strong)
 *  - SSIDs "Flock-XXXX", "Flock Camera net.", names "FS Ext Battery", "Penguin", "Pigvision"
 *  - a list of component-vendor OUIs also found in Flock units (weak: shared with lots of other gear)
 * Note: newer Flock firmware mostly stays quiet; the most reliable modern method (sniffing Wi-Fi probe
 * requests in monitor mode) is not possible on an unrooted phone. The ALPR map tool fills that gap.
 */
object Classifier {

    private val FLOCK_WEAK_OUIS = setOf(
        "70:C9:4E", "3C:91:80", "D8:F3:BC", "80:30:49", "B8:35:32", "14:5A:FC", "74:4C:A1", "08:3A:88",
        "9C:2F:9D", "C0:35:32", "94:08:53", "E4:AA:EA", "F4:6A:DD", "F8:A2:D6", "24:B2:B9", "00:F4:8D",
        "D0:39:57", "E8:D0:FC", "E0:4F:43", "B8:1E:A4", "70:08:94", "58:8E:81", "EC:1B:BD", "3C:71:BF",
        "58:00:E3", "90:35:EA", "5C:93:A2", "64:6E:69", "48:27:EA", "A4:CF:12", "82:6B:F2",
    )

    private data class Rule(val re: Regex, val kind: ThreatKind, val conf: Confidence, val why: String)

    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    /** Applied to device names, SSIDs, WPS model strings, ONVIF names and web banners. */
    private val TEXT_RULES = listOf(
        Rule(r("^flock[-_ ]?[a-z0-9]"), FLOCK, HIGH, "Name matches the Flock camera hotspot pattern"),
        Rule(r("flock ?camera"), FLOCK, HIGH, "Name matches a Flock camera network"),
        Rule(r("flock ?safety"), FLOCK, HIGH, "Names Flock Safety"),
        Rule(r("^fs ext battery"), FLOCK, MEDIUM, "\"FS Ext Battery\" is the Flock solar battery pack"),
        Rule(r("^pigvision"), FLOCK, MEDIUM, "Name seen in Flock firmware (Pigvision)"),
        Rule(r("^penguin"), FLOCK, LOW, "Name seen in Flock firmware (Penguin)"),
        Rule(r("^fs_"), FLOCK, LOW, "\"FS_\" prefix used by some Flock units"),
        Rule(r("raven"), FLOCK, LOW, "Name mentions Raven (Flock gunshot detector)"),

        // Cheap Wi-Fi spy / mini cameras usually create their own hotspot named after the app they use.
        Rule(r("hdwificam|hd ?wifi ?cam|ipcam|ip-cam|ip_cam|^ipc[-_ ]|v380|lookcam|icsee|yoosee|xmeye|camhi|minicam|mini[-_ ]?cam|spy ?cam|hidden ?cam|nanny ?cam|wifi[-_ ]?cam|p2p[-_ ]?cam|hdcam|hd[-_]cam|ubox|littlelf|smartcam|cloudcam|aishine|vr ?cam|ourcam|jxlcam|dvr[-_ ]?cam|pinhole|bulb ?cam|clock ?cam|charger ?cam"),
            HIDDEN_CAMERA, MEDIUM, "Name matches a cheap Wi-Fi spy-camera app or hotspot"),
        Rule(r("^(mv|hap|jx|ap|cam)[-_]?[0-9a-f]{6,}$"), HIDDEN_CAMERA, LOW, "Hotspot name looks like a mini-camera default"),

        Rule(r("ray-?ban|rb ?meta|meta ?glasses|oakley ?meta|spectacles"), SMART_GLASSES, HIGH, "Name matches camera glasses"),
        Rule(r("\\baxon\\b|body ?cam|bodycam|bodyworn|body-worn|watchguard|wolfcom"), BODY_CAM, MEDIUM, "Name matches body-camera equipment"),

        Rule(r("wyze|blink|arlo|eufy ?cam|eufycam|reolink|hikvision|dahua|ezviz|imou|tapo[-_ ]?c[0-9]|nest ?cam|^yi[-_ ]|kasa ?cam|amcrest|foscam|lorex|swann|annke|wansview|vstarcam|sricam|zmodo|blurams|tp-?link ?cam"),
            CAMERA, MEDIUM, "Name is a security-camera brand"),
        Rule(r("ring[-_ ]?(cam|doorbell|stick|spotlight|setup)|doorbell"), CAMERA, MEDIUM, "Name looks like a video doorbell / camera"),
        Rule(r("gopro|insta360|70mai|dash ?cam|dashcam|viofo|nextbase|garmin ?dash|osmo ?action|akaso"), CAMERA, MEDIUM, "Name looks like an action / dash camera"),
        Rule(r("\\bcam\\b|camera|webcam|\\bnvr\\b|\\bdvr\\b|cctv|surveil"), CAMERA, LOW, "Name mentions a camera"),
        Rule(r("airtag"), TRACKER, MEDIUM, "Name is an AirTag"),
        Rule(r("smarttag|smart tag|\\btile\\b|chipolo|pebblebee"), TRACKER, MEDIUM, "Name is a Bluetooth tracker"),
    )

    /** Applied to manufacturer names (from the MAC OUI or Bluetooth company ID). */
    private val VENDOR_RULES = listOf(
        Rule(r("flock safety"), FLOCK, HIGH, "MAC address registered to Flock Safety"),
        Rule(r("hikvision|dahua|ezviz|reolink|amcrest|foscam|wyze|arlo|blink|ring llc|eufy|imou|uniview|vivotek|axis communications|hanwha|lorex|swann|annke|xiongmai|vstarcam|wansview|zmodo|sricam|verkada|rhombus|avigilon|mobotix|pelco|milestone|tiandy|kedacom|uniarch|jovision|hipcam|shenzhen gwell|gwelltimes|tenvis|escam|ctronics|jooan|yi technology|shanghai xiaoyi|ubiquiti.*camera|meari|xiaovv|vimtag|cloudedge"),
            CAMERA, MEDIUM, "Manufacturer makes security / IP cameras"),
        Rule(r("gopro|insta360|arashi vision|70mai|viofo|nextbase|dji"), CAMERA, LOW, "Manufacturer makes action / dash / drone cameras"),
        Rule(r("axon enterprise|taser international|digital ally|watchguard|getac|wolfcom|reveal media|utility,? inc|coban"), BODY_CAM, MEDIUM, "Manufacturer makes police body cameras"),
        Rule(r("motorola solutions|vigilant solutions|genetec|neology|leonardo|elsag|kapsch"), FLOCK, LOW, "Manufacturer makes police / ALPR equipment"),
        Rule(r("luxottica"), SMART_GLASSES, HIGH, "Luxottica (makes the Ray-Ban/Oakley Meta camera glasses)"),
        Rule(r("snapchat|snap inc"), SMART_GLASSES, MEDIUM, "Snap (makes Spectacles camera glasses)"),
        Rule(r("tile,? inc|chipolo|pebblebee"), TRACKER, MEDIUM, "Tracker manufacturer"),
    )

    private fun applyRules(text: String?, rules: List<Rule>, prefix: String = ""): List<Flag> {
        if (text.isNullOrBlank()) return emptyList()
        val out = ArrayList<Flag>()
        for (rule in rules) {
            if (rule.re.containsMatchIn(text) && out.none { it.kind == rule.kind }) {
                out += Flag(rule.kind, rule.conf, if (prefix.isEmpty()) "${rule.why}: \"$text\"" else "$prefix ${rule.why.replaceFirstChar { it.lowercase() }}: \"$text\"")
            }
        }
        return out
    }

    fun textFlags(text: String?, prefix: String = ""): List<Flag> = applyRules(text, TEXT_RULES, prefix)

    fun vendorFlags(vendor: String?): List<Flag> {
        if (vendor.isNullOrBlank()) return emptyList()
        val out = ArrayList<Flag>()
        for (rule in VENDOR_RULES) {
            if (rule.re.containsMatchIn(vendor) && out.none { it.kind == rule.kind }) {
                out += Flag(rule.kind, rule.conf, "${rule.why} ($vendor)")
            }
        }
        return out
    }

    fun macFlags(mac: String): List<Flag> {
        val p = Vendors.prefix(mac)
        return if (p in FLOCK_WEAK_OUIS) listOf(
            Flag(FLOCK, LOW, "MAC prefix $p appears in community Flock lists, but it's a shared component maker — weak on its own")
        ) else emptyList()
    }

    fun companyFlags(companyId: Int): List<Flag> = when (companyId) {
        0x09C8 -> listOf(Flag(FLOCK, HIGH, "BLE manufacturer ID 0x09C8 (XUNTONG) — the battery module used on Flock / Raven units"))
        0x0D53 -> listOf(Flag(SMART_GLASSES, HIGH, "BLE manufacturer ID 0x0D53 (Luxottica) — Ray-Ban / Oakley Meta camera glasses"))
        0x01AB, 0x058E -> listOf(Flag(SMART_GLASSES, MEDIUM, "BLE manufacturer ID ${String.format("0x%04X", companyId)} (Meta) — Meta camera glasses or a Quest headset"))
        0x03C2 -> listOf(Flag(SMART_GLASSES, MEDIUM, "BLE manufacturer ID 0x03C2 (Snap) — Spectacles camera glasses"))
        else -> emptyList()
    }

    fun serviceFlags(uuid16: Int): List<Flag> = when {
        uuid16 in 0x3100..0x3500 -> listOf(Flag(FLOCK, HIGH, String.format("BLE service 0x%04X — Raven (Flock gunshot detector) service range", uuid16)))
        uuid16 == 0xFEED || uuid16 == 0xFEEC -> listOf(Flag(TRACKER, MEDIUM, "Tile tracker service"))
        uuid16 == 0xFD5A -> listOf(Flag(TRACKER, MEDIUM, "Samsung SmartThings Find service (SmartTag or a lost Galaxy device)"))
        else -> emptyList()
    }

    fun merge(vararg lists: List<Flag>): List<Flag> {
        val best = LinkedHashMap<ThreatKind, Flag>()
        for (l in lists) for (f in l) {
            val cur = best[f.kind]
            if (cur == null || f.confidence > cur.confidence) best[f.kind] = f
        }
        // Several weak hints of the same kind add up to "likely".
        val counts = lists.flatMap { it }.groupingBy { it.kind }.eachCount()
        return best.values.map { f ->
            if (f.confidence == LOW && (counts[f.kind] ?: 0) >= 2) f.copy(confidence = MEDIUM, reason = f.reason + " (+ other hints)") else f
        }.sortedByDescending { it.confidence.ordinal }
    }
}
