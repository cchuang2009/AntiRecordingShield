package com.antirecording.shield

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Camera-maker lookup by MAC prefix (OUI). Nothing is hard-coded from memory: "Update" downloads the public IEEE MA-L registry
 * (https://standards-oui.ieee.org/oui/oui.csv, needs the INTERNET permission), keeps only the organisations whose name matches
 * [VENDORS] and stores them in filesDir/camera_oui.csv.  A match means "made by a company that sells cameras" - it is a HINT only.
 */
object OuiDb {
    private const val SOURCE = "https://standards-oui.ieee.org/oui/oui.csv"
    private val VENDORS = Regex("hikvision|dahua|ezviz|reolink|wyze|uniview|foscam|amcrest|annke|lorex|swann|vivotek|geovision|mobotix|hanwha|tiandy|zosi|axis communications|arlo|jovision|sunell|ipcam|webcam|camera", RegexOption.IGNORE_CASE)
    @Volatile private var map: Map<String, String> = emptyMap()

    private fun file(ctx: Context) = File(ctx.filesDir, "camera_oui.csv")

    fun load(ctx: Context) {
        val f = file(ctx)
        if (!f.exists()) { map = emptyMap(); return }
        val m = HashMap<String, String>()
        try { f.forEachLine { l -> if (l.length > 7 && l[6] == ',') m[l.substring(0, 6)] = l.substring(7) } } catch (_: Exception) {}
        map = m
    }

    fun size(): Int = map.size
    fun lastUpdated(ctx: Context): Long = ctx.getSharedPreferences("hush_oui", Context.MODE_PRIVATE).getLong("t", 0L)

    /** @param clearLocalBit true for Wi-Fi BSSIDs (virtual BSSIDs set the locally-administered bit); false for Bluetooth. */
    fun vendorOf(mac: String, clearLocalBit: Boolean): String? {
        val m = map
        if (m.isEmpty()) return null
        val hex = mac.replace(":", "").replace("-", "").uppercase()
        if (hex.length < 6) return null
        var first = hex.substring(0, 2).toIntOrNull(16) ?: return null
        if ((first and 0x02) != 0) { if (!clearLocalBit) return null; first = first and 0x02.inv() }
        return m[String.format("%02X", first) + hex.substring(2, 6)]
    }

    private fun parseCsv(line: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            if (q) { if (c == '"') { if (i + 1 < line.length && line[i + 1] == '"') { sb.append('"'); i++ } else q = false } else sb.append(c) }
            else if (c == '"') q = true
            else if (c == ',') { out.add(sb.toString()); sb.setLength(0) }
            else sb.append(c)
            i++
        }
        out.add(sb.toString()); return out
    }

    /** Downloads the registry on a background thread; [done] runs on the main thread. */
    fun update(ctx: Context, done: (ok: Boolean, msg: String) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            var ok = false; var msg: String
            try {
                val conn = URL(SOURCE).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000; conn.readTimeout = 60_000
                conn.setRequestProperty("User-Agent", "HushShield-OUI-lookup")
                if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode}")
                val tmp = File(app.filesDir, "camera_oui.tmp")
                var kept = 0
                conn.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    tmp.bufferedWriter(Charsets.UTF_8).use { w ->
                        for (line in lines) {
                            val c = parseCsv(line)
                            if (c.size >= 3 && c[0] == "MA-L" && c[1].length == 6 && VENDORS.containsMatchIn(c[2])) {
                                w.write(c[1].uppercase() + "," + c[2].replace(',', ' ').replace('\n', ' ')); w.newLine(); kept++
                            }
                        }
                    }
                }
                if (kept == 0) throw RuntimeException("no matching vendors found")
                file(app).delete(); tmp.renameTo(file(app))
                app.getSharedPreferences("hush_oui", Context.MODE_PRIVATE).edit().putLong("t", System.currentTimeMillis()).apply()
                load(app); ok = true; msg = "$kept"
            } catch (e: Exception) { msg = e.message ?: e.javaClass.simpleName }
            Handler(Looper.getMainLooper()).post { done(ok, msg) }
        }.start()
    }
}
