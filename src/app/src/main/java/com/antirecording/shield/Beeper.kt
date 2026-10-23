package com.antirecording.shield

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/** Geiger-style beeps for the direction finder: the stronger the live signal, the faster the beeps. */
class Beeper {
    private val tone: ToneGenerator? = try { ToneGenerator(AudioManager.STREAM_MUSIC, 70) } catch (_: Exception) { null }
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var rssi: Double? = null
    var enabled = false

    /** @param dbm smoothed RSSI, or null when no recent packets (then it stays silent). */
    fun update(dbm: Double?) { rssi = dbm }

    fun start() { if (running) return; running = true; handler.post(loop) }
    fun stop() { running = false; handler.removeCallbacks(loop) }
    fun release() { stop(); try { tone?.release() } catch (_: Exception) {} }

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            val r = rssi
            if (enabled && r != null) try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 40) } catch (_: Exception) {}
            // -40 dBm (very close) -> 120 ms, -90 dBm (far) -> 1100 ms
            val iv = if (r == null) 600L else (120 + ((-40.0 - r).coerceIn(0.0, 50.0) / 50.0) * 980).toLong()
            handler.postDelayed(this, iv)
        }
    }
}
