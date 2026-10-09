package com.example.antirecording

import android.Manifest
import android.content.pm.PackageManager
import android.media.*
import android.os.Bundle
import android.os.Process
import android.widget.*
import androidx.activity.ComponentActivity
import kotlin.math.*

/**
 * Anti-Recording Shield v0.2 research prototype.
 *
 * This version adds microphone spectrum analysis (FFT) and adaptive masking.
 * It is an experimental acoustic privacy tool; it cannot guarantee prevention
 * of recording and must be used at conservative volume levels.
 */
class MainActivity : ComponentActivity() {
    private var running = false
    private var worker: Thread? = null
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private lateinit var status: TextView
    private lateinit var speechEnergy: ProgressBar
    private lateinit var maskEnergy: ProgressBar
    private lateinit var intensity: SeekBar
    private lateinit var bandText: TextView

    private val sampleRate = 44100
    private val fftSize = 1024

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)
        }
        buildUi()
    }

    private fun buildUi() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 28, 32, 24)
        }
        TextView(this).apply {
            text = "Anti-Recording Shield v0.2\nAdaptive ASR Research"
            textSize = 23f
        }.also { box.addView(it) }

        status = TextView(this).apply {
            text = "OFF"
            textSize = 19f
            setPadding(0, 22, 0, 10)
        }
        box.addView(status)

        box.addView(TextView(this).apply { text = "Detected speech-band energy" })
        speechEnergy = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        box.addView(speechEnergy)

        box.addView(TextView(this).apply { text = "Adaptive masking level" })
        maskEnergy = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        box.addView(maskEnergy)

        bandText = TextView(this).apply { text = "Band: --"; textSize = 15f; setPadding(0, 10, 0, 6) }
        box.addView(bandText)

        box.addView(TextView(this).apply { text = "Maximum masking intensity (start low)" })
        intensity = SeekBar(this).apply { max = 30; progress = 6 }
        box.addView(intensity)

        val start = Button(this).apply {
            text = "START ADAPTIVE PROTECTION"
            setOnClickListener { if (running) stopShield() else startShield() }
        }
        box.addView(start)

        box.addView(TextView(this).apply {
            text = "\nResearch workflow:\n1. Analyze microphone FFT.\n2. Estimate 1–4 kHz speech-band energy.\n3. Generate low-level adaptive masking.\n4. Record externally and compare ASR/WER.\n\nSafety: keep output low. This prototype does not guarantee that another device cannot record. Avoid prolonged high-frequency playback."
            textSize = 13f
        })
        setContentView(box)
    }

    private fun startShield() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (running) return

        val minRec = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val recBuffer = max(minRec, fftSize * 2)
        recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            recBuffer
        )

        val minOut = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val outBuffer = max(minOut, fftSize * 2)
        track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            outBuffer,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )

        try {
            recorder!!.startRecording()
            track!!.play()
        } catch (e: Exception) {
            status.text = "Audio start failed: ${e.javaClass.simpleName}"
            recorder?.release(); recorder = null
            track?.release(); track = null
            return
        }

        running = true
        status.text = "ON — FFT adaptive masking active"

        worker = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val input = ShortArray(fftSize)
            val output = ShortArray(fftSize)
            val real = DoubleArray(fftSize)
            val imag = DoubleArray(fftSize)
            var phase = 0.0
            var smoothSpeech = 0.0

            while (running) {
                val n = recorder?.read(input, 0, input.size, AudioRecord.READ_BLOCKING) ?: 0
                if (n <= 0) continue
                for (i in 0 until fftSize) {
                    val x = if (i < n) input[i] / 32768.0 else 0.0
                    // Hann window
                    val w = 0.5 - 0.5 * cos(2.0 * PI * i / (fftSize - 1))
                    real[i] = x * w
                    imag[i] = 0.0
                }
                fft(real, imag)

                var speechPower = 0.0
                var totalPower = 1e-9
                var peakBin = 0
                var peakPower = 0.0
                for (k in 1 until fftSize / 2) {
                    val p = real[k] * real[k] + imag[k] * imag[k]
                    totalPower += p
                    val hz = k.toDouble() * sampleRate / fftSize
                    if (hz in 1000.0..4000.0) speechPower += p
                    if (hz in 1000.0..6000.0 && p > peakPower) {
                        peakPower = p
                        peakBin = k
                    }
                }

                val ratio = (speechPower / totalPower).coerceIn(0.0, 1.0)
                smoothSpeech = 0.85 * smoothSpeech + 0.15 * ratio
                val adaptive = (smoothSpeech * (intensity.progress / 30.0)).coerceIn(0.0, 0.18)

                // Broadband low-level masking with a modest 1–6 kHz emphasis.
                for (i in output.indices) {
                    val hzPhase = 2.0 * PI * 2200.0 / sampleRate
                    phase += hzPhase
                    if (phase >= 2 * PI) phase -= 2 * PI
                    val pseudoNoise = sin(phase * 17.0) * 0.35 + sin(phase * 31.0 + 1.7) * 0.25 + sin(phase * 53.0 + 0.4) * 0.20
                    val shaped = pseudoNoise + sin(phase) * 0.20
                    output[i] = (shaped * adaptive * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                track?.write(output, 0, output.size, AudioTrack.WRITE_BLOCKING)

                val pct = (smoothSpeech * 100).roundToInt().coerceIn(0, 100)
                val mpct = ((adaptive / 0.18) * 100).roundToInt().coerceIn(0, 100)
                val peakHz = peakBin * sampleRate / fftSize
                runOnUiThread {
                    speechEnergy.progress = pct
                    maskEnergy.progress = mpct
                    bandText.text = "Speech-band: $pct%   Peak: ${peakHz} Hz   Mask: $mpct%"
                }
            }
        }.also { it.start() }
    }

    private fun stopShield() {
        running = false
        worker?.join(500)
        worker = null
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release(); recorder = null
        try { track?.stop() } catch (_: Exception) {}
        track?.release(); track = null
        status.text = "OFF"
        speechEnergy.progress = 0
        maskEnergy.progress = 0
        bandText.text = "Band: --"
    }

    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wLenR = cos(ang)
            val wLenI = sin(ang)
            var i = 0
            while (i < n) {
                var wr = 1.0
                var wi = 0.0
                for (k in 0 until len / 2) {
                    val uR = real[i + k]
                    val uI = imag[i + k]
                    val vR = real[i + k + len / 2] * wr - imag[i + k + len / 2] * wi
                    val vI = real[i + k + len / 2] * wi + imag[i + k + len / 2] * wr
                    real[i + k] = uR + vR
                    imag[i + k] = uI + vI
                    real[i + k + len / 2] = uR - vR
                    imag[i + k + len / 2] = uI - vI
                    val nextWr = wr * wLenR - wi * wLenI
                    wi = wr * wLenI + wi * wLenR
                    wr = nextWr
                }
                i += len
            }
            len = len shl 1
        }
    }

    override fun onDestroy() {
        stopShield()
        super.onDestroy()
    }
}
