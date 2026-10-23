package com.antirecording.shield

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.*

/**
 * Animated radar for the Bluetooth / Wi-Fi scans.
 *  - rotating sweep with a fading trail while scanning; blips glow when the sweep passes them
 *  - a ping ring expands from a device the first time it is seen (nearest [maxPings] only)
 *  - radius = estimated distance on a log scale; blips glide to their new distance instead of jumping
 *  - blips fade out as they get stale (Blip.ageFrac 0 = just heard, 1 = about to be dropped)
 *  - at most [maxBlips] nearby + [maxFar] beyond-range blips are drawn; the rest are counted ("+N more")
 *  - labels never overlap: a label that would cover an already drawn one is skipped
 *  - the ANGLE of a blip is arbitrary (derived from the id): radio signal strength gives no direction.
 */
class RadarView(context: Context) : View(context) {
    /** type: 0 BLE, 1 Classic, 2 both, 3 Wi-Fi 2.4 GHz, 4 Wi-Fi 5/6 GHz, 5 SUSPICIOUS (red).  measured = real ranging (Wi-Fi RTT).  ageFrac: 0 fresh .. 1 stale. */
    class Blip(val id: String, val angleDeg: Float, val distM: Double, val type: Int, val measured: Boolean = false, val ageFrac: Float = 0f)
    /** false = Bluetooth legend, true = Wi-Fi legend */
    var wifiMode = false

    private val maxBlips = 40
    private val maxFar = 8
    private val maxLabels = 8
    private val maxPings = 10

    private val cyan = Color.rgb(57, 222, 244)
    private val amber = Color.rgb(255, 180, 67)
    private val violet = Color.rgb(190, 140, 255)
    private val green = Color.rgb(110, 232, 140)
    private val yellow = Color.rgb(240, 226, 104)
    private val muted = Color.rgb(164, 187, 212)
    private val red = Color.rgb(255, 77, 109)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private var all: List<Blip> = emptyList()
    private var blips: List<Blip> = emptyList()
    private var hiddenCount = 0
    private val firstSeen = HashMap<String, Long>()
    private val shown = HashMap<String, Double>()      // distance currently drawn (glides toward Blip.distM)
    private var lastFrame = 0L
    private var scanning = false
    private var sweep = 0f
    private var anim: ValueAnimator? = null
    private var sweepShader: Shader? = null
    private var maxM = 20.0
    private var ringsM = ringsFor(20.0)
    private fun ringsFor(m: Double) = doubleArrayOf(0.07, 0.2, 0.47, 1.0).map { Math.round(it * m).toDouble().coerceAtLeast(1.0) }.toDoubleArray()

    /** Radar edge in metres (15 / 20 / 40 / 50). Only the plot scale changes, not what the radio can hear. */
    fun setRange(m: Double) { maxM = m; ringsM = ringsFor(m); applyBlips(); invalidate() }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.density * resources.configuration.fontScale
    private fun frac(d: Double) = (ln(1.0 + min(d, maxM)) / ln(1.0 + maxM)).toFloat()

    fun setBlips(list: List<Blip>) {
        val now = SystemClock.uptimeMillis()
        all = list
        for (b in list) if (!firstSeen.containsKey(b.id)) firstSeen[b.id] = now
        val ids = list.map { it.id }.toSet()
        firstSeen.keys.retainAll(ids); shown.keys.retainAll(ids)
        applyBlips(); invalidate()
    }

    private fun applyBlips() {
        val sorted = all.sortedWith(compareBy({ it.type != 5 }, { it.distM }))      // suspicious (type 5) first: never dropped by the caps
        val near = sorted.filter { it.distM <= maxM }.take(maxBlips)
        val far = sorted.filter { it.distM > maxM }.take(maxFar)
        blips = near + far
        hiddenCount = all.size - blips.size
    }

    fun setScanning(on: Boolean) {
        scanning = on
        if (on) {
            lastFrame = 0L
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
        val dt = if (lastFrame == 0L) 16L else (now - lastFrame).coerceIn(1L, 100L); lastFrame = now
        val k = 1.0 - exp(-dt / 250.0)                       // ~250 ms glide toward the newest distance
        val sorted = blips.sortedWith(compareBy({ it.type != 5 }, { it.distM }))   // suspicious first so they get a label
        val used = ArrayList<RectF>(); var labels = 0
        sorted.forEachIndexed { idx, b ->
            val cur = shown[b.id] ?: b.distM
            val dd = if (scanning) cur + (b.distM - cur) * k else b.distM
            shown[b.id] = dd
            val rr = r * frac(dd); val a = Math.toRadians(b.angleDeg.toDouble())
            val x = cx + (rr * cos(a)).toFloat(); val y = cy + (rr * sin(a)).toFloat()
            val col = when (b.type) { 0 -> cyan; 1 -> amber; 2 -> violet; 3 -> green; 5 -> red; else -> yellow }
            val fade = 1f - 0.8f * b.ageFrac.coerceIn(0f, 1f)
            val glow = if (scanning) { val since = ((sweep - b.angleDeg) % 360f + 360f) % 360f; (1f - since / 300f).coerceIn(0f, 1f) } else 0.6f
            val far = b.distM > maxM
            paint.style = Paint.Style.FILL
            paint.color = Color.argb((55 * glow * fade).toInt().coerceIn(0, 255), Color.red(col), Color.green(col), Color.blue(col))
            canvas.drawCircle(x, y, dp(7f) * (0.6f + 0.7f * glow), paint)
            val core = if (far) 110f else 130f + 125f * glow
            paint.color = Color.argb((core * fade).toInt().coerceIn(0, 255), Color.red(col), Color.green(col), Color.blue(col)); canvas.drawCircle(x, y, dp(4f), paint)
            val age = now - (firstSeen[b.id] ?: now)
            if (scanning && age < 1600 && idx < maxPings) {   // ping ring on first sight
                val t = age / 1600f
                paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1.5f); paint.color = Color.argb(((1f - t) * 200).toInt(), Color.red(col), Color.green(col), Color.blue(col))
                canvas.drawCircle(x, y, dp(6f) + t * dp(30f), paint)
            }
            if (b.type == 5) {      // pulsing red ring around a suspicious device
                val ph = (now % 1200L) / 1200f
                paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1.8f); paint.color = Color.argb(((1f - ph) * 230).toInt(), 255, 77, 109)
                canvas.drawCircle(x, y, dp(8f) + ph * dp(10f), paint)
            }
            if (b.measured) { paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1.6f); paint.color = Color.WHITE; canvas.drawCircle(x, y, dp(8f), paint) }
            if (labels < maxLabels && fade > 0.35f) {
                val label = (if (b.type == 5) "⚠ " else "") + (if (far) ">${maxM.toInt()} m" else (if (b.measured) "" else "~") + fmt(dd) + (if (b.measured) " ✓" else ""))
                text.textSize = sp(9f)
                val w = text.measureText(label) / 2f + dp(2f)
                val box = RectF(x - w, y - dp(11f) - sp(9f), x + w, y - dp(11f) + dp(2f))
                if (used.none { RectF.intersects(it, box) }) {
                    used.add(box); labels++
                    text.color = Color.argb((230 * fade).toInt(), 241, 247, 255)
                    canvas.drawText(label, x, y - dp(11f), text)
                }
            }
        }
        // centre = this phone
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE; canvas.drawCircle(cx, cy, dp(4f), paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1.5f); paint.color = Color.argb(160, 255, 255, 255); canvas.drawCircle(cx, cy, dp(8f), paint)
        if (hiddenCount > 0) { text.textSize = sp(10f); text.color = muted; canvas.drawText(L.t("+$hiddenCount more not drawn", "另有 $hiddenCount 個未繪出"), cx, dp(12f), text) }
        // legend
        text.textSize = sp(10f)
        val ly = height - dp(4f)
        if (wifiMode) {
            text.color = green; canvas.drawText("● 2.4 GHz", cx - dp(111f), ly, text)
            text.color = yellow; canvas.drawText("● 5/6 GHz", cx - dp(37f), ly, text)
            text.color = Color.WHITE; canvas.drawText("◎ ✓ RTT", cx + dp(37f), ly, text)
            text.color = red; canvas.drawText("● ${L.t("Suspect", "可疑")}", cx + dp(111f), ly, text)
        } else {
            text.color = cyan; canvas.drawText("● BLE", cx - dp(111f), ly, text)
            text.color = amber; canvas.drawText("● ${L.t("Classic", "傳統")}", cx - dp(37f), ly, text)
            text.color = violet; canvas.drawText("● ${L.t("Both", "兩者")}", cx + dp(37f), ly, text)
            text.color = red; canvas.drawText("● ${L.t("Suspect", "可疑")}", cx + dp(111f), ly, text)
        }
        if (!scanning && blips.isEmpty()) { text.textSize = sp(12f); text.color = muted; canvas.drawText(L.t("Tap Start scan", "點「開始掃描」"), cx, cy + r * 0.45f, text) }
    }

    private fun fmt(d: Double) = if (d < 10) String.format(java.util.Locale.US, "%.1f m", d) else String.format(java.util.Locale.US, "%d m", d.roundToInt())
}
