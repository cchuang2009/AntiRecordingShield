package com.antirecording.shield

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.*

/**
 * Direction finder for ONE Bluetooth LE device.
 *
 * The user holds the phone flat in front of the chest (top edge pointing forward) and turns slowly on the spot.
 * Every advertisement of the target (high-rate, filtered by address) is stored in a 10-degree bin of the phone's
 * compass heading. The bin with the strongest signal (centroid of all bins within 6 dB of the peak) is the estimate.
 * The human body shadows 2.4 GHz by several dB, so the signal peaks when the user FACES the device (device in front,
 * body behind the phone). Walls and reflections add error: expect roughly +-30..45 degrees, and no useful result
 * when the target is far away or the profile is flat.  Wi-Fi cannot be used: Android scans it only every ~30 s.
 *
 * Heading = magnetic north (not true north); only the relative direction matters here.
 * All callbacks arrive on the main thread.
 */
class DirectionFinder(private val ctx: Context, private val onChange: () -> Unit) {
    class Estimate(val bearing: Float, val spreadDb: Double, val coverage: Float, val peakDbm: Double)

    companion object {
        const val BINS = 36                 // 10 degrees each
        const val SWEEP_MS = 20_000L        // suggested time for one full turn
        const val MAX_MS = 180_000L         // auto-stop (battery)
    }

    var running = false; private set
    var targetAddress = ""; private set
    var targetName = ""; private set
    var error: String? = null; private set
    var heading = Float.NaN; private set                 // degrees clockwise from magnetic north, where the phone points
    var compassAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH; private set
    var samples = 0; private set
    var liveRssi: Double? = null; private set            // smoothed, dBm
    var lastPacketAt = 0L; private set

    private val mean = DoubleArray(BINS)
    private val cnt = IntArray(BINS)
    private var startedAt = 0L
    private var sinH = 0.0
    private var cosH = 1.0
    private var haveHeading = false
    private val handler = Handler(Looper.getMainLooper())
    private var cb: ScanCallback? = null
    private var sensorMgr: SensorManager? = null
    private val rot = FloatArray(9)

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rot, e.values)
            // rot maps device axes -> world (x East, y North, z Up).  Row 0 = East, row 1 = North, row 2 = Up.
            val east: Float; val north: Float
            if (abs(rot[8]) > 0.7f) { east = rot[1]; north = rot[4] }      // held flat: top edge of the phone (device +Y)
            else { east = -rot[2]; north = -rot[5] }                         // held upright: back of the phone (device -Z)
            if (hypot(east.toDouble(), north.toDouble()) < 0.05) return
            val h = atan2(east.toDouble(), north.toDouble())
            if (!haveHeading) { sinH = sin(h); cosH = cos(h); haveHeading = true }
            else { sinH += (sin(h) - sinH) * 0.25; cosH += (cos(h) - cosH) * 0.25 }   // circular low-pass
            heading = ((Math.toDegrees(atan2(sinH, cosH)) + 360.0) % 360.0).toFloat()
        }
        override fun onAccuracyChanged(s: Sensor?, accuracy: Int) { compassAccuracy = accuracy }
    }

    /** @return an error message, or null when the finder has started. */
    @SuppressLint("MissingPermission")
    fun start(address: String, name: String): String? {
        stop()
        val ad = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return "This device has no Bluetooth."
        if (!ad.isEnabled) return "Bluetooth is off. Turn it on in Android settings, then try again."
        val sm = ctx.getSystemService(SensorManager::class.java)
        val rv = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return "This phone has no compass / rotation sensor."
        val sc = ad.bluetoothLeScanner ?: return "BLE scanner unavailable."
        java.util.Arrays.fill(mean, 0.0); java.util.Arrays.fill(cnt, 0)
        samples = 0; liveRssi = null; lastPacketAt = 0L; error = null; heading = Float.NaN; haveHeading = false
        targetAddress = address; targetName = name
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) { record(result.rssi) }
            override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { record(it.rssi) } }
            override fun onScanFailed(errorCode: Int) { error = "BLE scan failed (code $errorCode)"; onChange() }
        }
        cb = callback
        try {
            sc.startScan(listOf(ScanFilter.Builder().setDeviceAddress(address).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        } catch (e: SecurityException) { cb = null; return "Bluetooth scan permission is missing." }
        catch (e: IllegalArgumentException) { cb = null; return "Invalid device address." }
        sensorMgr = sm
        sm.registerListener(sensorListener, rv, SensorManager.SENSOR_DELAY_GAME)
        startedAt = SystemClock.uptimeMillis(); running = true
        handler.postDelayed(tick, 150)
        return null
    }

    /** Stops listening; the collected profile stays available (profile() / estimate()) until the next start(). */
    @SuppressLint("MissingPermission")
    fun stop() {
        handler.removeCallbacksAndMessages(null)
        cb?.let { c -> try { ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(c) } catch (_: Exception) {} }
        cb = null
        sensorMgr?.unregisterListener(sensorListener); sensorMgr = null
        running = false
    }

    fun sweepRemainingSec(): Int = if (running) max(0L, (startedAt + SWEEP_MS - SystemClock.uptimeMillis()) / 1000).toInt() else 0

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (SystemClock.uptimeMillis() - startedAt > MAX_MS) { stop(); error = "Stopped automatically after 3 minutes."; onChange(); return }
            onChange(); handler.postDelayed(this, 150)
        }
    }

    private fun record(rssi: Int) {
        lastPacketAt = SystemClock.uptimeMillis()
        liveRssi = liveRssi?.let { it * 0.7 + rssi * 0.3 } ?: rssi.toDouble()
        if (heading.isNaN()) return
        val b = (heading / 360f * BINS).toInt().coerceIn(0, BINS - 1)
        cnt[b]++
        mean[b] += (rssi - mean[b]) / min(cnt[b], 6)       // recent samples weigh more, so a moved target is re-learned
        samples++
    }

    /** Mean RSSI (dBm) per 10-degree heading bin, lightly smoothed over neighbours; NaN = not turned through yet. */
    fun profile(): DoubleArray {
        val out = DoubleArray(BINS) { Double.NaN }
        for (i in 0 until BINS) {
            if (cnt[i] == 0) continue
            var s = mean[i] * 2; var w = 2.0
            for (d in intArrayOf(-1, 1)) { val j = (i + d + BINS) % BINS; if (cnt[j] > 0) { s += mean[j]; w += 1.0 } }
            out[i] = s / w
        }
        return out
    }

    fun coverage(): Float = cnt.count { it > 0 } / BINS.toFloat()

    /** null until enough of the circle has been covered. */
    fun estimate(): Estimate? {
        val p = profile()
        val vals = p.filter { !it.isNaN() }.sorted()
        if (samples < 15 || vals.size < 8) return null
        val peak = vals.last(); val median = vals[vals.size / 2]
        var sx = 0.0; var sy = 0.0
        for (i in 0 until BINS) {
            val v = p[i]
            if (v.isNaN() || v < peak - 6.0) continue
            val w = 10.0.pow((v - peak) / 10.0)
            val a = Math.toRadians((i + 0.5) * 360.0 / BINS)
            sx += w * sin(a); sy += w * cos(a)
        }
        val bearing = ((Math.toDegrees(atan2(sx, sy)) + 360.0) % 360.0).toFloat()
        return Estimate(bearing, peak - median, coverage(), peak)
    }
}
