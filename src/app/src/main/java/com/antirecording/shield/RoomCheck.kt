package com.antirecording.shield

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One-tap room check: Bluetooth scan (20 s) -> Wi-Fi scan (30 s) -> optional LAN camera scan -> a report that can be exported as PDF.
 * It only ever reports HINTS (camera-like names, camera-maker MAC prefixes, camera-like network services). "No findings" never proves a room is clear.
 * Uses its own scanner instances; the caller must stop the radar scans first and must hold the scan permissions.
 */
class RoomCheck(
    private val ctx: Context,
    private val isKnown: (String) -> Boolean,
    private val listener: Listener
) {
    interface Listener { fun onStep(step: Int, total: Int, text: String); fun onDone(report: Report) }

    /** level 2 = strong hint, 1 = weak hint, 0 = information. */
    class Item(val level: Int, val kind: String, val title: String, val detail: String)

    class Report(
        val time: String, val items: List<Item>, val notes: List<String>,
        val btCount: Int, val btAnon: Int, val wifiBssids: Int, val wifiRadios: Int, val lanHosts: Int, val lanScanned: Boolean
    ) {
        val score: Int get() = items.sumOf { when (it.level) { 2 -> 3; 1 -> 1; else -> 0 }.toInt() }
        /** 2 = needs attention, 1 = worth checking, 0 = no obvious signs */
        val verdict: Int get() = if (score >= 3) 2 else if (score >= 1) 1 else 0
    }

    var running = false; private set
    private var bt: BtScanner? = null
    private var wifi: WifiScanner? = null
    private var lan: LanScanner? = null
    private val items = ArrayList<Item>()
    private val notes = ArrayList<String>()
    private var includeLan = false
    private var total = 2
    private var btCount = 0; private var btAnon = 0; private var wifiBssids = 0; private var wifiRadios = 0; private var lanHosts = 0

    fun start(includeLan: Boolean) {
        if (running) return
        running = true; this.includeLan = includeLan; total = if (includeLan) 3 else 2
        items.clear(); notes.clear(); btCount = 0; btAnon = 0; wifiBssids = 0; wifiRadios = 0; lanHosts = 0
        stepBle()
    }

    fun cancel() { running = false; bt?.stop(); wifi?.stop(); lan?.cancel() }

    // ---- step 1: Bluetooth
    private fun stepBle() {
        listener.onStep(1, total, L.t("Step 1/$total: scanning Bluetooth (20 s)…", "步驟 1/$total：掃描藍牙中（20 秒）…"))
        val b = BtScanner(ctx, { }, { if (running) finishBle() })
        bt = b
        val err = b.start(20_000L, true, false, 2.5)
        if (err != null) { notes.add(err); stepWifi() }
    }

    private fun finishBle() {
        val b = bt ?: return
        val list = b.list()
        btCount = list.size
        btAnon = list.count { it.name == null && it.le && !it.classic }
        for (d in list) {
            if (isKnown(d.address)) continue
            val byName = SuspectDetector.matchName(d.name)
            val byVendor = if (byName == null && d.classic) OuiDb.vendorOf(d.address, false) else null
            val reason = byName ?: byVendor ?: continue
            val dist = b.distanceM(d)?.let { String.format(Locale.US, "~%.1f m", it) } ?: "?"
            items.add(Item(if (byName != null) 2 else 1, "BT", "${d.name ?: "?"}  (${d.address})",
                L.t("Bluetooth · $dist · " + (if (byName != null) "name matches '$reason'" else "made by camera maker $reason"),
                    "藍牙 · $dist · " + (if (byName != null) "名稱符合「$reason」" else "廠商為攝影機製造商 $reason"))))
        }
        val near = list.filter { it.name != null && !isKnown(it.address) && (b.distanceM(it) ?: 99.0) <= 5.0 && SuspectDetector.matchName(it.name) == null }
            .sortedBy { b.distanceM(it) ?: 99.0 }.take(6)
        for (d in near) items.add(Item(0, "BT", "${d.name} (${d.address})", L.t("Named Bluetooth device within about 5 m", "5 公尺內有名稱的藍牙裝置")))
        stepWifi()
    }

    // ---- step 2: Wi-Fi
    private fun stepWifi() {
        if (!running) return
        listener.onStep(2, total, L.t("Step 2/$total: scanning Wi-Fi (30 s)…", "步驟 2/$total：掃描 Wi-Fi 中（30 秒）…"))
        val w = WifiScanner(ctx, { }, { if (running) finishWifi() })
        wifi = w
        val err = w.start(30_000L, false, 2.5)
        if (err != null) { notes.add(err); stepLan() }
    }

    private fun finishWifi() {
        val w = wifi ?: return
        val aps = w.list()
        val clusters = RadioCluster.cluster(aps) { w.distanceM(it) }
        wifiBssids = aps.size; wifiRadios = clusters.size
        for (g in clusters) {
            if (g.any { isKnown(it.bssid) }) continue
            val nameHit = g.firstOrNull { SuspectDetector.matchName(it.ssid) != null }
            val vendor = if (nameHit == null) g.firstNotNullOfOrNull { OuiDb.vendorOf(it.bssid, true) } else null
            if (nameHit == null && vendor == null) continue
            val best = g.minByOrNull { w.distanceM(it) ?: Double.MAX_VALUE } ?: g[0]
            val dist = w.distanceM(best)?.let { String.format(Locale.US, "~%.0f m", it) } ?: "?"
            val ssid = (nameHit ?: g[0]).ssid.ifBlank { "<hidden>" }
            items.add(Item(if (nameHit != null) 2 else 1, "WIFI", "$ssid  (${(nameHit ?: g[0]).bssid})",
                L.t("Wi-Fi · $dist · " + (if (nameHit != null) "name matches '${SuspectDetector.matchName(nameHit.ssid)}'" else "made by camera maker $vendor"),
                    "Wi-Fi · $dist · " + (if (nameHit != null) "名稱符合「${SuspectDetector.matchName(nameHit.ssid)}」" else "廠商為攝影機製造商 $vendor"))))
        }
        stepLan()
    }

    // ---- step 3: LAN (optional)
    private fun stepLan() {
        if (!running) return
        if (!includeLan) { finish(false); return }
        listener.onStep(3, total, L.t("Step 3/$total: LAN camera scan…", "步驟 3/$total：區網攝影機掃描…"))
        val l = LanScanner(ctx, { p -> if (running) listener.onStep(3, total, p) }, { hosts, err -> if (running) finishLan(hosts, err) })
        lan = l
        val err = l.start()
        if (err != null) { notes.add(err); finish(false) }
    }

    private fun finishLan(hosts: List<LanScanner.Host>, err: String?) {
        if (err != null) notes.add(err)
        lanHosts = hosts.size
        for (h in hosts) {
            val lvl = if (h.score >= 4) 2 else if (h.score >= 2) 1 else continue
            items.add(Item(lvl, "LAN", h.ip, L.t("LAN · ports ${h.ports.joinToString(",")} · ", "區網 · 連接埠 ${h.ports.joinToString(",")} · ") + h.reasons.joinToString("; ")))
        }
        finish(true)
    }

    private fun finish(lanRan: Boolean) {
        running = false
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        listener.onDone(Report(time, ArrayList(items), ArrayList(notes), btCount, btAnon, wifiBssids, wifiRadios, lanHosts, lanRan))
    }

    companion object {
        /** Plain text for the screen; list rows that need attention start with "⚠ " (the caller colours them red). */
        fun asText(r: Report): String {
            val sb = StringBuilder()
            sb.append(when (r.verdict) {
                2 -> L.t("RESULT: NEEDS ATTENTION", "結果：需要留意")
                1 -> L.t("RESULT: WORTH CHECKING", "結果：值得檢查")
                else -> L.t("RESULT: NO OBVIOUS SIGNS (this does not prove the room is clear)", "結果：沒有明顯跡象（不代表房間沒有問題）")
            })
            sb.append("\n").append(L.t("Bluetooth: ${r.btCount} devices (${r.btAnon} anonymous) · Wi-Fi: ${r.wifiRadios} radios (${r.wifiBssids} BSSIDs)",
                "藍牙：${r.btCount} 個裝置（${r.btAnon} 個匿名）· Wi-Fi：${r.wifiRadios} 個無線電（${r.wifiBssids} 個 BSSID）"))
            if (r.lanScanned) sb.append("\n").append(L.t("LAN: ${r.lanHosts} hosts with open service ports", "區網：${r.lanHosts} 台主機有開啟服務埠"))
            sb.append("\n")
            for (e in r.items.filter { it.level > 0 }.sortedByDescending { it.level }) sb.append("\n⚠ ").append(e.title).append("\n      ").append(e.detail)
            val info = r.items.filter { it.level == 0 }
            if (info.isNotEmpty()) {
                sb.append("\n\n").append(L.t("For information:", "僅供參考："))
                for (e in info) sb.append("\n• ").append(e.title).append(" — ").append(e.detail)
            }
            if (r.notes.isNotEmpty()) { sb.append("\n\n").append(L.t("Notes:", "備註：")); for (n in r.notes) sb.append("\n• ").append(n) }
            return sb.toString()
        }
    }
}

/** A4 PDF of a [RoomCheck.Report] (android.graphics.pdf.PdfDocument, no extra libraries). */
object ReportPdf {
    private const val W = 595
    private const val H = 842
    private const val M = 40

    fun build(r: RoomCheck.Report, appVersion: String): ByteArray {
        val doc = PdfDocument()
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
        var c = page.canvas
        var y = M.toFloat()
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = Color.BLACK }
        val bold = Paint(body).apply { typeface = Typeface.DEFAULT_BOLD }
        val title = Paint(bold).apply { textSize = 20f }
        val head = Paint(bold).apply { textSize = 14f }

        fun newPage() { doc.finishPage(page); pageNo++; page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create()); c = page.canvas; y = M.toFloat() }
        fun text(s: String, p: Paint, color: Int = Color.BLACK, indent: Float = 0f) {
            val old = p.color; p.color = color
            for (para in s.replace("\r", "").split("\n")) {
                var line = para
                if (line.isEmpty()) { y += p.textSize * 0.8f; continue }
                while (line.isNotEmpty()) {
                    var take = p.breakText(line, true, W - 2f * M - indent, null)
                    if (take <= 0) take = 1
                    if (take < line.length) { val sp = line.lastIndexOf(' ', take - 1); if (sp > take / 2) take = sp + 1 }
                    if (y + p.textSize * 1.5f > H - M) newPage()
                    c.drawText(line.substring(0, take), M + indent, y + p.textSize, p)
                    y += p.textSize * 1.5f
                    line = line.substring(take)
                }
            }
            p.color = old
        }

        text(L.t("Room check report", "房間檢查報告"), title)
        text("Hush Shield $appVersion · ${r.time} · ${Build.MANUFACTURER} ${Build.MODEL}", body, Color.DKGRAY)
        y += 8
        val (vt, vc) = when (r.verdict) {
            2 -> Pair(L.t("NEEDS ATTENTION", "需要留意"), Color.rgb(200, 30, 60))
            1 -> Pair(L.t("WORTH CHECKING", "值得檢查"), Color.rgb(210, 120, 0))
            else -> Pair(L.t("NO OBVIOUS SIGNS", "沒有明顯跡象"), Color.rgb(30, 130, 60))
        }
        text(vt, head, vc)
        text(L.t("This report lists hints only (camera-like names, camera-maker MAC prefixes, camera-like network services). It is not proof either way.",
            "本報告只列出線索（像攝影機的名稱、攝影機製造商的 MAC 前綴、像攝影機的網路服務），不能作為有或沒有的證明。"), body)
        y += 8
        text(L.t("Summary", "摘要"), head)
        text("• " + L.t("Bluetooth: ${r.btCount} devices, ${r.btAnon} anonymous", "藍牙：${r.btCount} 個裝置，其中 ${r.btAnon} 個匿名"), body)
        text("• " + L.t("Wi-Fi: ${r.wifiBssids} BSSIDs in ${r.wifiRadios} radios", "Wi-Fi：${r.wifiRadios} 個無線電共 ${r.wifiBssids} 個 BSSID"), body)
        text("• " + (if (r.lanScanned) L.t("LAN scan: ${r.lanHosts} hosts with open service ports", "區網掃描：${r.lanHosts} 台主機有開啟服務埠") else L.t("LAN scan: not run", "區網掃描：未執行")), body)
        y += 8
        text(L.t("Findings", "發現項目"), head)
        val hits = r.items.filter { it.level > 0 }.sortedByDescending { it.level }
        if (hits.isEmpty()) text(L.t("No names, vendors or services that look like a camera or recorder.", "沒有看起來像攝影機或錄音器的名稱、廠商或服務。"), body)
        for (e in hits) {
            val col = if (e.level == 2) Color.rgb(200, 30, 60) else Color.rgb(210, 120, 0)
            text("⚠ " + e.title, bold, col)
            text(e.detail, body, Color.DKGRAY, 14f)
        }
        val info = r.items.filter { it.level == 0 }
        if (info.isNotEmpty()) {
            y += 6; text(L.t("For information", "僅供參考"), head)
            for (e in info) text("• ${e.title} — ${e.detail}", body, Color.DKGRAY)
        }
        if (r.notes.isNotEmpty()) { y += 6; text(L.t("Notes", "備註"), head); for (n in r.notes) text("• $n", body, Color.DKGRAY) }
        y += 10
        text(L.t("What to check physically", "請實際檢查"), head)
        listOf(
            L.t("Switch off the lights and sweep a phone flashlight slowly: a camera lens gives a small bright glint (the app's Lens finder helps).", "關燈後用手機手電筒慢慢掃視：攝影機鏡頭會出現小而亮的反光（可用本 App 的鏡頭輔助檢查）。"),
            L.t("Look for tiny holes or lenses in smoke detectors, clocks, USB chargers, power strips, outlets, air vents, picture frames, plants, tissue boxes, bookshelves and TV boxes.", "檢查煙霧偵測器、時鐘、USB 充電器、延長線、插座、通風口、相框、盆栽、面紙盒、書架與電視盒上是否有細小孔洞或鏡頭。"),
            L.t("In a dark room look for small red, green or infrared LEDs; the front camera or another camera app can show infrared light.", "在暗處找小型紅色、綠色或紅外線燈點；前鏡頭或其他相機 App 可以看到紅外線。"),
            L.t("Mirrors: press a fingertip on the glass; with a two-way mirror there is no gap between your finger and its reflection.", "鏡子：用指尖貼住玻璃；若是雙面鏡，指尖與倒影之間不會有空隙。"),
            L.t("Unplug or move unfamiliar devices and see whether a Wi-Fi network or Bluetooth device disappears.", "拔掉或移動不認識的裝置，看看有沒有 Wi-Fi 或藍牙裝置跟著消失。"),
            L.t("If you manage the router, check its list of connected devices for unknown ones.", "如果您管理路由器，請檢查其連線裝置清單中有沒有不認識的裝置。"),
            L.t("If you find a camera in a rental or hotel: do not tamper with it, photograph it, and contact the host, front desk or the police.", "如果在民宿或旅館發現攝影機：不要自行破壞，請拍照存證，並聯絡房東、櫃台或警方。")
        ).forEach { text("[  ] $it", body) }
        y += 10
        text(L.t("Limits", "限制"), head)
        text(L.t("A camera that is switched off, has no radio, or records only to a memory card cannot be found by radio scanning. Distances and names are estimates. Always follow local law and obtain consent before testing networks or spaces that are not yours.",
            "關閉中、沒有無線電、或只錄到記憶卡的攝影機，無法用無線電掃描找出。距離與名稱只是估計。測試不屬於您的網路或空間前，請遵守當地法律並取得同意。"), body)
        doc.finishPage(page)
        val out = ByteArrayOutputStream(); doc.writeTo(out); doc.close()
        return out.toByteArray()
    }
}
