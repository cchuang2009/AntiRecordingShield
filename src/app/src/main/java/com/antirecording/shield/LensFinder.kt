package com.antirecording.shield

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.min

/**
 * Lens / IR spot helper (a visual aid, NOT an automatic detector).
 *  FLASH mode (back camera): the torch blinks; a camera lens reflects it straight back, so a small bright glint that appears only while the
 *    torch is on is marked with a red circle. Shiny things (glass, screws, jewellery, mirrors) glint too, so every circle must be checked by eye.
 *  IR mode (front camera, no flash): phone cameras can often see infrared LEDs of night-vision cameras as bright dots in a dark room;
 *    small isolated very bright dots are circled. Some phones filter IR out, so a clean result proves nothing.
 * Frames are analysed in memory only (a small grayscale grid); nothing is saved or sent. Needs the CAMERA permission.
 */
class LensFinder(private val act: Activity) {
    companion object { const val FLASH = 0; const val IR = 1 }

    private val gw = 128
    private val gh = 96
    private class Spot(val fx: Float, val fy: Float, val strength: Int, var t: Long)

    private var dialog: Dialog? = null
    private var cam: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var bg: Handler? = null
    private var builder: CaptureRequest.Builder? = null
    private var mode = FLASH
    private var torch = false
    private var lastToggle = 0L
    private var startedAt = 0L
    private var sensorOrientation = 90
    private var front = false
    private var offGrid: IntArray? = null
    private val spots = ArrayList<Spot>()
    private lateinit var view: LensView
    private lateinit var info: TextView

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()

    /** @return an error message, or null when the helper is showing. */
    @SuppressLint("MissingPermission")
    fun show(mode: Int): String? {
        this.mode = mode
        val cm = act.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val wantFront = mode == IR
        val facing = if (wantFront) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = (try { cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing } } catch (e: CameraAccessException) { null })
            ?: return L.t("No suitable camera found.", "找不到合適的鏡頭。")
        val ch = cm.getCameraCharacteristics(id)
        if (mode == FLASH && ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) return L.t("This camera has no flash.", "此鏡頭沒有閃光燈。")
        sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        front = wantFront
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return L.t("This camera is not supported.", "不支援此鏡頭。")
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888) ?: return L.t("This camera is not supported.", "不支援此鏡頭。")
        val size = sizes.filter { it.width >= 480 && it.height >= 360 }.minByOrNull { it.width.toLong() * it.height }
            ?: sizes.maxByOrNull { it.width.toLong() * it.height } ?: return L.t("This camera is not supported.", "不支援此鏡頭。")
        thread = HandlerThread("lens-finder").also { it.start() }
        bg = Handler(thread!!.looper)
        reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).also { it.setOnImageAvailableListener({ r -> analyze(r) }, bg) }
        buildDialog()
        startedAt = SystemClock.uptimeMillis()
        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { cam = c; startSession(c) }
                override fun onDisconnected(c: CameraDevice) { c.close(); cam = null }
                override fun onError(c: CameraDevice, e: Int) { c.close(); cam = null; say(L.t("Camera error $e", "鏡頭錯誤 $e")) }
            }, bg)
        } catch (e: Exception) { release(); return e.message ?: "camera error" }
        return null
    }

    private fun say(t: String) { act.runOnUiThread { if (::info.isInitialized) info.text = t } }

    private fun buildDialog() {
        val d = Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(act)
        view = LensView(act)
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        info = TextView(act).apply {
            setTextColor(Color.WHITE); textSize = 13f; setBackgroundColor(0xAA000000.toInt()); setPadding(dp(12), dp(40), dp(12), dp(10))
            text = if (mode == FLASH)
                L.t("Slowly sweep the room with the back camera. A red circle = a lens-like glint that appears only while the flash is on. Shiny objects glint too: check every circle by eye.",
                    "請用後鏡頭慢慢掃視房間。紅圈＝只在閃光燈亮時才出現的鏡頭狀反光。閃亮物品也會反光，請逐一用肉眼確認。")
            else
                L.t("Dim the room and move the FRONT camera around. Small bright isolated dots may be infrared LEDs of night-vision cameras. Some phones filter IR, so a clean result proves nothing.",
                    "請把房間調暗，用前鏡頭四處照。小而亮且孤立的亮點可能是夜視攝影機的紅外線燈。有些手機會濾掉紅外線，所以沒看到不代表沒有。")
        }
        root.addView(info, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        val close = Button(act).apply { text = L.t("Close", "關閉"); setOnClickListener { d.dismiss() } }
        root.addView(close, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(40) })
        d.setContentView(root)
        d.setOnDismissListener { release() }
        d.show(); dialog = d
    }

    @Suppress("DEPRECATION")
    private fun startSession(c: CameraDevice) {
        val r = reader ?: return
        try {
            val b = c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(r.surface)
            builder = b
            c.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { session = s; lastToggle = SystemClock.uptimeMillis(); apply() }
                override fun onConfigureFailed(s: CameraCaptureSession) { say(L.t("The camera could not start.", "鏡頭無法啟動。")) }
            }, bg)
        } catch (e: Exception) { say(e.message ?: "camera error") }
    }

    private fun apply() {
        val b = builder ?: return
        val s = session ?: return
        b.set(CaptureRequest.FLASH_MODE, if (mode == FLASH && torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        b.set(CaptureRequest.CONTROL_AE_LOCK, SystemClock.uptimeMillis() - startedAt > 1200)     // fixed exposure after warm-up so the glint stands out
        try { s.setRepeatingRequest(b.build(), null, bg) } catch (_: Exception) {}
    }

    /** Downsample the Y plane to a gw x gh grid (block maximum), then rotate/mirror it to an upright picture. */
    private fun analyze(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return
        try {
            val now = SystemClock.uptimeMillis()
            val plane = img.planes[0]; val buf = plane.buffer; val rs = plane.rowStride; val ps = plane.pixelStride
            val w = img.width; val h = img.height
            val bw = w / gw; val bh = h / gh
            val g = IntArray(gw * gh)
            for (gy in 0 until gh) for (gx in 0 until gw) {
                var m = 0; var yy = gy * bh; val ye = yy + bh
                while (yy < ye) {
                    var xx = gx * bw; val xe = xx + bw
                    while (xx < xe) { val v = buf.get(yy * rs + xx * ps).toInt() and 0xFF; if (v > m) m = v; xx += 2 }
                    yy += 2
                }
                g[gy * gw + gx] = m
            }
            val (up, uw, uh) = orient(g)
            if (mode == FLASH) {
                val since = now - lastToggle
                if (since >= 550) { torch = !torch; lastToggle = now; apply() }
                else if (since >= 320) {
                    if (torch) { val off = offGrid; if (off != null) detectFlash(up, off, uw, uh, now) } else offGrid = up
                }
            } else detectIr(up, uw, uh, now)
            spots.removeAll { now - it.t > 2200 }
            val bmp = Bitmap.createBitmap(uw, uh, Bitmap.Config.ARGB_8888)
            val px = IntArray(uw * uh) { val v = up[it]; Color.rgb(v, v, v) }
            bmp.setPixels(px, 0, uw, 0, 0, uw, uh)
            val snap = spots.map { floatArrayOf(it.fx, it.fy, ((now - it.t) / 2200f).coerceIn(0f, 1f)) }
            view.update(bmp, snap)
        } catch (_: Exception) {
        } finally { try { img.close() } catch (_: Exception) {} }
    }

    private fun orient(g: IntArray): Triple<IntArray, Int, Int> {
        val (nw, nh) = if (sensorOrientation == 90 || sensorOrientation == 270) Pair(gh, gw) else Pair(gw, gh)
        val out = IntArray(nw * nh)
        for (y in 0 until gh) for (x in 0 until gw) {
            var nx: Int; val ny: Int
            when (sensorOrientation) {
                90 -> { nx = gh - 1 - y; ny = x }
                270 -> { nx = y; ny = gw - 1 - x }
                180 -> { nx = gw - 1 - x; ny = gh - 1 - y }
                else -> { nx = x; ny = y }
            }
            if (front) nx = nw - 1 - nx
            out[ny * nw + nx] = g[y * gw + x]
        }
        return Triple(out, nw, nh)
    }

    /** Cells that are bright now but were dark with the torch off, grouped into small blobs. */
    private fun detectFlash(on: IntArray, off: IntArray, w: Int, h: Int, now: Long) {
        if (on.size != off.size) return
        val cand = BooleanArray(w * h) { on[it] >= 190 && on[it] - off[it] >= 60 }
        blobs(cand, w, h, 10) { cx, cy, n -> addSpot(cx / w, cy / h, 60 + n, now) }
    }

    /** Small, very bright cells whose surroundings (3 cells away) are much darker. */
    private fun detectIr(g: IntArray, w: Int, h: Int, now: Long) {
        val cand = BooleanArray(w * h)
        for (y in 3 until h - 3) for (x in 3 until w - 3) {
            if (g[y * w + x] < 245) continue
            val ring = (g[(y - 3) * w + x] + g[(y + 3) * w + x] + g[y * w + x - 3] + g[y * w + x + 3]) / 4
            if (ring < 140) cand[y * w + x] = true
        }
        blobs(cand, w, h, 12) { cx, cy, n -> addSpot(cx / w, cy / h, 60 + n, now) }
    }

    private fun addSpot(fx: Float, fy: Float, s: Int, now: Long) {
        val near = spots.firstOrNull { Math.abs(it.fx - fx) < 0.04f && Math.abs(it.fy - fy) < 0.04f }
        if (near != null) near.t = now else if (spots.size < 12) spots.add(Spot(fx, fy, s, now))
    }

    /** 4-neighbour connected components of [cand]; calls [hit] (centre x, centre y, size) for components of at most [maxCells] cells. */
    private fun blobs(cand: BooleanArray, w: Int, h: Int, maxCells: Int, hit: (Float, Float, Int) -> Unit) {
        val seen = BooleanArray(cand.size)
        val stack = IntArray(cand.size)
        for (i in cand.indices) {
            if (!cand[i] || seen[i]) continue
            var sp = 0; stack[sp++] = i; seen[i] = true
            var n = 0; var sx = 0f; var sy = 0f
            while (sp > 0) {
                val c = stack[--sp]; n++; sx += c % w; sy += c / w
                val x = c % w; val y = c / w
                if (x > 0 && cand[c - 1] && !seen[c - 1]) { seen[c - 1] = true; stack[sp++] = c - 1 }
                if (x < w - 1 && cand[c + 1] && !seen[c + 1]) { seen[c + 1] = true; stack[sp++] = c + 1 }
                if (y > 0 && cand[c - w] && !seen[c - w]) { seen[c - w] = true; stack[sp++] = c - w }
                if (y < h - 1 && cand[c + w] && !seen[c + w]) { seen[c + w] = true; stack[sp++] = c + w }
            }
            if (n <= maxCells) hit(sx / n, sy / n, n)
        }
    }

    private fun release() {
        try { session?.close() } catch (_: Exception) {}; session = null
        try { cam?.close() } catch (_: Exception) {}; cam = null
        try { reader?.close() } catch (_: Exception) {}; reader = null
        thread?.quitSafely(); thread = null; bg = null
        dialog = null
    }

    /** Draws the grayscale frame (aspect preserved) and the red circles. */
    private class LensView(context: Context) : View(context) {
        private var bmp: Bitmap? = null
        private var spots: List<FloatArray> = emptyList()
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun update(b: Bitmap, s: List<FloatArray>) { bmp = b; spots = s; postInvalidate() }
        override fun onDraw(c: Canvas) {
            c.drawColor(Color.BLACK)
            val b = bmp ?: return
            val sc = min(width / b.width.toFloat(), height / b.height.toFloat())
            val dw = b.width * sc; val dh = b.height * sc
            val l = (width - dw) / 2f; val t = (height - dh) / 2f
            p.isFilterBitmap = true
            c.drawBitmap(b, null, RectF(l, t, l + dw, t + dh), p)
            val rad = resources.displayMetrics.density * 22f
            for (s in spots) {
                p.style = Paint.Style.STROKE; p.strokeWidth = resources.displayMetrics.density * 3f
                p.color = Color.argb((255 * (1f - s[2] * 0.7f)).toInt(), 255, 60, 90)
                c.drawCircle(l + s[0] * dw, t + s[1] * dh, rad, p)
            }
        }
    }
}
