package com.antirecording.shield

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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.max
import kotlin.math.pow

/**
 * BLE scan + Classic (BR/EDR) discovery. All callbacks arrive on the main thread.
 *
 * When both are enabled they run in alternating 12 s slots (BLE, Classic, BLE, ...): on many phones a running
 * Classic inquiry starves BLE scanning, so running them at the same time can return nothing at all.
 * BLE uses non-legacy scanning (extended advertisements too) at LOW_LATENCY.
 *
 * Distance: log-distance path-loss model   d = 10 ^ ((RSSI@1m - RSSI) / (10 * n))
 *   RSSI@1m = advertised TX power - 41 dB when the advertisement carries it, otherwise -59 dBm.
 *   n = 2.0 open space, 2.5 indoor, 3.2 obstructed.  RSSI is the median of the last 7 samples.
 * Only an order-of-magnitude estimate (bodies, walls and phone orientation move it by several metres).
 */
class BtScanner(
    private val ctx: Context,
    private val onChange: () -> Unit,
    private val onFinished: (String) -> Unit
) {
    class Dev(val address: String) {
        var name: String? = null
        var le = false
        var classic = false
        var txPower: Int? = null
        var vendor: String? = null
        var cls: String? = null
        private val samples = ArrayDeque<Int>()
        var firstSeen = 0L
        var lastSeen = 0L
        fun add(rssi: Int, now: Long) { samples.addLast(rssi); while (samples.size > 7) samples.removeFirst(); lastSeen = now }
        fun hasRssi() = samples.isNotEmpty()
        fun rssi(): Int { val s = samples.sorted(); return s[s.size / 2] }
    }

    val devices = LinkedHashMap<String, Dev>()
    var scanning = false; private set
    var lastError: String? = null; private set
    // raw counters, shown to the user so "nothing found" can be diagnosed
    var bleHits = 0; private set
    var classicHits = 0; private set
    var discoveryRuns = 0; private set
    var discoveryStartOk: Boolean? = null; private set
    var bleStartError: String? = null; private set

    private var n = 2.5
    private var startedAt = 0L
    private var endAt = 0L
    private var wantBle = false
    private var wantClassic = false
    private var classicOn = false
    private val slotMs = 12_000L
    private val handler = Handler(Looper.getMainLooper())
    private var leCallback: ScanCallback? = null
    private var receiverRegistered = false
    private var notifyPending = false
    private val anonWindowMs = 10_000L
    /** Anonymous (no name, random-address) LE devices that list(maxAnon) left out on the last call. */
    var hiddenAnon = 0; private set
    private var lastLeAt = 0L
    private var lastClassicAt = 0L
    private val activeWindowMs get() = if (wantBle && wantClassic) 36_000L else 15_000L

    private fun adapter(): BluetoothAdapter? = ctx.getSystemService(BluetoothManager::class.java)?.adapter

    /** @return an error message, or null when the scan has started. */
    @SuppressLint("MissingPermission")
    fun start(durationMs: Long, ble: Boolean, classic: Boolean, pathLossN: Double): String? {
        val ad = adapter() ?: return "This device has no Bluetooth."
        if (!ad.isEnabled) return "Bluetooth is off. Turn it on in Android settings, then try again."
        stop()
        devices.clear(); lastError = null; bleStartError = null; discoveryStartOk = null
        bleHits = 0; classicHits = 0; discoveryRuns = 0; lastLeAt = 0L; lastClassicAt = 0L
        n = pathLossN; wantBle = ble; wantClassic = classic
        startedAt = SystemClock.uptimeMillis(); endAt = startedAt + durationMs; scanning = true
        try {
            if (classic) {
                val f = IntentFilter().apply { addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED) }
                if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED) else ctx.registerReceiver(receiver, f)
                receiverRegistered = true
            }
            syncPhase()
        } catch (e: SecurityException) { stop(); return "Bluetooth scan permission is missing." }
        handler.postDelayed(tick, 500)
        return null
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        handler.removeCallbacksAndMessages(null); notifyPending = false
        stopLe(); stopClassic()
        if (receiverRegistered) { try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}; receiverRegistered = false }
        scanning = false
    }

    fun remainingSec(): Int = if (scanning) max(0L, (endAt - SystemClock.uptimeMillis()) / 1000).toInt() else 0

    /** "Last updated: BLE 2 s ago · Classic 14 s ago (listening: Classic)" while scanning, else "". */
    fun updatedText(): String {
        if (!scanning) return ""
        val now = SystemClock.uptimeMillis()
        fun ago(t: Long) = if (t == 0L) "no data yet" else "${(now - t) / 1000} s ago"
        val parts = ArrayList<String>()
        if (wantBle) parts.add("BLE ${ago(lastLeAt)}")
        if (wantClassic) parts.add("Classic ${ago(lastClassicAt)}")
        val ph = if (wantBle && wantClassic) " (listening: ${phase()})" else ""
        return "Last updated: " + parts.joinToString(" · ") + ph
    }

    /** Which radio is listening right now. */
    fun phase(): String = if (!scanning) "idle" else if (leCallback != null && classicOn) "BLE+Classic" else if (leCallback != null) "BLE" else if (classicOn) "Classic" else "waiting"

    fun raw(): String = "BLE hits $bleHits · Classic hits $classicHits · inquiries $discoveryRuns" +
        (discoveryStartOk?.let { " (start ${if (it) "ok" else "REFUSED"})" } ?: "") + (bleStartError?.let { " · $it" } ?: "")

    /** Devices to show: while scanning only those heard recently, afterwards everything seen in the session. Nearest first. */
    fun list(maxAnon: Int = Int.MAX_VALUE): List<Dev> {
        val now = SystemClock.uptimeMillis()
        val live = devices.values.filter { !scanning || now - it.lastSeen <= windowFor(it) }
            .sortedBy { distanceM(it) ?: Double.MAX_VALUE }
        var kept = 0; var hidden = 0
        val out = ArrayList<Dev>()
        for (d in live) {
            if (isAnon(d)) { if (kept < maxAnon) { out.add(d); kept++ } else hidden++ } else out.add(d)
        }
        hiddenAnon = hidden
        return out
    }

    /** No name, LE only, no/Apple vendor: typically phones / tags with a rotating random address - they flood the list. */
    private fun isAnon(d: Dev) = d.name == null && !d.classic && (d.vendor == null || d.vendor == "Apple")
    private fun windowFor(d: Dev) = if (isAnon(d)) anonWindowMs else activeWindowMs

    /** 0 = just heard .. 1 = about to be dropped from the list (used to fade radar blips). */
    fun ageFrac(d: Dev): Float = if (!scanning) 0f else ((SystemClock.uptimeMillis() - d.lastSeen).toFloat() / windowFor(d)).coerceIn(0f, 1f)

    /** Coalesce the flood of per-advertisement callbacks into at most one UI refresh per 250 ms. */
    private fun notifySoon() {
        if (notifyPending) return
        notifyPending = true
        handler.postDelayed({ notifyPending = false; onChange() }, 250)
    }

    fun distanceM(d: Dev): Double? {
        if (!d.hasRssi()) return null
        val ref = (d.txPower?.let { it - 41 } ?: -59).coerceIn(-75, -45)
        return 10.0.pow((ref - d.rssi()) / (10.0 * n)).coerceIn(0.1, 100.0)
    }

    fun summary(): String {
        val all = devices.values
        val nearest = all.mapNotNull { distanceM(it) }.minOrNull()
        return "BLE ${all.count { it.le }} · Classic ${all.count { it.classic }}" + (nearest?.let { " · nearest ≈ " + String.format(java.util.Locale.US, "%.1f m", it) } ?: "")
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!scanning) return
            if (SystemClock.uptimeMillis() >= endAt) { stop(); onFinished("Scan complete"); return }
            try { syncPhase() } catch (_: SecurityException) { lastError = "Bluetooth permission was revoked" }
            onChange(); handler.postDelayed(this, 500)
        }
    }

    private fun slot(): Long = (SystemClock.uptimeMillis() - startedAt) / slotMs

    @SuppressLint("MissingPermission")
    private fun syncPhase() {
        val ad = adapter() ?: return
        val wantLeNow = wantBle && (!wantClassic || slot() % 2L == 0L)
        val wantClassicNow = wantClassic && (!wantBle || slot() % 2L == 1L)
        if (wantLeNow && leCallback == null) startLe(ad) else if (!wantLeNow && leCallback != null) stopLe()
        if (wantClassicNow && !classicOn) startClassic(ad) else if (!wantClassicNow && classicOn) stopClassic()
    }

    private fun dev(address: String): Dev = devices.getOrPut(address) { Dev(address).also { it.firstSeen = SystemClock.uptimeMillis() } }

    @SuppressLint("MissingPermission")
    private fun startLe(ad: BluetoothAdapter) {
        val sc = ad.bluetoothLeScanner ?: run { bleStartError = "BLE scanner unavailable"; return }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) { handleLe(result) }
            override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { handleLe(it) } }
            override fun onScanFailed(errorCode: Int) { bleStartError = "BLE scan failed (code $errorCode)"; leCallback = null; onChange() }
        }
        leCallback = cb
        try {
            sc.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setLegacy(false).setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED).build(), cb)
        } catch (e: IllegalArgumentException) {   // some chips reject non-legacy / all-PHY scanning: fall back to the plain scan
            sc.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopLe() {
        val cb = leCallback ?: return
        leCallback = null
        try { adapter()?.bluetoothLeScanner?.stopScan(cb) } catch (_: Exception) {}
    }

    private fun handleLe(r: ScanResult) {
        val now = SystemClock.uptimeMillis()
        bleHits++; lastLeAt = now
        val d = dev(r.device.address)
        d.le = true
        d.add(r.rssi, now)
        r.scanRecord?.deviceName?.takeIf { it.isNotBlank() }?.let { d.name = it }
        val tx = r.txPower
        if (tx != ScanResult.TX_POWER_NOT_PRESENT) d.txPower = tx
        else r.scanRecord?.txPowerLevel?.takeIf { it != Int.MIN_VALUE }?.let { d.txPower = it }
        val msd = r.scanRecord?.manufacturerSpecificData
        if (msd != null && msd.size() > 0) d.vendor = vendorName(msd.keyAt(0))
        notifySoon()
    }

    @SuppressLint("MissingPermission")
    private fun startClassic(ad: BluetoothAdapter) {
        if (ad.isDiscovering) ad.cancelDiscovery()
        classicOn = true; discoveryRuns++
        val ok = ad.startDiscovery()
        discoveryStartOk = ok
        if (!ok) { lastError = "Classic discovery was refused by Android (permission or Location off?)"; classicOn = false }
    }

    @SuppressLint("MissingPermission")
    private fun stopClassic() {
        if (!classicOn) return
        classicOn = false
        try { if (adapter()?.isDiscovering == true) adapter()?.cancelDiscovery() } catch (_: Exception) {}
    }

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    @Suppress("DEPRECATION")
                    val bd: BluetoothDevice = (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                                               else i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)) ?: return
                    classicHits++
                    val now = SystemClock.uptimeMillis(); lastClassicAt = now
                    val d = dev(bd.address)
                    d.classic = true
                    val rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    if (rssi != Short.MIN_VALUE.toInt()) d.add(rssi, now) else d.lastSeen = now
                    val nm = i.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: try { bd.name } catch (_: SecurityException) { null }
                    if (!nm.isNullOrBlank()) d.name = nm
                    try { d.cls = classLabel(bd.bluetoothClass?.majorDeviceClass) } catch (_: SecurityException) {}
                    notifySoon()
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    // an inquiry lasts ~12 s; if we are still in a Classic slot, run another one
                    classicOn = false
                    if (scanning && wantClassic && SystemClock.uptimeMillis() < endAt - 4000) handler.postDelayed({
                        if (scanning) try { syncPhase() } catch (_: SecurityException) {}
                    }, 1200)
                }
            }
        }
    }

    private fun classLabel(major: Int?): String? = when (major) {
        BluetoothClass.Device.Major.PHONE -> "phone"
        BluetoothClass.Device.Major.COMPUTER -> "computer"
        BluetoothClass.Device.Major.AUDIO_VIDEO -> "audio/video"
        BluetoothClass.Device.Major.WEARABLE -> "wearable"
        BluetoothClass.Device.Major.PERIPHERAL -> "peripheral"
        BluetoothClass.Device.Major.HEALTH -> "health"
        BluetoothClass.Device.Major.IMAGING -> "imaging"
        BluetoothClass.Device.Major.NETWORKING -> "networking"
        BluetoothClass.Device.Major.TOY -> "toy"
        else -> null
    }

    private fun vendorName(id: Int): String? = when (id) {
        0x004C -> "Apple"; 0x0075 -> "Samsung"; 0x0006 -> "Microsoft"; 0x00E0 -> "Google"
        0x027D -> "Huawei"; 0x038F -> "Xiaomi"; 0x012D -> "Sony"; 0x009E -> "Bose"; 0x0087 -> "Garmin"
        else -> null
    }
}
