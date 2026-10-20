package com.antirecording.shield

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.*

/**
 * Animated radar for the Bluetooth scan.
 *  - rotating sweep with a fading trail while scanning; blips glow when the sweep passes them
 *  - a ping ring expands from a device the first time it is seen
 *  - radius = estimated distance on a log scale (rings: 1 m, 3 m, 7 m, 15 m)
 *  - the ANGLE of a blip is arbitrary (derived from the address): Bluetooth RSSI gives no direction.
 */
class RadarView(context: Context) : View(context) {
    /** type: 0 BLE, 1 Classic, 2 both, 3 Wi-Fi 2.4 GHz, 4 Wi-Fi 5/6 GHz.  measured = real ranging (Wi-Fi RTT) instead of an RSSI estimate. */
    class Blip(val id: String, val angleDeg: Float, val distM: Double, val type: Int, val measured: Boolean = false)
    /** false = Bluetooth legend, true = Wi-Fi legend */
    var wifiMode = false

    private val cyan = Color.rgb(57, 222, 244)
    private val amber = Color.rgb(255, 180, 67)
    private val violet = Color.rgb(190, 140, 255)
    private val green = Color.rgb(110, 232, 140)
    private val yellow = Color.rgb(240, 226, 104)
    private val muted = Color.rgb(164, 187, 212)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private var blips: List<Blip> = emptyList()
    private val firstSeen = HashMap<String, Long>()
    private var scanning = false
    private var sweep = 0f
    private var anim: ValueAnimator? = null
    private var sweepShader: Shader? = null
    private var maxM = 20.0
    private var ringsM = ringsFor(20.0)
    private fun ringsFor(m: Double) = doubleArrayOf(0.07, 0.2, 0.47, 1.0).map { Math.round(it * m).toDouble().coerceAtLeast(1.0) }.toDoubleArray()

    /** Radar edge in metres (15 / 20 / 40 / 50). Only the plot scale changes, not what the radio can hear. */
    fun setRange(m: Double) { maxM = m; ringsM = ringsFor(m); invalidate() }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.density * resources.configuration.fontScale
    private fun frac(d: Double) = (ln(1.0 + min(d, maxM)) / ln(1.0 + maxM)).toFloat()

    fun setBlips(list: List<Blip>) {
        val now = SystemClock.uptimeMillis()
        for (b in list) if (!firstSeen.containsKey(b.id)) firstSeen[b.id] = now
        firstSeen.keys.retainAll(list.map { it.id }.toSet())
        blips = list; invalidate()
    }

    fun setScanning(on: Boolean) {
        scanning = on
        if (on) {
            if (anim?.isRunning != true) anim = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 3000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
                addUpdateListener { sweep = it.animatedValue as Float; invalidate() }; start()
            }
        } else { anim?.cancel(); anim = null }
        invalidate()
    }

    override fun onDetachedFromWindow() { anim?.cancel(); anim = null; super.onDetachedFromWindow() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val cx = w / 2f; val cy = h / 2f
        // bright edge at 0° (the sweep line), 60° fading trail behind it
        sweepShader = SweepGradient(cx, cy, intArrayOf(Color.argb(0, 57, 222, 244), Color.argb(0, 57, 222, 244), Color.argb(150, 57, 222, 244)), floatArrayOf(0f, 0.83f, 1f))
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = min(width, height) / 2f - dp(20f)
        if (r <= 0) return
        // disc + rings + cross-hair
        paint.style = Paint.Style.FILL; paint.shader = null; paint.color = Color.rgb(8, 24, 45); canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1f)
        for (m in ringsM) { paint.color = Color.argb(if (m == maxM) 150 else 80, 57, 222, 244); canvas.drawCircle(cx, cy, r * frac(m), paint) }
        paint.color = Color.argb(45, 57, 222, 244)
        canvas.drawLine(cx - r, cy, cx + r, cy, paint); canvas.drawLine(cx, cy - r, cx, cy + r, paint)
        text.textSize = sp(9f); text.color = muted
        for (m in ringsM) canvas.drawText("${m.toInt()} m", cx + r * frac(m) * 0.7071f + dp(8f), cy - r * frac(m) * 0.7071f - dp(2f), text)

        // sweep
        if (scanning) {
            canvas.save(); canvas.rotate(sweep, cx, cy)
            paint.style = Paint.Style.FILL; paint.shader = sweepShader; canvas.drawCircle(cx, cy, r, paint); paint.shader = null
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1.6f); paint.color = Color.argb(220, 57, 222, 244); canvas.drawLine(cx, cy, cx + r, cy, paint)
            canvas.restore()
        }

        // blips (nearest first so labels of the closest devices win)
        val now = SystemClock.uptimeMillis()
        val sorted = blips.sortedBy { it.distM }
        sorted.forEachIndexed { idx, b ->
            val rr = r * frac(b.distM); val a = Math.toRadians(b.angleDeg.toDouble())
            val x = cx + (rr * cos(a)).toFloat(); val y = cy + (rr * sin(a)).toFloat()
            val col = when (b.type) { 0 -> cyan; 1 -> amber; 2 -> violet; 3 -> green; else -> yellow }
            val glow = if (scanning) { val since = ((sweep - b.angleDeg) % 360f + 360f) % 360f; (1f - since / 300f).coerceIn(0f, 1f) } else 0.6f
            val far = b.distM > maxM
            paint.style = Paint.Style.FILL
            paint.color = Color.argb((90 * glow).toInt(), Color.red(col), Color.green(col), Color.blue(col)); canvas.drawCircle(x, y, dp(11f) * (0.6f + glow), paint)
            paint.color = Color.argb(if (far) 120 else (130 + 125 * glow).toInt().coerceAtMost(255), Color.red(col), Color.green(col), Color.blue(col)); canvas.drawCircle(x, y, dp(4.5f), paint)
            val age = now - (firstSeen[b.id] ?: now)
            if (scanning && age < 1600) {   // ping ring on first sight
                val t = age / 1600f
                paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1.5f); paint.color = Color.argb(((1f - t) * 200).toInt(), Color.red(col), Color.green(col), Color.blue(col))
                canvas.drawCircle(x, y, dp(6f) + t * dp(30f), paint)
            }
            if (b.measured) { paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1.6f); paint.color = Color.WHITE; canvas.drawCircle(x, y, dp(8f), paint) }
            if (idx < 8) {
                text.textSize = sp(9f); text.color = Color.argb(230, 241, 247, 255)
                canvas.drawText(if (far) ">${maxM.toInt()} m" else (if (b.measured) "" else "~") + fmt(b.distM) + (if (b.measured) " ✓" else ""), x, y - dp(11f), text)
            }
        }
        // centre = this phone
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE; canvas.drawCircle(cx, cy, dp(4f), paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1.5f); paint.color = Color.argb(160, 255, 255, 255); canvas.drawCircle(cx, cy, dp(8f), paint)
        // legend
        text.textSize = sp(10f)
        val ly = height - dp(4f)
        if (wifiMode) {
            text.color = green; canvas.drawText("● 2.4 GHz", cx - dp(74f), ly, text)
            text.color = yellow; canvas.drawText("● 5/6 GHz", cx, ly, text)
            text.color = Color.WHITE; canvas.drawText("◎ ✓ RTT", cx + dp(74f), ly, text)
        } else {
            text.color = cyan; canvas.drawText("● BLE", cx - dp(70f), ly, text)
            text.color = amber; canvas.drawText("● Classic", cx, ly, text)
            text.color = violet; canvas.drawText("● Both", cx + dp(70f), ly, text)
        }
        if (!scanning && blips.isEmpty()) { text.textSize = sp(12f); text.color = muted; canvas.drawText("Tap Start scan", cx, cy + r * 0.45f, text) }
    }

    private fun fmt(d: Double) = if (d < 10) String.format(java.util.Locale.US, "%.1f m", d) else String.format(java.util.Locale.US, "%d m", d.roundToInt())
}
