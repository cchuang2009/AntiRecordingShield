package com.antirecording.shield

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.*

/**
 * Heading-up dial for DirectionFinder: the TOP of the dial is where the phone points right now.
 * Bars = signal strength per compass bin (long/bright = strong, grey tick = not turned through yet).
 * The arrow points to the estimated direction of the device; it turns green when the phone points at it.
 */
class FinderView(context: Context) : View(context) {
    private var profile = DoubleArray(DirectionFinder.BINS) { Double.NaN }
    private var heading = 0f
    private var bearing: Float? = null
    private var centerText = ""
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val muted = Color.rgb(164, 187, 212)
    private val cyan = Color.rgb(57, 222, 244)
    private val green = Color.rgb(110, 232, 140)
    private val amber = Color.rgb(255, 180, 67)

    fun set(profile: DoubleArray, heading: Float, bearing: Float?, centerText: String) {
        this.profile = profile; this.heading = heading; this.bearing = bearing; this.centerText = centerText; invalidate()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.density * resources.configuration.fontScale
    private fun px(cx: Float, r: Float, deg: Double) = cx + (r * sin(Math.toRadians(deg))).toFloat()
    private fun py(cy: Float, r: Float, deg: Double) = cy - (r * cos(Math.toRadians(deg))).toFloat()
    private fun mix(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(), (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(), (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = min(width, height) / 2f - dp(22f)
        if (r <= 0) return
        paint.style = Paint.Style.FILL; paint.color = Color.rgb(8, 24, 45); canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1f); paint.color = Color.argb(90, 57, 222, 244)
        canvas.drawCircle(cx, cy, r, paint); canvas.drawCircle(cx, cy, r * 0.38f, paint)

        val vals = profile.filter { !it.isNaN() }
        val lo = vals.minOrNull() ?: 0.0; val hi = vals.maxOrNull() ?: 0.0
        val inner = r * 0.38f
        paint.strokeCap = Paint.Cap.ROUND
        for (i in 0 until DirectionFinder.BINS) {
            val abs = (i + 0.5) * 360.0 / DirectionFinder.BINS
            val ang = abs - heading
            val v = profile[i]
            if (v.isNaN()) {
                paint.strokeWidth = dp(1.5f); paint.color = Color.argb(70, 164, 187, 212)
                canvas.drawLine(px(cx, r * 0.93f, ang), py(cy, r * 0.93f, ang), px(cx, r * 0.99f, ang), py(cy, r * 0.99f, ang), paint)
            } else {
                val t = if (hi - lo < 1.0) 0.5f else ((v - lo) / (hi - lo)).toFloat()
                paint.strokeWidth = dp(5f); paint.color = mix(Color.rgb(60, 90, 140), if (t > 0.85f) amber else cyan, t)
                val len = inner + (r * 0.97f - inner) * (0.2f + 0.8f * t)
                canvas.drawLine(px(cx, inner, ang), py(cy, inner, ang), px(cx, len, ang), py(cy, len, ang), paint)
            }
        }
        // north marker
        text.textSize = sp(11f); text.color = muted
        canvas.drawText("N", px(cx, r + dp(12f), -heading.toDouble()), py(cy, r + dp(12f), -heading.toDouble()) + dp(4f), text)
        // fixed "phone points here" marker at the top
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE
        val top = Path().apply { moveTo(cx, cy - r - dp(3f)); lineTo(cx - dp(6f), cy - r - dp(13f)); lineTo(cx + dp(6f), cy - r - dp(13f)); close() }
        canvas.drawPath(top, paint)
        // estimated direction arrow
        val b = bearing
        if (b != null) {
            val d = ((b - heading + 540f) % 360f) - 180f           // -180..180, positive = to the right
            val aligned = abs(d) <= 15f
            val col = if (aligned) green else Color.WHITE
            val ang = (b - heading).toDouble()
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(3f); paint.color = col
            canvas.drawLine(cx, cy, px(cx, r * 0.9f, ang), py(cy, r * 0.9f, ang), paint)
            paint.style = Paint.Style.FILL
            val hx = px(cx, r * 0.97f, ang); val hy = py(cy, r * 0.97f, ang)
            val l1 = Path().apply {
                moveTo(hx, hy); lineTo(px(cx, r * 0.80f, ang - 7), py(cy, r * 0.80f, ang - 7)); lineTo(px(cx, r * 0.80f, ang + 7), py(cy, r * 0.80f, ang + 7)); close() }
            canvas.drawPath(l1, paint)
        }
        // centre readout
        paint.style = Paint.Style.FILL; paint.color = Color.rgb(8, 24, 45); canvas.drawCircle(cx, cy, dp(30f), paint)
        text.textSize = sp(11f); text.color = Color.WHITE
        canvas.drawText(centerText, cx, cy + dp(4f), text)
        if (vals.isEmpty()) { text.textSize = sp(12f); text.color = muted; canvas.drawText("Turn slowly on the spot", cx, cy + r * 0.62f, text) }
    }
}
