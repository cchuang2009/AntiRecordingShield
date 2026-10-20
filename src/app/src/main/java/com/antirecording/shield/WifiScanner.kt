package com.antirecording.shield

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.max
import kotlin.math.pow

/**
 * Wi-Fi radar source: access points / hotspots in range (Android cannot list other Wi-Fi *clients*).
 *
 * Distance, in order of preference:
 *  1. Wi-Fi RTT (IEEE 802.11mc round-trip time, Android 9+) for APs that support it -> measured distance (~1-2 m typical error)
 *  2. RSSI estimate:  d = 10 ^ ((RSSI@1m - RSSI) / (10 n)),  RSSI@1m = -40 (2.4 GHz) / -47 (5 GHz) / -50 (6 GHz) dBm, n = 2.0 / 2.5 / 3.2
 *
 * Android throttles foreground scans (about 4 per 2 minutes), so a new scan is requested every 20 s and cached results are re-read every 4 s.
 */
class WifiScanner(
    private val ctx: Context,
    private val onChange: () -> Unit,
    private val onFinished: (String) -> Unit
) {
    class Ap(val bssid: String) {
        var ssid = ""; var freq = 0; var security = ""; var mc = false
        var lastTs = 0L; var firstSeen = 0L; var lastSeen = 0L
        var rttM: Double? = null; var rttStdM: Double? = null; var rttAt = 0L
        private val samples = ArrayDeque<Int>()
        fun add(rssi: Int, now: Long) { samples.addLast(rssi); while (samples.size > 5) samples.removeFirst(); lastSeen = now }
        fun hasRssi() = samples.isNotEmpty()
        fun rssi(): Int { val s = samples.sorted(); return s[s.size / 2] }
        fun band(): Int = if (freq < 3000) 24 else if (freq < 5925) 5 else 6
        fun channel(): Int = when {
            freq == 2484 -> 14
            freq < 3000 -> (freq - 2407) / 5
            freq >= 5955 -> (freq - 5950) / 5
            else -> (freq - 5000) / 5
        }
    }

    val aps = LinkedHashMap<String, Ap>()
    var scanning = false; private set
    var lastError: String? = null; private set
    var rttEnabled = false; private set
    var rttAvailable: Boolean? = null; private set
    var scansRequested = 0; private set
    var scansThrottled = 0; private set
    private var n = 2.5
    private var startedAt = 0L
    private var endAt = 0L
    private var lastScanReq = 0L
    private var lastHarvest = 0L
    private var lastRange = 0L
    private var rttBusy = false
    private val handler = Handler(Looper.getMainLooper())
    private var receiverRegistered = false
    private val rttFreshMs = 15_000L
    private val apWindowMs = 90_000L

    private fun wifi(): WifiManager? = ctx.applicationContext.getSystemService(WifiManager::class.java)

    /** @return an error message, or null when the radar has started. */
    @SuppressLint("MissingPermission")
    fun start(durationMs: Long, useRtt: Boolean, pathLossN: Double): String? {
        val w = wifi() ?: return "This device has no Wi-Fi."
        if (!w.isWifiEnabled) return "Wi-Fi is off. Turn it on in Android settings, then try again."
        stop()
        aps.clear(); lastError = null; n = pathLossN; rttEnabled = useRtt; rttAvailable = null; rttBusy = false
        scansRequested = 0; scansThrottled = 0
        val now = SystemClock.uptimeMillis(); startedAt = now; endAt = now + durationMs; lastScanReq = 0L; lastHarvest = 0L; lastRange = 0L
        scanning = true
        val f = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED) else ctx.registerReceiver(receiver, f)
        receiverRegistered = true
        try { harvest(w); requestScan(w) } catch (e: SecurityException) { stop(); return "Wi-Fi scan permission is missing." }
        handler.postDelayed(tick, 1000)
        return null
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        if (receiverRegistered) { try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}; receiverRegistered = false }
        scanning = false
    }

    fun remainingSec(): Int = if (scanning) max(0L, (endAt - SystemClock.uptimeMillis()) / 1000).toInt() else 0

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(c: Context, i: Intent) { val w = wifi() ?: return; try { harvest(w) } catch (_: SecurityException) {} }
    }

    private val tick = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            if (!scanning) return
            val now = SystemClock.uptimeMillis()
            if (now >= endAt) { stop(); onFinished("Scan complete"); return }
            val w = wifi()
            if (w != null) try {
                if (now - lastScanReq >= 20_000L) requestScan(w)
                if (now - lastHarvest >= 4_000L) harvest(w)
                if (rttEnabled && now - lastRange >= 3_000L) range(w)
            } catch (_: SecurityException) { lastError = "Wi-Fi permission was revoked" }
            onChange(); handler.postDelayed(this, 1000)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestScan(w: WifiManager) {
        lastScanReq = SystemClock.uptimeMillis(); scansRequested++
        @Suppress("DEPRECATION") val ok = w.startScan()
        if (!ok) scansThrottled++
    }

    @SuppressLint("MissingPermission")
    private fun harvest(w: WifiManager) {
        val now = SystemClock.uptimeMillis(); lastHarvest = now
        val bootUs = SystemClock.elapsedRealtime() * 1000
        for (sr in w.scanResults ?: return) {
            val bssid = sr.BSSID ?: continue
            if ((bootUs - sr.timestamp) / 1_000_000L > 120) continue          // ignore stale cache entries
            val ap = aps.getOrPut(bssid) { Ap(bssid).also { it.firstSeen = now } }
            if (sr.timestamp != ap.lastTs) { ap.lastTs = sr.timestamp; ap.add(sr.level, now) } else ap.lastSeen = max(ap.lastSeen, now - 1)
            @Suppress("DEPRECATION") val s = sr.SSID
            ap.ssid = s ?: ""; ap.freq = sr.frequency; ap.security = security(sr.capabilities ?: ""); ap.mc = sr.is80211mcResponder
        }
    }

    @SuppressLint("MissingPermission", "NewApi")
    private fun range(w: WifiManager) {
        lastRange = SystemClock.uptimeMillis()
        if (Build.VERSION.SDK_INT < 28) { rttAvailable = false; return }
        val mgr = ctx.getSystemService(WifiRttManager::class.java)
        if (mgr == null || !mgr.isAvailable) { rttAvailable = false; return }
        rttAvailable = true
        if (rttBusy) return
        val cands = (w.scanResults ?: return).filter { it.is80211mcResponder }.sortedByDescending { it.level }.take(RangingRequest.getMaxPeers())
        if (cands.isEmpty()) return
        rttBusy = true
        try {
            mgr.startRanging(RangingRequest.Builder().addAccessPoints(cands).build(), ctx.mainExecutor, object : RangingResultCallback() {
                override fun onRangingFailure(code: Int) { rttBusy = false; lastError = "Wi-Fi RTT ranging failed (code $code)"; onChange() }
                override fun onRangingResults(results: MutableList<RangingResult>) {
                    rttBusy = false
                    val now = SystemClock.uptimeMillis()
                    for (r in results) {
                        if (r.status != RangingResult.STATUS_SUCCESS) continue
                        val mac = r.macAddress?.toString()?.lowercase() ?: continue
                        val ap = aps.values.firstOrNull { it.bssid.lowercase() == mac } ?: continue
                        ap.rttM = r.distanceMm / 1000.0; ap.rttStdM = r.distanceStdDevMm / 1000.0; ap.rttAt = now
                    }
                    onChange()
                }
            })
        } catch (e: SecurityException) { rttBusy = false; lastError = "Wi-Fi RTT permission missing" }
    }

    private fun security(c: String) = when {
        c.contains("SAE") -> "WPA3"
        c.contains("RSN") || c.contains("WPA2") -> "WPA2"
        c.contains("WPA") -> "WPA"
        c.contains("WEP") -> "WEP"
        c.contains("OWE") -> "OWE"
        else -> "OPEN"
    }

    fun isMeasured(a: Ap): Boolean = a.rttM != null && SystemClock.uptimeMillis() - a.rttAt < rttFreshMs && (a.rttStdM ?: 99.0) < 3.0

    /** @return distance in metres, or null when there is neither an RTT result nor an RSSI sample. */
    fun distanceM(a: Ap): Double? {
        if (isMeasured(a)) return a.rttM!!.coerceIn(0.0, 100.0)
        if (!a.hasRssi()) return null
        val ref = when (a.band()) { 24 -> -40; 5 -> -47; else -> -50 }
        return 10.0.pow((ref - a.rssi()) / (10.0 * n)).coerceIn(0.1, 150.0)
    }

    fun list(): List<Ap> {
        val now = SystemClock.uptimeMillis()
        return aps.values.filter { !scanning || now - it.lastSeen <= apWindowMs }.sortedBy { distanceM(it) ?: Double.MAX_VALUE }
    }

    fun summary(): String {
        val all = aps.values
        val nearest = all.mapNotNull { distanceM(it) }.minOrNull()
        return "APs ${all.size} · 2.4G ${all.count { it.band() == 24 }} · 5/6G ${all.count { it.band() != 24 }} · 802.11mc ${all.count { it.mc }} · RTT ${all.count { isMeasured(it) }}" +
            (nearest?.let { " · nearest ≈ " + String.format(java.util.Locale.US, "%.1f m", it) } ?: "")
    }
}
