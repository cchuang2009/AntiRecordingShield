package com.example.antirecording

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.*
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.view.View
import android.widget.*
import android.graphics.Color
import androidx.activity.ComponentActivity
import kotlin.math.*

/**
 * Anti-Recording Shield 0.3.0 — experimental acoustic privacy prototype.
 * Important: microphone analysis cannot determine whether another phone is recording.
 * The score below is an acoustic activity indicator, not a recording-device detector.
 */
class MainActivity : ComponentActivity() {
    @Volatile private var running = false
    @Volatile private var maskingEnabled = true
    private var worker: Thread? = null
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private lateinit var status: TextView
    private lateinit var score: TextView
    private lateinit var speechEnergy: ProgressBar
    private lateinit var maskEnergy: ProgressBar
    private lateinit var intensity: SeekBar
    private lateinit var bandText: TextView
    private lateinit var spectrum: SpectrumView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var maskSwitch: Switch
    private lateinit var alertDetails: TextView
    @Volatile private var alertActive = false
    private var lastAlertAtMs = 0L
    private val sampleRate = 44100
    private val fftSize = 1024
    private val navy = Color.rgb(7, 20, 39)
    private val card = Color.rgb(15, 36, 61)
    private val blue = Color.rgb(40, 151, 255)
    private val cyan = Color.rgb(57, 222, 244)
    private val white = Color.rgb(241, 247, 255)
    private val muted = Color.rgb(164, 187, 212)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = navy
        window.navigationBarColor = navy
        window.decorView.systemUiVisibility = 0
        buildUi()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Float, color: Int = white, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    private fun cardLayout(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(card); cornerRadius = dp(20).toFloat()
            setStroke(dp(1), Color.rgb(34, 67, 99))
        }
    }
    private fun addGap(parent: LinearLayout, h: Int = 12) { parent.addView(View(this), LinearLayout.LayoutParams(1, dp(h))) }
    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(navy); isFillViewport = true }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(24)) }
        scroll.addView(root)
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val logo = ImageView(this).apply { setImageResource(R.mipmap.ic_launcher); contentDescription = "Anti-Recording Shield icon" }
        header.addView(logo, LinearLayout.LayoutParams(dp(48), dp(48)))
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        titleBox.addView(label("Anti-Recording Shield", 20f, white, true))
        titleBox.addView(label("ACOUSTIC PRIVACY  •  v0.4", 10f, cyan, true))
        header.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)
        addGap(root, 18)

        val statusCard = cardLayout()
        statusCard.addView(label("LIVE ACOUSTIC MONITOR", 12f, muted, true))
        addGap(statusCard, 10)
        val statusPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(12), dp(16), dp(12), dp(16))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.rgb(55, 31, 49)); cornerRadius = dp(16).toFloat(); setStroke(dp(1), Color.rgb(178, 62, 91)) }
        }
        status = label("●  MONITORING OFF", 18f, Color.rgb(255, 111, 132), true)
        statusPanel.addView(status)
        score = label("Acoustic activity score  —", 13f, white)
        statusPanel.addView(score)
        statusCard.addView(statusPanel)
        addGap(statusCard, 8)
        alertDetails = label("聲學監測待命。此功能只能提示持續的語音型聲音，不能確認附近裝置是否正在錄音。", 12f, muted)
        statusCard.addView(alertDetails)
        addGap(statusCard)
        statusCard.addView(label("LIVE SPECTRUM", 12f, muted, true))
        spectrum = SpectrumView()
        statusCard.addView(spectrum, LinearLayout.LayoutParams(-1, dp(92)))
        statusCard.addView(label("Illustrative acoustic spectrum • updates while monitoring", 11f, muted))
        root.addView(statusCard)
        addGap(root, 14)

        val metrics = cardLayout()
        metrics.addView(label("SIGNAL ANALYSIS", 12f, muted, true))
        addGap(metrics, 12)
        metrics.addView(label("Speech-band energy", 14f, white, true))
        speechEnergy = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(255, 76, 105)); progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(37, 60, 83)) }
        metrics.addView(speechEnergy, LinearLayout.LayoutParams(-1, dp(8)))
        addGap(metrics, 12)
        metrics.addView(label("Masking output level", 14f, white, true))
        maskEnergy = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progressTintList = android.content.res.ColorStateList.valueOf(blue); progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(37, 60, 83)) }
        metrics.addView(maskEnergy, LinearLayout.LayoutParams(-1, dp(8)))
        addGap(metrics, 10)
        bandText = label("Speech band: —     Peak: — Hz", 12f, muted)
        metrics.addView(bandText)
        root.addView(metrics)
        addGap(root, 14)

        val control = cardLayout()
        control.addView(label("ADAPTIVE MASKING", 12f, muted, true))
        maskSwitch = Switch(this).apply { text = "Enable low-level masking"; textSize = 15f; setTextColor(white); isChecked = true; buttonTintList = android.content.res.ColorStateList.valueOf(cyan); setOnCheckedChangeListener { _, checked -> maskingEnabled = checked } }
        control.addView(maskSwitch)
        addGap(control, 6)
        control.addView(label("Maximum intensity (start low)", 13f, white))
        intensity = SeekBar(this).apply { max = 30; progress = 6; progressTintList = android.content.res.ColorStateList.valueOf(blue); thumbTintList = android.content.res.ColorStateList.valueOf(cyan) }
        control.addView(intensity)
        addGap(control, 10)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        startButton = Button(this).apply { text = "▶  START"; setTextColor(Color.WHITE); background = android.graphics.drawable.GradientDrawable().apply { setColor(blue); cornerRadius = dp(14).toFloat() }; setOnClickListener { startShield() } }
        stopButton = Button(this).apply { text = "■  STOP"; setTextColor(white); background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.rgb(39, 58, 81)); cornerRadius = dp(14).toFloat() }; isEnabled = false; setOnClickListener { stopShield() } }
        buttons.addView(startButton, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) })
        buttons.addView(stopButton, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(6) })
        control.addView(buttons)
        root.addView(control)
        addGap(root, 14)
        val disclaimer = cardLayout()
        disclaimer.addView(label("PRIVACY & SAFETY NOTE", 12f, cyan, true))
        addGap(disclaimer, 6)
        disclaimer.addView(label("This app analyzes sound captured by this phone's microphone and can play low-level masking audio. It cannot detect whether a nearby phone is recording, and cannot guarantee prevention of recording or speech recognition. Use only at a comfortable volume and with the consent of people present.", 12f, muted))
        root.addView(disclaimer)
        setContentView(scroll)
    }

    private fun startShield() {
        if (running) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10); return
        }
        val minRec = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minRec <= 0) { status.text = "●  MICROPHONE UNAVAILABLE"; return }
        val recBuffer = max(minRec, fftSize * 2)
        recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recBuffer)
        val minOut = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minOut <= 0) { recorder?.release(); recorder = null; status.text = "●  AUDIO OUTPUT UNAVAILABLE"; return }
        track = AudioTrack(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(), AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(), max(minOut, fftSize * 2), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
        try { recorder!!.startRecording(); track!!.play() } catch (e: Exception) {
            status.text = "●  AUDIO START FAILED"; recorder?.release(); recorder = null; track?.release(); track = null; return
        }
        running = true; status.text = "●  MONITORING ACTIVE"; status.setTextColor(cyan)
        startButton.isEnabled = false; stopButton.isEnabled = true
        worker = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val input = ShortArray(fftSize); val output = ShortArray(fftSize)
            val real = DoubleArray(fftSize); val imag = DoubleArray(fftSize)
            var phase = 0.0; var smoothSpeech = 0.0; var suspiciousFrames = 0; var quietFrames = 0
            while (running) {
                val n = recorder?.read(input, 0, input.size, AudioRecord.READ_BLOCKING) ?: 0
                if (n <= 0) continue
                for (i in 0 until fftSize) { val x = if (i < n) input[i] / 32768.0 else 0.0; val w = 0.5 - 0.5 * cos(2.0 * PI * i / (fftSize - 1)); real[i] = x * w; imag[i] = 0.0 }
                fft(real, imag)
                var speechPower = 0.0; var totalPower = 1e-9; var peakBin = 0; var peakPower = 0.0
                val bins = IntArray(24)
                for (k in 1 until fftSize / 2) {
                    val p = real[k] * real[k] + imag[k] * imag[k]; totalPower += p
                    val hz = k.toDouble() * sampleRate / fftSize
                    if (hz in 1000.0..4000.0) speechPower += p
                    if (hz in 1000.0..6000.0 && p > peakPower) { peakPower = p; peakBin = k }
                    if (k < fftSize / 2) { val idx = ((ln(k.toDouble() + 1) / ln((fftSize / 2).toDouble())) * bins.size).toInt().coerceIn(0, bins.lastIndex); bins[idx] = max(bins[idx], (10 * ln(p + 1e-10) + 100).toInt().coerceIn(2, 100)) }
                }
                val ratio = (speechPower / totalPower).coerceIn(0.0, 1.0)
                var rms = 0.0
                for (i in 0 until n.coerceAtMost(input.size)) { val v = input[i] / 32768.0; rms += v * v }
                rms = sqrt(rms / n.coerceAtLeast(1))
                smoothSpeech = 0.85 * smoothSpeech + 0.15 * ratio

                // Heuristic only: sustained speech-band energy + sufficient input level.
                // This detects a speech-like acoustic environment, NOT another device recording.
                val speechLikeFrame = smoothSpeech >= 0.22 && rms >= 0.012
                if (speechLikeFrame) { suspiciousFrames++; quietFrames = 0 }
                else { suspiciousFrames = max(0, suspiciousFrames - 1); quietFrames++ }
                val shouldAlert = suspiciousFrames >= 12 && !alertActive &&
                    (System.currentTimeMillis() - lastAlertAtMs > 15000L)
                if (shouldAlert) {
                    alertActive = true
                    lastAlertAtMs = System.currentTimeMillis()
                    runOnUiThread { showSuspiciousRecordingAlert(smoothSpeech) }
                } else if (alertActive && quietFrames >= 30) {
                    alertActive = false
                    runOnUiThread {
                        if (running) {
                            status.text = "●  MONITORING ACTIVE"
                            status.setTextColor(cyan)
                            alertDetails.text = "語音型聲音已降低。仍無法確認附近裝置是否正在錄音。"
                            alertDetails.setTextColor(muted)
                        }
                    }
                }
                val adaptive = if (maskingEnabled) (smoothSpeech * (intensity.progress / 30.0)).coerceIn(0.0, 0.12) else 0.0
                for (i in output.indices) { val hzPhase = 2.0 * PI * 2200.0 / sampleRate; phase += hzPhase; if (phase >= 2 * PI) phase -= 2 * PI; val pseudoNoise = sin(phase * 17.0) * 0.35 + sin(phase * 31.0 + 1.7) * 0.25 + sin(phase * 53.0 + 0.4) * 0.20; output[i] = ((pseudoNoise + sin(phase) * 0.20) * adaptive * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort() }
                track?.write(output, 0, output.size, AudioTrack.WRITE_BLOCKING)
                val pct = (smoothSpeech * 100).roundToInt().coerceIn(0, 100); val mpct = ((adaptive / 0.12) * 100).roundToInt().coerceIn(0, 100); val peakHz = peakBin * sampleRate / fftSize
                runOnUiThread {
                    speechEnergy.progress = pct
                    maskEnergy.progress = mpct
                    bandText.text = "Speech band: $pct%     Peak: $peakHz Hz"
                    score.text = "Acoustic activity index  $pct%  •  heuristic only"
                    spectrum.setLevels(bins)
                }
            }
        }.also { it.start() }
    }

    private fun showSuspiciousRecordingAlert(speechRatio: Double) {
        if (!running) return
        status.text = "⚠  疑似錄音環境"
        status.setTextColor(Color.rgb(255, 190, 72))
        alertDetails.text = "偵測到持續語音型聲音（${(speechRatio * 100).roundToInt()}%）。這不是附近手機正在錄音的證據。"
        alertDetails.setTextColor(Color.rgb(255, 190, 72))
        try {
            val vibrator: Vibrator? = if (android.os.Build.VERSION.SDK_INT >= 31) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() == true) {
                if (android.os.Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(220, VibrationEffect.DEFAULT_AMPLITUDE))
                else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(220)
                }
            }
        } catch (_: Exception) {}
        Toast.makeText(this, "警示：持續語音型聲音；無法確認是否有裝置錄音", Toast.LENGTH_LONG).show()
    }

    private fun stopShield() {
        running = false
        try { recorder?.stop() } catch (_: Exception) {}
        try { track?.stop() } catch (_: Exception) {}
        worker?.join(700); worker = null
        recorder?.release(); recorder = null; track?.release(); track = null
        alertActive = false
        if (::status.isInitialized) { status.text = "●  MONITORING OFF"; status.setTextColor(Color.rgb(255, 111, 132)) }
        if (::alertDetails.isInitialized) { alertDetails.text = "監測已停止。此工具不能確認附近裝置是否正在錄音。"; alertDetails.setTextColor(muted) }
        if (::speechEnergy.isInitialized) speechEnergy.progress = 0
        if (::maskEnergy.isInitialized) maskEnergy.progress = 0
        if (::bandText.isInitialized) bandText.text = "Speech band: —     Peak: — Hz"
        if (::score.isInitialized) score.text = "Acoustic activity score  —"
        if (::spectrum.isInitialized) spectrum.setLevels(IntArray(24))
        if (::startButton.isInitialized) startButton.isEnabled = true
        if (::stopButton.isInitialized) stopButton.isEnabled = false
    }

    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size; var j = 0
        for (i in 1 until n) { var bit = n shr 1; while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }; j = j xor bit; if (i < j) { val tr = real[i]; real[i] = real[j]; real[j] = tr; val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti } }
        var len = 2
        while (len <= n) { val ang = -2.0 * PI / len; val wLenR = cos(ang); val wLenI = sin(ang); var i = 0
            while (i < n) { var wr = 1.0; var wi = 0.0; for (k in 0 until len / 2) { val uR = real[i+k]; val uI = imag[i+k]; val vR = real[i+k+len/2]*wr - imag[i+k+len/2]*wi; val vI = real[i+k+len/2]*wi + imag[i+k+len/2]*wr; real[i+k] = uR+vR; imag[i+k] = uI+vI; real[i+k+len/2] = uR-vR; imag[i+k+len/2] = uI-vI; val nr = wr*wLenR-wi*wLenI; wi = wr*wLenI+wi*wLenR; wr = nr }; i += len }; len = len shl 1 }
    }
    override fun onDestroy() { stopShield(); super.onDestroy() }

    private inner class SpectrumView : View(this@MainActivity) {
        private val bars = IntArray(24) { 8 }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun setLevels(values: IntArray) { for (i in bars.indices) bars[i] = values.getOrElse(i) { 0 }.coerceIn(2,100); postInvalidate() }
        override fun onDraw(canvas: Canvas) { super.onDraw(canvas); val gap = width * 0.012f; val bw = (width - gap * (bars.size + 1)) / bars.size; val maxH = height * 0.86f
            for (i in bars.indices) { val h = max(dp(3).toFloat(), maxH * bars[i] / 100f); val left = gap + i * (bw + gap); paint.color = when { i < 6 -> Color.rgb(34, 139, 255); i < 12 -> Color.rgb(42, 219, 170); i < 18 -> Color.rgb(255, 180, 67); else -> Color.rgb(255, 79, 111) }; canvas.drawRoundRect(RectF(left, height - h, left + bw, height.toFloat()), bw / 2, bw / 2, paint) }
        }
    }
}
