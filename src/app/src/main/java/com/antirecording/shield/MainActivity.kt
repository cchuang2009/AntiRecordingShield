package com.antirecording.shield

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Bundle
import android.os.Build
import android.os.Process
import android.net.wifi.WifiManager
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.view.Gravity
import android.view.View
import android.widget.*
import android.graphics.Color
import android.graphics.Typeface
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.Random
import kotlin.math.*

/**
 * Hush Shield 1.1.4 — acoustic masking + local privacy environment notebook.
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
    private lateinit var modeSpinner: Spinner
    private lateinit var wavButton: Button
    private lateinit var modeNote: TextView
    @Volatile private var mode = 0              // 0 noise, 1 babble, 2 ambient room tone, 3 custom WAV
    private var outRate = 48000
    private lateinit var ambientSpinner: Spinner
    @Volatile private var ambientKind = 0       // 0 fan, 1 air-conditioner, 2 café murmur
    private var wavData: ShortArray? = null     // custom (e.g. adversarial) perturbation, 48 kHz mono 16-bit
    private var wavRms = 1.0
    private val pickWav = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadWav(uri)
    }
    private lateinit var alertDetails: TextView
    @Volatile private var alertActive = false
    private var lastAlertAtMs = 0L
    private val sampleRate = 48000
    private val fftSize = 1024
    private val navy = Color.rgb(7, 20, 39)
    private val card = Color.rgb(15, 36, 61)
    private val blue = Color.rgb(40, 151, 255)
    private val cyan = Color.rgb(57, 222, 244)
    private val white = Color.rgb(241, 247, 255)
    private val muted = Color.rgb(164, 187, 212)

    // Local-only environment notebook. Data stays in this app's private SharedPreferences.
    private val prefs by lazy { getSharedPreferences("privacy_environment_v11", MODE_PRIVATE) }
    private val historyItems = mutableListOf<String>()
    private val currentSnapshot = mutableListOf<String>()
    private lateinit var deviceOutput: TextView
    private lateinit var historyOutput: TextView
    private lateinit var historySearch: EditText
    private lateinit var deviceNameInput: EditText
    private lateinit var deviceLabelInput: EditText
    private lateinit var deviceIdInput: EditText
    private lateinit var snapshotSummary: TextView
    private val exportCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) try {
            val csv = buildCsv()
            contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(csv) }
            Toast.makeText(this, L.t("CSV exported", "CSV 已匯出"), Toast.LENGTH_SHORT).show()
            addHistory("EXPORT", "CSV export completed")
        } catch (e: Exception) { Toast.makeText(this, L.t("CSV export failed: ${e.message}", "CSV 匯出失敗：${e.message}"), Toast.LENGTH_LONG).show() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        L.load(this)
        OuiDb.load(applicationContext)
        buildUi()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)
        }
    }

    // ---- runtime permission helper for the optional Wi-Fi / Bluetooth features ----
    private var pendingAction: (() -> Unit)? = null
    private var pendingStatus: TextView? = null
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val act = pendingAction; val st = pendingStatus; pendingAction = null; pendingStatus = null
        if (act != null) {
            if (res.isNotEmpty() && res.values.all { it }) act()
            else {
                val msg = permissionHelp(res)
                st?.text = msg
                if (st == null && ::deviceOutput.isInitialized) deviceOutput.text = msg
                showSettingsDialog(L.t("Permission needed", "需要權限"), msg, android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                addHistory("PERMISSION", "Denied: " + res.filterValues { !it }.keys.joinToString { it.substringAfterLast('.') })
            }
        }
    }

    /** Runs [action] once the permissions (and, if [needLocation], the system Location switch) are in place; otherwise explains what is missing. */
    private fun withPermission(perms: Array<String>, status: TextView? = null, needLocation: Boolean = false, action: () -> Unit) {
        val go = {
            if (needLocation && !locationOn()) {
                val m = L.t("Location is turned OFF. Android only returns Wi-Fi scan results (and, on Android 11 and below, Bluetooth scan results) while Location is on. The app does not read or store your position.", "定位已關閉。Android 只有在定位開啟時才會回傳 Wi-Fi 掃描結果（Android 11 以下也包括藍牙掃描結果）。本 App 不會讀取或儲存您的位置。")
                status?.text = m
                showSettingsDialog(L.t("Turn on Location", "開啟定位"), m, android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            } else action()
        }
        if (perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) go()
        else { pendingAction = go; pendingStatus = status; permLauncher.launch(perms) }
    }

    private fun locationOn(): Boolean = try {
        val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled else (lm.isProviderEnabled("gps") || lm.isProviderEnabled("network"))
    } catch (_: Exception) { true }

    private fun permissionHelp(res: Map<String, Boolean>): String {
        val denied = res.filterValues { !it }.keys
        val parts = ArrayList<String>()
        if (denied.any { it.endsWith("NEARBY_WIFI_DEVICES") }) parts.add(L.t("“Nearby devices” (Wi-Fi)", "「附近裝置」（Wi-Fi）"))
        if (denied.any { it.endsWith("BLUETOOTH_SCAN") || it.endsWith("BLUETOOTH_CONNECT") }) parts.add(L.t("“Nearby devices” (Bluetooth)", "「附近裝置」（藍牙）"))
        if (denied.any { it.endsWith("FINE_LOCATION") }) parts.add(if (res["android.permission.ACCESS_COARSE_LOCATION"] == true) L.t("Location set to PRECISE (it is on Approximate)", "定位需設為「精確」（目前是「大概」）") else L.t("Location (precise)", "定位（精確）"))
        return L.t("Permission denied: ", "權限被拒絕：") + parts.joinToString(", ").ifEmpty { L.t("required permission", "必要權限") } + L.t(". Open App settings → Permissions, allow it, then try again.", "。請到「應用程式設定 → 權限」允許後再試一次。")
    }

    private fun showSettingsDialog(title: String, msg: String, action: String) {
        try {
            android.app.AlertDialog.Builder(this).setTitle(title).setMessage(msg)
                .setPositiveButton(L.t("Open settings", "開啟設定")) { _, _ ->
                    try { startActivity(android.content.Intent(action).apply { if (action == android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS) data = android.net.Uri.parse("package:$packageName") }) } catch (_: Exception) {}
                }.setNegativeButton(L.t("Cancel", "取消"), null).show()
        } catch (_: Exception) {}
    }

    /** Plain-language checklist shown when a scan finds nothing. */
    private fun diagnostics(): String {
        fun g(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        fun ok(b: Boolean) = if (b) L.t("OK", "正常") else L.t("MISSING", "缺少")
        val l = ArrayList<String>()
        l.add(L.t("Precise location permission: ${ok(g(Manifest.permission.ACCESS_FINE_LOCATION))}", "精確定位權限：${ok(g(Manifest.permission.ACCESS_FINE_LOCATION))}"))
        l.add(L.t("Location switch: ${if (locationOn()) "OK (on)" else "OFF"}", "定位開關：${if (locationOn()) "正常（已開）" else "關閉"}"))
        if (Build.VERSION.SDK_INT >= 33) l.add(L.t("Nearby Wi-Fi devices permission: ${ok(g(Manifest.permission.NEARBY_WIFI_DEVICES))}", "附近 Wi-Fi 裝置權限：${ok(g(Manifest.permission.NEARBY_WIFI_DEVICES))}"))
        if (Build.VERSION.SDK_INT >= 31) l.add(L.t("Nearby Bluetooth permission: ${ok(g(Manifest.permission.BLUETOOTH_SCAN) && g(Manifest.permission.BLUETOOTH_CONNECT))}", "附近藍牙權限：${ok(g(Manifest.permission.BLUETOOTH_SCAN) && g(Manifest.permission.BLUETOOTH_CONNECT))}"))
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        l.add(L.t("Wi-Fi: ${if (wm?.isWifiEnabled == true) "OK (on)" else "OFF"}", "Wi-Fi：${if (wm?.isWifiEnabled == true) "正常（已開）" else "關閉"}"))
        val bt = try { getSystemService(BluetoothManager::class.java)?.adapter } catch (_: Exception) { null }
        l.add(L.t("Bluetooth: ${if (bt?.isEnabled == true) "OK (on)" else "OFF"}", "藍牙：${if (bt?.isEnabled == true) "正常（已開）" else "關閉"}"))
        return l.joinToString("\n") { "• $it" }
    }

    private fun wifiPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    private fun btPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()

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
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(24)) }
        scroll.addView(page)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }      // "Sound Shield" tab
        val radarBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }  // "Radar" tab
        val wifiBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }   // "Wi-Fi · Notes" tab
        val testBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }   // "Tests" tab
        val roomBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }   // "Room check" tab
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val logo = ImageView(this).apply { setImageResource(R.mipmap.ic_launcher); contentDescription = L.t("Hush Shield icon", "Hush Shield 圖示") }
        header.addView(logo, LinearLayout.LayoutParams(dp(48), dp(48)))
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        titleBox.addView(label("Hush Shield", 20f, white, true))
        titleBox.addView(label(L.t("VOICE PRIVACY + NEARBY RADAR  •  v${FeatureNotes.releases[0].version}", "語音隱私 + 附近雷達  •  v${FeatureNotes.releases[0].version}"), 10f, cyan, true))
        header.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(header)
        addGap(page, 12)
        // ---- tab bar: every feature is one tap away instead of far below the mask controls ----
        val tabNames = arrayOf(L.t("Sound Shield", "聲音護盾"), L.t("Radar", "雷達"), L.t("Wi-Fi · Notes", "Wi-Fi · 筆記"), L.t("Tests", "測試"), L.t("Room check", "房間檢查"))
        val boxes = arrayOf<View>(root, radarBox, wifiBox, testBox, roomBox)
        val tabBtns = ArrayList<Button>()
        fun selectTab(i: Int) {
            boxes.forEachIndexed { k, b -> b.visibility = if (k == i) View.VISIBLE else View.GONE }
            tabBtns.forEachIndexed { k, t -> t.alpha = if (k == i) 1f else 0.55f; t.setTextColor(if (k == i) cyan else white) }
            scroll.scrollTo(0, 0)
        }
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabNames.forEachIndexed { i, n ->
            val b = Button(this).apply { text = n; isAllCaps = false; textSize = 12f; minWidth = 0; minimumWidth = 0; setPadding(dp(12), 0, dp(12), 0); setOnClickListener { selectTab(i) } }
            tabBtns.add(b); tabRow.addView(b, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(4) })
        }
        page.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabRow) })
        addGap(page, 12)
        boxes.forEach { page.addView(it) }

        val statusCard = cardLayout()
        statusCard.addView(label(L.t("LIVE ACOUSTIC MONITOR", "即時聲學監測"), 12f, muted, true))
        addGap(statusCard, 10)
        val statusPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(12), dp(16), dp(12), dp(16))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.rgb(55, 31, 49)); cornerRadius = dp(16).toFloat(); setStroke(dp(1), Color.rgb(178, 62, 91)) }
        }
        status = label(L.t("●  MONITORING OFF", "●  監測已關閉"), 18f, Color.rgb(255, 111, 132), true)
        statusPanel.addView(status)
        score = label(L.t("Acoustic activity score  —", "聲學活動分數  —"), 13f, white)
        statusPanel.addView(score)
        statusCard.addView(statusPanel)
        addGap(statusCard, 8)
        alertDetails = label(L.t("Acoustic monitoring on standby. It can only flag sustained speech-like sound; it cannot confirm that a nearby device is recording.", "聲學監測待命。此功能只能提示持續的語音型聲音，不能確認附近裝置是否正在錄音。"), 12f, muted)
        statusCard.addView(alertDetails)
        addGap(statusCard)
        statusCard.addView(label(L.t("LIVE SPECTRUM", "即時頻譜"), 12f, muted, true))
        spectrum = SpectrumView()
        statusCard.addView(spectrum, LinearLayout.LayoutParams(-1, dp(92)))
        statusCard.addView(label(L.t("Illustrative acoustic spectrum • updates while monitoring", "示意聲學頻譜 • 監測時會更新"), 11f, muted))
        root.addView(statusCard)
        addGap(root, 14)

        val metrics = cardLayout()
        metrics.addView(label(L.t("SIGNAL ANALYSIS", "訊號分析"), 12f, muted, true))
        addGap(metrics, 12)
        metrics.addView(label(L.t("Speech-band energy", "語音頻帶能量"), 14f, white, true))
        speechEnergy = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(255, 76, 105)); progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(37, 60, 83)) }
        metrics.addView(speechEnergy, LinearLayout.LayoutParams(-1, dp(8)))
        addGap(metrics, 12)
        metrics.addView(label(L.t("Masking output level", "遮蔽輸出音量"), 14f, white, true))
        maskEnergy = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progressTintList = android.content.res.ColorStateList.valueOf(blue); progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(37, 60, 83)) }
        metrics.addView(maskEnergy, LinearLayout.LayoutParams(-1, dp(8)))
        addGap(metrics, 10)
        bandText = label(L.t("Speech band: —     Peak: — Hz", "語音頻帶：—     峰值：— Hz"), 12f, muted)
        metrics.addView(bandText)
        root.addView(metrics)
        addGap(root, 14)

        val control = cardLayout()
        control.addView(label(L.t("SOUND SHIELD  •  ADAPTIVE MASKING", "聲音護盾  •  自適應遮蔽"), 12f, muted, true))
        maskSwitch = Switch(this).apply { text = L.t("Enable low-level masking", "啟用低音量遮蔽"); textSize = 15f; setTextColor(white); isChecked = true; buttonTintList = android.content.res.ColorStateList.valueOf(cyan); setOnCheckedChangeListener { _, checked -> maskingEnabled = checked } }
        control.addView(maskSwitch)
        addGap(control, 8)
        control.addView(label(L.t("Masking mode", "遮蔽模式"), 13f, white))
        val modes = arrayOf(L.t("1  Speech-shaped noise", "1  語音頻譜雜訊"), L.t("2  Babble (8 synthetic voices)", "2  人聲喧鬧（8 個合成人聲）"), L.t("3  Ambient room tone (low-profile)", "3  環境室內音（低調）"), L.t("4  Custom WAV (adversarial)", "4  自訂 WAV（對抗式）"))
        modeSpinner = Spinner(this).apply {
            adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_item, modes) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                    (super.getView(position, convertView, parent) as TextView).apply { setTextColor(white) }
                override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                    (super.getDropDownView(position, convertView, parent) as TextView).apply { setTextColor(white); setBackgroundColor(card); setPadding(dp(12), dp(10), dp(12), dp(10)) }
            }
            setPopupBackgroundDrawable(android.graphics.drawable.ColorDrawable(card))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    wavButton.visibility = if (pos == 3) View.VISIBLE else View.GONE
                    ambientSpinner.visibility = if (pos == 2) View.VISIBLE else View.GONE
                    modeNote.text = when (pos) {
                        0 -> L.t("Broadband noise shaped like the long-term speech spectrum (150 Hz–6.5 kHz).", "依長期語音頻譜塑形的寬頻雜訊（150 Hz–6.5 kHz）。")
                        1 -> L.t("Overlapping synthetic syllables from 8 voices; harder for denoisers to separate than steady noise.", "8 個人聲的合成音節互相重疊；比穩定雜訊更難被降噪演算法分離。")
                        2 -> L.t("Masks with a believable room sound (fan / air-conditioner / café murmur) that drifts slowly, fades in over 3 s and rises at most 3 dB when voices are detected. Less aggressive than modes 1-2 but far less likely to draw attention.", "用逼真的室內聲音（風扇／冷氣／咖啡館低語）遮蔽，聲音緩慢變化，3 秒內淡入，偵測到人聲時最多提高 3 dB。比模式 1-2 溫和，但更不容易引人注意。")
                        else -> L.t("Plays a looping 48 kHz mono 16-bit WAV (e.g. perturbation from make_adversarial.py). Effective only against the model it was optimised for.", "循環播放 48 kHz 單聲道 16 位元 WAV（例如 make_adversarial.py 產生的擾動）。只對它所針對的模型有效。")
                    }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        control.addView(modeSpinner)
        ambientSpinner = Spinner(this).apply {
            visibility = View.GONE
            val kinds = arrayOf(L.t("Desk fan", "桌上風扇"), L.t("Air-conditioner / HVAC", "冷氣／空調"), L.t("Café murmur", "咖啡館低語"))
            adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_item, kinds) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                    (super.getView(position, convertView, parent) as TextView).apply { setTextColor(white) }
                override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                    (super.getDropDownView(position, convertView, parent) as TextView).apply { setTextColor(white); setBackgroundColor(card); setPadding(dp(12), dp(10), dp(12), dp(10)) }
            }
            setPopupBackgroundDrawable(android.graphics.drawable.ColorDrawable(card))
        }
        control.addView(ambientSpinner)
        wavButton = Button(this).apply { text = L.t("Choose WAV…", "選擇 WAV…"); visibility = View.GONE; setOnClickListener { pickWav.launch(arrayOf("audio/*")) } }
        control.addView(wavButton)
        modeNote = label(L.t("Broadband noise shaped like the long-term speech spectrum (150 Hz–6.5 kHz).", "依長期語音頻譜塑形的寬頻雜訊（150 Hz–6.5 kHz）。"), 11f, muted)
        control.addView(modeNote)
        addGap(control, 6)
        control.addView(label(L.t("Masking level  (-36 … -6 dBFS, start low)", "遮蔽音量  （-36 … -6 dBFS，從低音量開始）"), 13f, white))
        intensity = SeekBar(this).apply { max = 30; progress = 12; progressTintList = android.content.res.ColorStateList.valueOf(blue); thumbTintList = android.content.res.ColorStateList.valueOf(cyan) }
        control.addView(intensity)
        addGap(control, 10)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        startButton = Button(this).apply { text = L.t("▶  START", "▶  開始"); setTextColor(Color.WHITE); background = android.graphics.drawable.GradientDrawable().apply { setColor(blue); cornerRadius = dp(14).toFloat() }; setOnClickListener { startShield() } }
        stopButton = Button(this).apply { text = L.t("■  STOP", "■  停止"); setTextColor(white); background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.rgb(39, 58, 81)); cornerRadius = dp(14).toFloat() }; isEnabled = false; setOnClickListener { stopShield() } }
        buttons.addView(startButton, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) })
        buttons.addView(stopButton, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(6) })
        control.addView(buttons)
        root.addView(control)
        addGap(root, 14)
        val disclaimer = cardLayout()
        disclaimer.addView(label(L.t("PRIVACY & SAFETY NOTE", "隱私與安全說明"), 12f, cyan, true))
        addGap(disclaimer, 6)
        disclaimer.addView(label(L.t("This app analyzes sound captured by this phone's microphone and can play audible masking sound. It cannot confirm whether another phone is recording, detect the recording state from RF/Wi-Fi/Bluetooth, or guarantee prevention of recording or speech recognition. Use at a comfortable volume and with consent.", "本 App 會分析手機麥克風收到的聲音，並可播放可聽見的遮蔽聲音。它無法確認其他手機是否正在錄音、無法從 RF／Wi-Fi／藍牙偵測錄音狀態，也不能保證防止錄音或語音辨識。請以舒適音量並在取得同意的情況下使用。"), 12f, muted))
        root.addView(disclaimer)
        addGap(root, 14)
        val radioCard = cardLayout()
        radioCard.addView(label(L.t("NEARBY DEVICE AWARENESS", "附近裝置概覽"), 12f, cyan, true))
        addGap(radioCard, 6)
        radioCard.addView(label(L.t("RF / Wi-Fi / Bluetooth presence is not proof of recording. Android apps cannot determine whether a nearby device is recording from its radio signals. Use these shortcuts only to inspect ordinary nearby connectivity; unknown devices may be absent and legitimate devices may appear.", "RF／Wi-Fi／藍牙訊號存在並不代表正在錄音。Android App 無法從無線電訊號判斷附近裝置是否正在錄音。這些捷徑只用來查看一般的附近連線；未知裝置可能不會出現，合法裝置也可能出現。"), 12f, muted))
        addGap(radioCard, 8)
        val radioButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val wifiButton = Button(this).apply { text = L.t("Wi-Fi settings", "Wi-Fi 設定"); setOnClickListener { try { startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)) } catch (_: Exception) { Toast.makeText(this@MainActivity, L.t("Wi-Fi settings unavailable", "無法開啟 Wi-Fi 設定"), Toast.LENGTH_SHORT).show() } } }
        val btButton = Button(this).apply { text = L.t("Bluetooth settings", "藍牙設定"); setOnClickListener { try { startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (_: Exception) { Toast.makeText(this@MainActivity, L.t("Bluetooth settings unavailable", "無法開啟藍牙設定"), Toast.LENGTH_SHORT).show() } } }
        radioButtons.addView(wifiButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        radioButtons.addView(btButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        radioCard.addView(radioButtons)
        wifiBox.addView(radioCard)
        addGap(wifiBox, 14)
        buildRadarCard(radarBox)
        buildWifiRadarCard(wifiBox)
        addGap(wifiBox, 14)
        buildEnvironmentNotebook(wifiBox)
        addGap(wifiBox, 14)
        buildFeaturesCard(wifiBox)
        buildDeviceTestChecklist(testBox)
        buildRoomTab(roomBox)
        selectTab(0)
        setContentView(scroll)
        scroll.clipToPadding = false
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->      // keep content clear of system bars / cutout
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }
        loadHistory()
        refreshHistory()
        refreshKnownDevices()
    }

    private fun buildEnvironmentNotebook(root: LinearLayout) {
        val notebook = cardLayout()
        notebook.addView(label(L.t("DEVICE AWARENESS & LOCAL RECORDS", "裝置概覽與本機紀錄"), 12f, cyan, true))
        addGap(notebook, 6)
        notebook.addView(label(L.t("Scan visibility depends on Android version, permissions, Wi-Fi settings and device discoverability. Unknown devices are not proof of recording.", "掃描能看到什麼取決於 Android 版本、權限、Wi-Fi 設定和裝置是否可被探索。未知裝置不代表正在錄音。"), 11f, muted))
        addGap(notebook, 8)
        val scanButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scanButtons.addView(Button(this).apply { text = L.t("Scan Wi-Fi", "掃描 Wi-Fi"); setOnClickListener { withPermission(wifiPermissions(), deviceOutput, true) { scanWifiNetworks() } } }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        scanButtons.addView(Button(this).apply { text = L.t("Paired Bluetooth", "已配對藍牙"); setOnClickListener { withPermission(btPermissions(), deviceOutput) { showPairedBluetooth() } } }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        notebook.addView(scanButtons)
        deviceOutput = label(L.t("No scan performed in this session.", "本次尚未進行掃描。"), 12f, white)
        notebook.addView(deviceOutput)
        addGap(notebook, 6)
        val snapButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        snapButtons.addView(Button(this).apply { text = L.t("Save snapshot", "儲存快照"); setOnClickListener { saveSnapshot() } }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        snapButtons.addView(Button(this).apply { text = L.t("Compare snapshots", "比較快照"); setOnClickListener { compareSnapshots() } }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        notebook.addView(snapButtons)
        snapshotSummary = label(L.t("Snapshots: ${prefs.getInt("snapshot_count", 0)}", "快照數：${prefs.getInt("snapshot_count", 0)}"), 11f, muted)
        notebook.addView(snapshotSummary)
        addGap(notebook, 10)
        notebook.addView(label(L.t("KNOWN DEVICE LABELS", "已知裝置標籤"), 12f, muted, true))
        deviceIdInput = EditText(this).apply { hint = L.t("Device ID / BSSID / Bluetooth address", "裝置 ID／BSSID／藍牙位址"); setSingleLine(true); setTextColor(white); setHintTextColor(muted) }
        deviceNameInput = EditText(this).apply { hint = L.t("Device name (e.g. living-room TV)", "裝置名稱（例如：客廳電視）"); setSingleLine(true); setTextColor(white); setHintTextColor(muted) }
        deviceLabelInput = EditText(this).apply { hint = L.t("Label (Router / TV / Laptop / known)", "標籤（路由器／電視／筆電／已知）"); setSingleLine(true); setTextColor(white); setHintTextColor(muted) }
        notebook.addView(deviceIdInput); notebook.addView(deviceNameInput); notebook.addView(deviceLabelInput)
        notebook.addView(Button(this).apply { text = L.t("Save / update known device", "儲存／更新已知裝置"); setOnClickListener { saveDeviceLabel() } })
        val knownText = label(L.t("Known devices are local notes only; labels do not certify a device is safe.", "已知裝置只是本機備註；標籤不代表該裝置安全。"), 11f, muted)
        notebook.addView(knownText)
        val knownOutput = label("", 11f, white)
        notebook.addView(knownOutput)
        // Keep the known-device list on the card and refresh it after edits.
        knownDevicesView = knownOutput
        addGap(notebook, 10)
        notebook.addView(label(L.t("EVENT HISTORY", "事件紀錄"), 12f, muted, true))
        historySearch = EditText(this).apply { hint = L.t("Search history (e.g. WIFI, WIFI_RADAR, BT_RADAR, AUDIO)", "搜尋紀錄（例如 WIFI、WIFI_RADAR、BT_RADAR、AUDIO）"); setSingleLine(true); setTextColor(white); setHintTextColor(muted) }
        notebook.addView(historySearch)
        notebook.addView(Button(this).apply { text = L.t("Search history", "搜尋紀錄"); setOnClickListener { refreshHistory() } })
        historyOutput = label(L.t("No saved events yet.", "尚無儲存的事件。"), 11f, white)
        notebook.addView(historyOutput)
        val historyButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        historyButtons.addView(Button(this).apply { text = L.t("Export CSV", "匯出 CSV"); setOnClickListener { exportCsv.launch("anti_recording_shield_history.csv") } }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        historyButtons.addView(Button(this).apply { text = L.t("Clear history", "清除紀錄"); setOnClickListener { confirmClearHistory() } }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        notebook.addView(historyButtons)
        root.addView(notebook)
    }

    private fun buildDeviceTestChecklist(root: LinearLayout) {
        val testCard = cardLayout()
        testCard.addView(label(L.t("PIXEL 6a / REAL-DEVICE TEST LOG", "Pixel 6a／實機測試紀錄"), 12f, cyan, true))
        addGap(testCard, 6)
        testCard.addView(label(L.t("Run these checks on the actual phone. Each button records a manual test result; it does not automatically verify the hardware.", "請在實際手機上執行這些檢查。每個按鈕只記錄手動測試結果，不會自動驗證硬體。"), 11f, muted))
        val tests = listOf(
            L.t("Microphone input + audible masking output", "麥克風輸入 + 可聽見的遮蔽輸出") to "TEST_AUDIO",
            L.t("Wi-Fi scan permissions and results", "Wi-Fi 掃描權限與結果") to "TEST_WIFI",
            L.t("Bluetooth radar: BLE + Classic scan, distances", "藍牙雷達：BLE + 傳統藍牙掃描、距離") to "TEST_BT_RADAR",
            L.t("Wi-Fi radar: AP plot, RSSI distance, Wi-Fi RTT", "Wi-Fi 雷達：AP 圖、RSSI 距離、Wi-Fi RTT") to "TEST_WIFI_RADAR",
            L.t("Bluetooth paired-device access", "已配對藍牙裝置存取") to "TEST_BLUETOOTH",
            L.t("Save snapshot, search history and export CSV", "儲存快照、搜尋紀錄與匯出 CSV") to "TEST_DATA"
        )
        tests.forEach { (description, key) ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(label(description, 12f, white), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Button(this).apply { text = L.t("PASS", "通過"); setOnClickListener { addHistory(key, "PASS — $description (manual user confirmation)"); Toast.makeText(this@MainActivity, L.t("Pass recorded", "已記錄通過"), Toast.LENGTH_SHORT).show() } })
            row.addView(Button(this).apply { text = L.t("ISSUE", "有問題"); setOnClickListener { addHistory(key, "ISSUE — $description (manual user report)"); Toast.makeText(this@MainActivity, L.t("Issue recorded", "已記錄問題"), Toast.LENGTH_SHORT).show() } })
            testCard.addView(row)
        }
        root.addView(testCard)
    }

    private lateinit var knownDevicesView: TextView

    // ------------------------------------------------------------------------------------
    // Bluetooth radar: BLE scan + Classic discovery, animated sweep, RSSI-based distance
    // ------------------------------------------------------------------------------------
    private lateinit var radar: RadarView
    private lateinit var radarAge: TextView
    private lateinit var radarStatus: TextView
    private lateinit var radarList: TextView
    private lateinit var radarStart: Button
    private lateinit var radarStop: Button
    private lateinit var radarEnv: Button
    private lateinit var radarRange: Button
    private lateinit var finderView: FinderView
    private lateinit var finderStatus: TextView
    private lateinit var finderStop: Button
    private lateinit var cbSortBt: CheckBox
    private lateinit var cbSortWifi: CheckBox
    private val btNums = HashMap<String, Int>()      // device address -> list/radar number (stable during one scan)
    private val wifiNums = HashMap<String, Int>()    // radio cluster key -> list/radar number
    private lateinit var roomStatus: TextView
    private lateinit var roomResult: TextView
    private lateinit var roomStart: Button
    private lateinit var roomExport: Button
    private lateinit var cbRoomLan: CheckBox
    private lateinit var lanStatus: TextView
    private lateinit var lanResult: TextView
    private lateinit var lanStart: Button
    private lateinit var lensStatus: TextView
    private lateinit var ouiStatus: TextView
    private lateinit var proStatus: TextView
    private lateinit var cbBeep: CheckBox
    private var room: RoomCheck? = null
    private var lastReport: RoomCheck.Report? = null
    private var lanScanner: LanScanner? = null
    private var lens: LensFinder? = null
    private val beeper = Beeper()
    private var knownRaw: String? = null
    private var knownJson = JSONObject()
    private val exportPdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val rep = lastReport
        if (uri != null && rep != null) try {
            val bytes = ReportPdf.build(rep, FeatureNotes.releases[0].version)
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            Toast.makeText(this, L.t("PDF report saved", "PDF 報告已儲存"), Toast.LENGTH_SHORT).show()
            addHistory("EXPORT", "Room check PDF exported")
        } catch (e: Exception) { Toast.makeText(this, L.t("PDF export failed: ${e.message}", "PDF 匯出失敗：${e.message}"), Toast.LENGTH_LONG).show() }
    }
    private lateinit var radarAlert: TextView
    private lateinit var wifiAlert: TextView
    private val finder by lazy { DirectionFinder(this) { refreshFinder() } }
    private lateinit var wifiRange: Button
    private lateinit var cbBle: CheckBox
    private lateinit var cbClassic: CheckBox
    private var envIdx = 1
    private var rangeIdx = 1
    private val rangeM = doubleArrayOf(15.0, 20.0, 40.0, 50.0)
    private val envN = doubleArrayOf(2.0, 2.5, 3.2)
    private val envName get() = arrayOf(L.t("Open space", "空曠"), L.t("Indoor", "室內"), L.t("Obstructed", "有遮蔽"))
    private val scanner by lazy { BtScanner(this, onChange = { refreshRadar() }, onFinished = { msg -> onRadarFinished(msg) }) }


    // ------------------------------------------------------------------------------------
    // Wi-Fi radar: access points in range, animated sweep, Wi-Fi RTT distance when available
    // ------------------------------------------------------------------------------------
    private lateinit var wifiRadar: RadarView
    private lateinit var wifiAge: TextView
    private lateinit var wifiStatus: TextView
    private lateinit var wifiList: TextView
    private lateinit var wifiStart: Button
    private lateinit var wifiStop: Button
    private lateinit var wifiEnv: Button
    private lateinit var cbFast: CheckBox
    private lateinit var cbRtt: CheckBox
    private val wifiScanner by lazy { WifiScanner(this, onChange = { refreshWifiRadar() }, onFinished = { msg -> onWifiRadarFinished(msg) }) }

    private fun cycleRange() {
        rangeIdx = (rangeIdx + 1) % rangeM.size
        val t = L.t("Range: ${rangeM[rangeIdx].toInt()} m", "範圍：${rangeM[rangeIdx].toInt()} m")
        if (::radarRange.isInitialized) radarRange.text = t
        if (::wifiRange.isInitialized) wifiRange.text = t
        if (::radar.isInitialized) radar.setRange(rangeM[rangeIdx])
        if (::wifiRadar.isInitialized) wifiRadar.setRange(rangeM[rangeIdx])
    }

    private fun refreshEnvButtons() {
        val t = L.t("Env: ${envName[envIdx]}", "環境：${envName[envIdx]}")
        if (::radarEnv.isInitialized) radarEnv.text = t
        if (::wifiEnv.isInitialized) wifiEnv.text = t
    }

    /** "Features" card in the active language (L), rendered from FeatureNotes.releases (newest first); the button switches Auto / English / 中文. */
    private fun buildFeaturesCard(parent: LinearLayout) {
        val c = cardLayout()
        c.addView(label(L.t("FEATURES", "功能"), 12f, cyan, true))
        val langBtn = Button(this).apply {
            text = "Language / 語言: " + arrayOf("Auto", "English", "中文")[L.mode()]
            setOnClickListener { L.setMode(this@MainActivity, (L.mode() + 1) % 3); recreate() }
        }
        c.addView(langBtn)
        for (r in FeatureNotes.releases) {
            addGap(c, 10)
            c.addView(label("v${r.version}", 13f, white, true))
            addGap(c, 4)
            (if (L.zh) r.zh else r.en).forEach { c.addView(label("• $it", 11f, white)) }
        }
        parent.addView(c)
    }

    private fun buildWifiRadarCard(parent: LinearLayout) {
        val c = cardLayout()
        c.addView(label(L.t("WI-FI RADAR  •  ACCESS POINTS + RTT", "Wi-Fi 雷達  •  基地台 + RTT"), 12f, cyan, true))
        addGap(c, 4)
        wifiRadar = RadarView(this).apply { wifiMode = true }
        c.addView(wifiRadar, LinearLayout.LayoutParams(-1, dp(310)))
        wifiAge = label("", 10f, muted); wifiAge.gravity = Gravity.CENTER_HORIZONTAL
        c.addView(wifiAge, LinearLayout.LayoutParams(-1, -2))
        wifiAlert = label("", 12f, alertRed, true); wifiAlert.visibility = View.GONE
        c.addView(wifiAlert, LinearLayout.LayoutParams(-1, -2))
        val opts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        cbRtt = CheckBox(this).apply { text = L.t("Use Wi-Fi RTT", "使用 Wi-Fi RTT"); isChecked = true; setTextColor(white) }
        wifiEnv = Button(this).apply {
            text = L.t("Env: ${envName[envIdx]}", "環境：${envName[envIdx]}")
            setOnClickListener { envIdx = (envIdx + 1) % envName.size; refreshEnvButtons(); if (scanner.scanning || wifiScanner.scanning) Toast.makeText(this@MainActivity, L.t("Applies to the next scan", "下次掃描才會套用"), Toast.LENGTH_SHORT).show() else { refreshRadar(); refreshWifiRadar() } }
        }
        opts.addView(cbRtt)
        cbFast = CheckBox(this).apply { text = L.t("Fast scan", "快速掃描"); isChecked = false; setTextColor(white) }
        opts.addView(cbFast)
        c.addView(opts)
        wifiRange = Button(this).apply { text = L.t("Range: ${rangeM[rangeIdx].toInt()} m", "範圍：${rangeM[rangeIdx].toInt()} m"); setOnClickListener { cycleRange() } }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(wifiEnv, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        row2.addView(wifiRange, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        c.addView(row2)
        wifiRadar.setRange(rangeM[rangeIdx])
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        wifiStart = Button(this).apply { text = L.t("Start Wi-Fi radar (60 s)", "開始 Wi-Fi 雷達（60 秒）"); setOnClickListener { withPermission(wifiPermissions(), wifiStatus, true) { startWifiRadar() } } }
        wifiStop = Button(this).apply { text = L.t("Stop", "停止"); isEnabled = false; setOnClickListener { stopWifiRadar(true) } }
        btns.addView(wifiStart, LinearLayout.LayoutParams(0, -2, 1.6f).apply { marginEnd = dp(4) })
        btns.addView(wifiStop, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        c.addView(btns)
        wifiStatus = label(L.t("Idle.", "待命。"), 12f, white, true)
        c.addView(wifiStatus)
        wifiList = label("", 11f, white).apply { typeface = Typeface.MONOSPACE }
        c.addView(wifiList)
        c.addView(Button(this).apply { text = L.t("Mark a network as known…", "標記為已知網路…"); setOnClickListener { pickKnownWifi() } })
        cbSortWifi = CheckBox(this).apply { text = L.t("Sort list by # (default: nearest first)", "依編號排序（預設：由近到遠）"); isChecked = false; setTextColor(white); setOnCheckedChangeListener { _, _ -> refreshWifiRadar() } }
        c.addView(cbSortWifi)
        addGap(c, 6)
        c.addView(label(L.t("Shows Wi-Fi access points and hotspots in range, not every phone or camera connected to them (Android cannot list other clients). ✓ = measured by Wi-Fi RTT (802.11mc access points only, on phones with RTT hardware, Wi-Fi on; Location on for Android 12 and below). ~ = estimated from signal strength, rough. Android limits foreground Wi-Fi scans to about 4 per 2 minutes, so the plot refreshes about every 30 s in Normal mode. Direction is arbitrary. Range only changes how the plot is scaled, not how far the radio hears; beyond about 15 m an RSSI distance is a very rough band. A nearby access point is not evidence of recording.", "顯示範圍內的 Wi-Fi 基地台與熱點，不是連到它們的每支手機或攝影機（Android 無法列出其他用戶端）。✓ = 以 Wi-Fi RTT 實測（僅限 802.11mc 基地台、具 RTT 硬體的手機、Wi-Fi 開啟；Android 12 以下需開啟定位）。~ = 依訊號強度估計，很粗略。Android 限制前景 Wi-Fi 掃描約每 2 分鐘 4 次，所以一般模式下圖約每 30 秒更新一次。方向是任意的。範圍只改變圖的縮放，不改變無線電能收到多遠；超過約 15 m 的 RSSI 距離只是很粗略的範圍。附近有基地台不代表正在錄音。"), 10f, muted))
        parent.addView(c)
    }

    private fun startWifiRadar() {
        wifiNums.clear()
        val err = wifiScanner.start(60_000L, cbRtt.isChecked, envN[envIdx], cbFast.isChecked)
        if (err != null) { wifiStatus.text = err; addHistory("WIFI_RADAR", err); return }
        wifiRadar.setScanning(true); wifiRadar.setBlips(emptyList())
        wifiStart.isEnabled = false; wifiStop.isEnabled = true; cbRtt.isEnabled = false
        addHistory("WIFI_RADAR", "Started (RTT=${cbRtt.isChecked}, ${envName[envIdx]})")
        refreshWifiRadar()
    }

    private fun stopWifiRadar(notify: Boolean) {
        wifiScanner.stop()
        if (notify) onWifiRadarFinished(L.t("Stopped", "已停止"))
    }

    private fun onWifiRadarFinished(why: String) {
        wifiRadar.setScanning(false)
        wifiStart.isEnabled = true; wifiStop.isEnabled = false; cbRtt.isEnabled = true
        refreshWifiRadar()
        val s = wifiScanner.summary()
        val notes = ArrayList<String>()
        if (wifiScanner.scansThrottled > 0) notes.add(L.t("${wifiScanner.scansThrottled} of ${wifiScanner.scansRequested} scan requests were throttled by Android (cached results used)", "${wifiScanner.scansThrottled}／${wifiScanner.scansRequested} 次掃描請求被 Android 限流（改用快取結果）"))
        if (cbRtt.isChecked && wifiScanner.rttAvailable == false) notes.add(L.t("Wi-Fi RTT is not available (no RTT hardware, Wi-Fi/Location off, or disabled by the system)", "Wi-Fi RTT 無法使用（沒有 RTT 硬體、Wi-Fi／定位未開，或被系統停用）"))
        if (cbRtt.isChecked && wifiScanner.rttAvailable == true && wifiScanner.aps.values.none { it.mc }) notes.add(L.t("No 802.11mc access point in range, so all distances are RSSI estimates", "範圍內沒有 802.11mc 基地台，所以所有距離都是 RSSI 估計值"))
        wifiScanner.lastError?.let { notes.add(it) }
        if (wifiScanner.aps.isEmpty()) notes.add(L.t("No access points found. Checklist:\n", "找不到基地台。請檢查：\n") + diagnostics())
        wifiStatus.text = "$why · $s" + (if (notes.isEmpty()) "" else "\n" + notes.joinToString("\n"))
        currentSnapshot.clear()
        wifiScanner.aps.values.forEach { currentSnapshot.add("WIFI|${it.ssid.ifBlank { "<hidden SSID>" }}|${it.bssid}|${it.freq}MHz") }
        deviceOutput.text = L.t("Wi-Fi radar: $s\nUse Save snapshot to compare later.", "Wi-Fi 雷達：$s\n之後可用「儲存快照」來比較。")
        addHistory("WIFI_RADAR", s)
    }

    private fun refreshWifiRadar() {
        if (!::wifiRadar.isInitialized) return
        val list = wifiScanner.list()
        val clusters = clusterRadios(list)
        // one blip per physical radio: hidden / guest / virtual BSSIDs of one access point (see clusterRadios) collapse into one
        wifiRadar.setBlips(clusters.mapNotNull { group ->
            val key = clusterKey(group)
            val best = group.minByOrNull { wifiScanner.distanceM(it) ?: Double.MAX_VALUE } ?: return@mapNotNull null
            wifiScanner.distanceM(best)?.let { RadarView.Blip(key, angleOf(key), it, if (group.any { suspectAp(it) != null }) 5 else if (best.band() == 24) 3 else 4, wifiScanner.isMeasured(best), wifiScanner.ageFrac(best), numOf(wifiNums, key)) }
        })
        wifiAge.text = wifiScanner.updatedText()
        updateAlert(wifiAlert, list.filter { suspectAp(it) != null }.map { "${it.ssid} ${it.bssid}" })
        if (wifiScanner.scanning) wifiStatus.text = "Scanning… ${wifiScanner.remainingSec()} s · ${wifiScanner.summary()} · ${L.t("radios", "無線電")} ${clusters.size}" + (wifiScanner.lastError?.let { " · $it" } ?: "")
        val groups = clusters.sortedByDescending { g -> g.any { suspectAp(it) != null } }       // nearest first; same radio = same BSSID except last byte + channel
        wifiList.text = if (list.isEmpty()) "" else redizeLines((if (::cbSortWifi.isInitialized && cbSortWifi.isChecked) groups.sortedBy { numOf(wifiNums, clusterKey(it)) } else groups).take(30).joinToString("\n") { g ->
            val a = g[0]
            val hit = g.firstOrNull { suspectAp(it) != null }
            val band = when (a.band()) { 24 -> "2.4G"; 5 -> "5G  "; else -> "6G  " }
            val m = wifiScanner.isMeasured(a)
            val d = wifiScanner.distanceM(a)
            val dist = if (d == null) "   ?   " else if (d > rangeM[rangeIdx]) " >${rangeM[rangeIdx].toInt()} m " else (if (m) " " else "~") + String.format(Locale.US, "%4.1f m", d)
            val rssi = if (a.hasRssi()) "${a.rssi()} dBm" else "-- dBm"
            val extra = listOfNotNull(a.security, if (a.mc) "802.11mc" else null, if (m) String.format(Locale.US, "RTT %.1f±%.1f m", a.rttM, a.rttStdM ?: 0.0) else null).joinToString("  ")
            "${if (hit != null) "⚠ " else ""}${"#${numOf(wifiNums, clusterKey(g))}".padEnd(4)}$band ch${a.channel().toString().padEnd(3)} $dist${if (m) "✓" else " "} ${rssi.padStart(8)}  ${a.ssid.ifBlank { "<hidden>" }}\n          ${a.bssid}  $extra" + (if (g.size > 1) L.t("\n          +${g.size - 1} more on this radio: ", "\n          同一無線電另有 ${g.size - 1} 個：") + g.drop(1).joinToString(", ") { it.ssid.ifBlank { "<hidden>" } } else "") +
                (if (hit != null) L.t("\n          ⚠ looks like a camera/recorder: ${suspectAp(hit)} (${hit.ssid})", "\n          ⚠ 疑似攝影機／錄音器：${suspectAp(hit)}（${hit.ssid}）") else "")
        })
    }

    private fun scanPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun buildRadarCard(root: LinearLayout) {
        val c = cardLayout()
        c.addView(label(L.t("BLUETOOTH RADAR  •  BLE + CLASSIC", "藍牙雷達  •  BLE + 傳統藍牙"), 12f, cyan, true))
        addGap(c, 4)
        radar = RadarView(this)
        c.addView(radar, LinearLayout.LayoutParams(-1, dp(310)))
        radarAge = label("", 10f, muted); radarAge.gravity = Gravity.CENTER_HORIZONTAL
        c.addView(radarAge, LinearLayout.LayoutParams(-1, -2))
        radarAlert = label("", 12f, alertRed, true); radarAlert.visibility = View.GONE
        c.addView(radarAlert, LinearLayout.LayoutParams(-1, -2))
        val opts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        cbBle = CheckBox(this).apply { text = "BLE"; isChecked = true; setTextColor(white) }
        cbClassic = CheckBox(this).apply { text = L.t("Classic", "傳統藍牙"); isChecked = true; setTextColor(white) }
        radarEnv = Button(this).apply {
            text = L.t("Env: ${envName[envIdx]}", "環境：${envName[envIdx]}")
            setOnClickListener { envIdx = (envIdx + 1) % envName.size; refreshEnvButtons(); if (scanner.scanning || wifiScanner.scanning) Toast.makeText(this@MainActivity, L.t("Applies to the next scan", "下次掃描才會套用"), Toast.LENGTH_SHORT).show() else { refreshRadar(); refreshWifiRadar() } }
        }
        opts.addView(cbBle); opts.addView(cbClassic)
        c.addView(opts)
        radarRange = Button(this).apply { text = L.t("Range: ${rangeM[rangeIdx].toInt()} m", "範圍：${rangeM[rangeIdx].toInt()} m"); setOnClickListener { cycleRange() } }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(radarEnv, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        row2.addView(radarRange, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        c.addView(row2)
        radar.setRange(rangeM[rangeIdx])
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        radarStart = Button(this).apply { text = L.t("Start scan", "開始掃描"); setOnClickListener { withPermission(scanPermissions(), radarStatus, true) { startRadar() } } }
        radarStop = Button(this).apply { text = L.t("Stop", "停止"); isEnabled = false; setOnClickListener { stopRadar(true) } }
        btns.addView(radarStart, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        btns.addView(radarStop, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        c.addView(btns)
        radarStatus = label(L.t("Idle.", "待命。"), 12f, white, true)
        c.addView(radarStatus)
        radarList = label("", 11f, white).apply { typeface = Typeface.MONOSPACE }
        c.addView(radarList)
        c.addView(Button(this).apply { text = L.t("Mark a device as known…", "標記為已知裝置…"); setOnClickListener { pickKnownBt() } })
        cbSortBt = CheckBox(this).apply { text = L.t("Sort list by # (default: nearest first)", "依編號排序（預設：由近到遠）"); isChecked = false; setTextColor(white); setOnCheckedChangeListener { _, _ -> refreshRadar() } }
        c.addView(cbSortBt)
        addGap(c, 14)
        c.addView(label(L.t("DIRECTION FINDER  •  BLE", "找方向  •  BLE"), 12f, cyan, true))
        c.addView(label(L.t("Pick a Bluetooth device, hold the phone flat in front of you (top edge pointing forward) and turn slowly on the spot for about 20 s. The arrow then shows where its signal is strongest. Your body blocks the signal, so the peak is when you FACE the device. Accuracy is roughly ±30–45°, worse near walls.", "選一個藍牙裝置，手機平放在胸前（頂端朝前），在原地慢慢轉一圈約 20 秒。箭頭會指出訊號最強的方向。人體會擋住訊號，所以面向裝置時訊號最強。準確度約 ±30–45°，靠近牆壁時更差。"), 11f, muted))
        finderView = FinderView(this)
        c.addView(finderView, LinearLayout.LayoutParams(-1, dp(280)))
        val fbtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val finderPick = Button(this).apply { text = L.t("Pick target & turn", "選目標並轉圈"); setOnClickListener { pickFinderTarget() } }
        finderStop = Button(this).apply { text = L.t("Stop", "停止"); isEnabled = false; setOnClickListener { stopFinder() } }
        fbtns.addView(finderPick, LinearLayout.LayoutParams(0, -2, 1.6f).apply { marginEnd = dp(4) })
        fbtns.addView(finderStop, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        c.addView(fbtns)
        cbBeep = CheckBox(this).apply { text = L.t("Proximity beeps (faster = stronger signal)", "接近提示音（越快＝訊號越強）"); isChecked = false; setTextColor(white) }
        c.addView(cbBeep)
        finderStatus = label(L.t("Run a Bluetooth scan first, then pick a device here.", "請先在上方做藍牙掃描，再回來選擇裝置。"), 12f, white, true)
        c.addView(finderStatus)
        addGap(c, 6)
        c.addView(label(L.t("Distance is estimated from signal strength (RSSI) and is only rough: bodies, walls and phone orientation can shift it by several metres. Bluetooth gives no direction, so the angle on the radar is arbitrary. Many devices use random addresses and some never advertise. A nearby device is not evidence that it is recording. Range only changes how the plot is scaled, not how far the radio hears; beyond about 15 m an RSSI distance is a very rough band.", "距離由訊號強度（RSSI）估計，只是粗略值：人體、牆壁與手機方向都可能造成數公尺的誤差。藍牙沒有方向資訊，所以雷達上的角度是任意的。許多裝置使用隨機位址，有些完全不廣播。附近有裝置不代表它正在錄音。範圍只改變圖的縮放，不改變無線電能收到多遠；超過約 15 m 的 RSSI 距離只是很粗略的範圍。"), 10f, muted))
        root.addView(c)
    }

    // ---------------- mark-as-known pickers ----------------
    private fun pickKnownBt() {
        val devs = scanner.devices.values.filter { it.hasRssi() }.sortedBy { scanner.distanceM(it) ?: Double.MAX_VALUE }.take(30)
        if (devs.isEmpty()) { radarStatus.text = L.t("No devices yet. Run a Bluetooth scan first.", "還沒有裝置。請先執行藍牙掃描。"); return }
        val items = devs.map { d -> String.format(Locale.US, "~%.1f m   %s\n%s", scanner.distanceM(d) ?: 0.0, d.name ?: L.t("Unknown", "未知"), d.address) }.toTypedArray()
        android.app.AlertDialog.Builder(this).setTitle(L.t("Mark as known (your own device)", "標記為已知（您自己的裝置）")).setItems(items) { _, i ->
            markKnown(devs[i].address, devs[i].name ?: "Unknown"); refreshRadar()
        }.setNegativeButton(L.t("Cancel", "取消"), null).show()
    }

    private fun pickKnownWifi() {
        val groups = clusterRadios(wifiScanner.list()).take(30)
        if (groups.isEmpty()) { wifiStatus.text = L.t("No networks yet. Run the Wi-Fi radar first.", "還沒有網路。請先執行 Wi-Fi 雷達。"); return }
        val items = groups.map { g -> String.format(Locale.US, "%s\n%s  %s", g[0].ssid.ifBlank { "<hidden>" }, g[0].bssid, wifiScanner.distanceM(g[0])?.let { String.format(Locale.US, "~%.0f m", it) } ?: "") }.toTypedArray()
        android.app.AlertDialog.Builder(this).setTitle(L.t("Mark as known (your own network)", "標記為已知（您自己的網路）")).setItems(items) { _, i ->
            groups[i].forEach { markKnown(it.bssid, it.ssid.ifBlank { "<hidden>" }) }; refreshWifiRadar()
        }.setNegativeButton(L.t("Cancel", "取消"), null).show()
    }

    // ---------------- room check / LAN scan / lens finder / vendor database / Pro ----------------
    private fun confirmLan(onYes: () -> Unit) {
        android.app.AlertDialog.Builder(this).setTitle(L.t("Scan this network?", "要掃描這個網路嗎？"))
            .setMessage(L.t("This probes the devices on the Wi-Fi network you are connected to for camera-related open ports. It does not log in, change anything or read any video. Continue only on a network you own or have permission to test.",
                "這會在您目前連線的 Wi-Fi 網路上，探測裝置是否開啟與攝影機相關的連接埠。不會登入、不會更改任何設定、也不會讀取任何影像。請只在您自己擁有或獲准測試的網路上繼續。"))
            .setPositiveButton(L.t("Scan", "掃描")) { _, _ -> onYes() }.setNegativeButton(L.t("Cancel", "取消"), null).show()
    }

    private fun startRoomCheck() {
        val perms = (wifiPermissions() + scanPermissions()).distinct().toTypedArray()
        withPermission(perms, roomStatus, true) {
            if (scanner.scanning) stopRadar(true)
            if (wifiScanner.scanning) stopWifiRadar(true)
            if (finder.running) stopFinder()
            val go = {
                roomStart.isEnabled = false; roomExport.isEnabled = false; roomResult.text = ""
                val rc = RoomCheck(this, { isKnown(it) }, object : RoomCheck.Listener {
                    override fun onStep(step: Int, total: Int, text: String) { roomStatus.text = text }
                    override fun onDone(report: RoomCheck.Report) {
                        lastReport = report
                        roomResult.text = redizeLines(RoomCheck.asText(report))
                        roomStatus.text = L.t("Room check finished.", "房間檢查完成。")
                        roomStart.isEnabled = true; roomExport.isEnabled = true
                        addHistory("ROOM_CHECK", "verdict=${report.verdict} score=${report.score} items=${report.items.size}")
                    }
                })
                room = rc; rc.start(cbRoomLan.isChecked)
            }
            if (cbRoomLan.isChecked) confirmLan(go) else go()
        }
    }

    private fun exportRoomPdf() {
        if (lastReport == null) return
        if (!Pro.allowed(this, Pro.Feature.REPORT_EXPORT)) { Pro.paywall(this); return }
        exportPdf.launch("hush_room_check_" + java.text.SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(java.util.Date()) + ".pdf")
    }

    private fun startLanScan() {
        lanResult.text = ""
        val l = LanScanner(this, { lanStatus.text = it }, { hosts, err -> showLan(hosts, err) })
        lanScanner = l
        val e = l.start()
        if (e != null) lanStatus.text = e else { lanStart.isEnabled = false; lanStatus.text = L.t("Starting…", "啟動中…") }
    }

    private fun showLan(hosts: List<LanScanner.Host>, err: String?) {
        lanStart.isEnabled = true
        val hits = hosts.filter { it.score >= 2 }
        lanStatus.text = (err ?: L.t("LAN scan finished.", "區網掃描完成。")) + " " +
            L.t("${hits.size} camera-like device(s), ${hosts.size} hosts with open ports.", "${hits.size} 個疑似攝影機的裝置，${hosts.size} 台主機有開啟連接埠。")
        val sb = StringBuilder()
        for ((i, h) in hits.withIndex()) sb.append("⚠ ${i + 1}. ").append(h.ip).append("  (").append(L.t("ports ", "連接埠 ")).append(h.ports.joinToString(",")).append(")\n      ").append(h.reasons.joinToString("; ")).append("\n")
        val others = hosts.filter { it.score < 2 }
        if (others.isNotEmpty()) { sb.append("\n").append(L.t("Other hosts with open ports:", "其他有開啟連接埠的主機：")); for (h in others) sb.append("\n• ${h.ip}  ${h.ports.joinToString(",")}") }
        lanResult.text = redizeLines(sb.toString())
        addHistory("LAN_SCAN", "hosts=${hosts.size} candidates=${hits.size}")
    }

    private fun startLens(mode: Int) {
        withPermission(arrayOf(Manifest.permission.CAMERA), lensStatus) {
            val lf = LensFinder(this); lens = lf
            lensStatus.text = lf.show(mode) ?: ""
        }
    }

    private fun ouiText(): String {
        val n = OuiDb.size()
        if (n == 0) return L.t("Vendor database: empty. Tap Update to download the public IEEE list (internet needed).", "廠商資料庫：空的。按「更新」下載公開的 IEEE 清單（需要網路）。")
        val d = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(OuiDb.lastUpdated(this)))
        return L.t("Vendor database: $n camera-maker prefixes, updated $d.", "廠商資料庫：$n 個攝影機廠商前綴，更新於 $d。")
    }

    private fun refreshProStatus() { if (::proStatus.isInitialized) proStatus.text = Pro.statusText(this) }

    private fun buildRoomTab(box: LinearLayout) {
        var c = cardLayout()
        c.addView(label(L.t("ROOM CHECK", "房間檢查"), 12f, cyan, true))
        c.addView(label(L.t("One tap: scans Bluetooth (20 s) and Wi-Fi (30 s), optionally your LAN, lists the hints it finds (camera-like names, camera-maker prefixes, camera-like services) and builds a PDF report. It can never prove that a room is clear.",
            "一鍵檢查：掃描藍牙（20 秒）與 Wi-Fi（30 秒），可選擇再掃描區網，列出找到的線索（像攝影機的名稱、攝影機廠商前綴、像攝影機的服務）並產生 PDF 報告。它無法證明房間沒有問題。"), 11f, muted))
        cbRoomLan = CheckBox(this).apply { text = L.t("Also run the LAN camera scan (only a network you own or may test)", "同時執行區網攝影機掃描（僅限自己擁有或獲准測試的網路）"); isChecked = false; setTextColor(white) }
        c.addView(cbRoomLan)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        roomStart = Button(this).apply { text = L.t("Start room check", "開始房間檢查"); setOnClickListener { startRoomCheck() } }
        val roomStop = Button(this).apply { text = L.t("Stop", "停止"); setOnClickListener { room?.cancel(); roomStart.isEnabled = true; roomStatus.text = L.t("Stopped.", "已停止。") } }
        row.addView(roomStart, LinearLayout.LayoutParams(0, -2, 1.6f).apply { marginEnd = dp(4) })
        row.addView(roomStop, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        c.addView(row)
        roomStatus = label(L.t("Idle.", "待命。"), 12f, white, true); c.addView(roomStatus)
        roomResult = label("", 11f, white).apply { typeface = Typeface.MONOSPACE }; c.addView(roomResult)
        roomExport = Button(this).apply { text = L.t("Export PDF report", "匯出 PDF 報告"); isEnabled = false; setOnClickListener { exportRoomPdf() } }
        c.addView(roomExport)
        box.addView(c); addGap(box, 14)

        c = cardLayout()
        c.addView(label(L.t("LAN CAMERA SCAN", "區網攝影機掃描"), 12f, cyan, true))
        c.addView(label(L.t("Finds devices on the Wi-Fi you are connected to that answer like IP cameras or recorders (RTSP, ONVIF, camera web pages and service ports). It never logs in or reads video. Only on networks you own or may test. A hit is a hint: routers, NAS and printers can look similar.",
            "找出您目前連線的 Wi-Fi 上、行為像網路攝影機或錄影機的裝置（RTSP、ONVIF、攝影機網頁與服務埠）。不會登入也不會讀取影像。僅限自己擁有或獲准測試的網路。符合只是線索：路由器、NAS 和印表機也可能長得很像。"), 11f, muted))
        val lrow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        lanStart = Button(this).apply { text = L.t("Scan this network", "掃描這個網路"); setOnClickListener {
            if (!Pro.allowed(this@MainActivity, Pro.Feature.LAN_SCAN)) Pro.paywall(this@MainActivity) else confirmLan { startLanScan() } } }
        val lanStop = Button(this).apply { text = L.t("Stop", "停止"); setOnClickListener { lanScanner?.cancel() } }
        lrow.addView(lanStart, LinearLayout.LayoutParams(0, -2, 1.6f).apply { marginEnd = dp(4) })
        lrow.addView(lanStop, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        c.addView(lrow)
        lanStatus = label(L.t("Idle.", "待命。"), 12f, white, true); c.addView(lanStatus)
        lanResult = label("", 11f, white).apply { typeface = Typeface.MONOSPACE }; c.addView(lanResult)
        box.addView(c); addGap(box, 14)

        c = cardLayout()
        c.addView(label(L.t("LENS & IR FINDER", "鏡頭與紅外線輔助"), 12f, cyan, true))
        c.addView(label(L.t("A visual aid, not an automatic detector. Flash mode blinks the torch and circles glints that appear only while it is on (camera lenses reflect light straight back; shiny objects do too). IR mode uses the front camera in a dim room to show infrared LEDs of night-vision cameras (some phones filter IR). Nothing is saved or sent.",
            "這是視覺輔助，不是自動偵測。閃光模式會讓手電筒閃爍，並圈出只在燈亮時出現的反光（鏡頭會把光直接反射回來，閃亮物品也會）。紅外線模式在昏暗房間用前鏡頭顯示夜視攝影機的紅外線燈（有些手機會濾掉紅外線）。不會儲存或傳送任何影像。"), 11f, muted))
        c.addView(Button(this).apply { text = L.t("Flash lens finder (back camera)", "閃光找鏡頭（後鏡頭）"); setOnClickListener { startLens(LensFinder.FLASH) } })
        c.addView(Button(this).apply { text = L.t("IR spot finder (front camera)", "紅外線亮點（前鏡頭）"); setOnClickListener { startLens(LensFinder.IR) } })
        lensStatus = label("", 12f, white, true); c.addView(lensStatus)
        box.addView(c); addGap(box, 14)

        c = cardLayout()
        c.addView(label(L.t("CAMERA-MAKER DATABASE", "攝影機廠商資料庫"), 12f, cyan, true))
        c.addView(label(L.t("Flags Wi-Fi and Bluetooth (Classic) addresses made by companies that sell cameras. Downloaded from the public IEEE list on your request; nothing is hard-coded and no personal data is sent.",
            "標出由攝影機製造商生產的 Wi-Fi 與藍牙（傳統）位址。依您的要求從公開的 IEEE 清單下載；沒有寫死的資料，也不會傳送任何個人資料。"), 11f, muted))
        ouiStatus = label(ouiText(), 12f, white, true); c.addView(ouiStatus)
        c.addView(Button(this).apply { text = L.t("Update database", "更新資料庫"); setOnClickListener {
            if (!Pro.allowed(this@MainActivity, Pro.Feature.VENDOR_DB)) Pro.paywall(this@MainActivity) else {
                ouiStatus.text = L.t("Downloading…", "下載中…")
                OuiDb.update(this@MainActivity) { ok, msg ->
                    ouiStatus.text = if (ok) ouiText() else L.t("Update failed: $msg (internet needed)", "更新失敗：$msg（需要網路）")
                }
            } } })
        box.addView(c); addGap(box, 14)

        c = cardLayout()
        c.addView(label("HUSH SHIELD PRO", 12f, cyan, true))
        proStatus = label(Pro.statusText(this), 12f, white, true); c.addView(proStatus)
        c.addView(Button(this).apply { text = L.t("Upgrade / restore", "升級／還原購買"); setOnClickListener { Pro.paywall(this@MainActivity) } })
        box.addView(c)
    }

    // ---------------- direction finder ----------------
    private fun pickFinderTarget() {
        val devs = scanner.devices.values.filter { it.le && it.hasRssi() }.sortedBy { scanner.distanceM(it) ?: Double.MAX_VALUE }.take(30)
        if (devs.isEmpty()) { finderStatus.text = L.t("No BLE devices yet. Start a Bluetooth scan above, wait a few seconds, then pick a target.", "還沒有 BLE 裝置。請先在上方開始藍牙掃描，等幾秒後再選目標。"); return }
        val items = devs.map { d ->
            String.format(Locale.US, "~%.1f m   %d dBm   %s%s\n%s", scanner.distanceM(d) ?: 0.0, d.rssi(), d.name ?: L.t("Unknown", "未知"), d.vendor?.let { " ($it)" } ?: "", d.address)
        }.toTypedArray()
        android.app.AlertDialog.Builder(this).setTitle(L.t("Pick target", "選擇目標")).setItems(items) { _, i ->
            val d = devs[i]
            withPermission(scanPermissions(), finderStatus, true) { startFinder(d.address, d.name ?: L.t("Unknown", "未知")) }
        }.setNegativeButton(L.t("Cancel", "取消"), null).show()
    }

    private fun startFinder(addr: String, name: String) {
        if (scanner.scanning) stopRadar(true)      // frees the BLE radio for the high-rate, address-filtered scan
        val err = finder.start(addr, name)
        if (err != null) { finderStatus.text = err; return }
        finderStop.isEnabled = true
        beeper.start()
        addHistory("BT_FINDER", "Direction finder started for $addr")
        refreshFinder()
    }

    private fun stopFinder() {
        finder.stop(); beeper.stop()
        if (::finderStop.isInitialized) finderStop.isEnabled = false
        refreshFinder()
    }

    private fun compass8(deg: Float): String = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg + 22.5f) % 360f) / 45f).toInt()]

    private fun refreshFinder() {
        if (!::finderView.isInitialized) return
        val est = finder.estimate()
        val h = if (finder.heading.isNaN()) 0f else finder.heading
        val live = finder.liveRssi?.let { String.format(Locale.US, "%.0f dBm", it) } ?: "–"
        finderView.set(finder.profile(), h, est?.bearing, live)
        val cov = (finder.coverage() * 100).toInt()
        val sb = StringBuilder()
        if (finder.running) {
            val rem = finder.sweepRemainingSec()
            sb.append(if (rem > 0) L.t("Turn slowly on the spot… $rem s", "請在原地慢慢轉圈… $rem 秒") else L.t("Keep turning to refine, or tap Stop", "繼續轉可提高準確度，或按「停止」"))
            sb.append(L.t(" · ${finder.samples} samples · $cov% of circle", " · ${finder.samples} 筆樣本 · 已涵蓋 $cov% 圓周"))
        } else if (finder.samples > 0) sb.append(L.t("Stopped · ${finder.samples} samples", "已停止 · ${finder.samples} 筆樣本")) else sb.append(L.t("Idle.", "待命。"))
        if (finder.targetAddress.isNotEmpty()) sb.append("\n" + L.t("Target: ", "目標：") + "${finder.targetName} ${finder.targetAddress}")
        if (est != null) {
            val d = ((est.bearing - h + 540f) % 360f) - 180f
            val deg = est.bearing.toInt()
            sb.append("\n" + L.t("Strongest signal toward $deg° ${compass8(est.bearing)} (magnetic) · ", "訊號最強方向 $deg° ${compass8(est.bearing)}（磁北）· "))
            sb.append(if (abs(d) <= 15f) L.t("you are pointing at it", "您正對著它")
                      else L.t("turn ${abs(d).toInt()}° ${if (d > 0) "right" else "left"}", "請向${if (d > 0) "右" else "左"}轉 ${abs(d).toInt()}°"))
            if (finder.coverage() < 0.6f) sb.append("\n" + L.t("Turn through more of the circle for a reliable result.", "請多轉一些角度，結果才可靠。"))
            else if (est.spreadDb < 4.0) {
                val sp = String.format(Locale.US, "%.1f", est.spreadDb)
                sb.append("\n" + L.t("Weak contrast ($sp dB): direction unreliable. The device may be far away, or the signal is bouncing off walls.", "訊號差異很小（$sp dB）：方向不可靠。裝置可能很遠，或訊號被牆壁反射。"))
            }
        } else if (finder.running && finder.samples > 0) sb.append("\n" + L.t("Collecting… keep turning.", "收集中… 請繼續轉。"))
        else if (finder.running) sb.append("\n" + L.t("Waiting for the device's advertisements (is it still on and in range?).", "等待裝置廣播中（它還開著、在範圍內嗎？）。"))
        if (finder.running && finder.compassAccuracy <= 1) sb.append("\n" + L.t("Compass needs calibration: wave the phone in a figure-8, away from metal and magnets.", "羅盤需要校正：請在遠離金屬與磁鐵處把手機畫 8 字。"))
        finder.error?.let { sb.append("\n").append(it) }
        finderStatus.text = sb.toString()
        beeper.enabled = ::cbBeep.isInitialized && cbBeep.isChecked
        beeper.update(if (finder.running && android.os.SystemClock.uptimeMillis() - finder.lastPacketAt < 2500) finder.liveRssi else null)
    }

    private fun startRadar() {
        btNums.clear()
        if (!cbBle.isChecked && !cbClassic.isChecked) { Toast.makeText(this, L.t("Select BLE and/or Classic", "請選擇 BLE 和／或傳統藍牙"), Toast.LENGTH_SHORT).show(); return }
        val err = scanner.start(if (cbBle.isChecked && cbClassic.isChecked) 48_000L else 30_000L, cbBle.isChecked, cbClassic.isChecked, envN[envIdx])
        if (err != null) { radarStatus.text = err; addHistory("BT_RADAR", err); return }
        radar.setScanning(true); radar.setBlips(emptyList())
        radarStart.isEnabled = false; radarStop.isEnabled = true; cbBle.isEnabled = false; cbClassic.isEnabled = false
        addHistory("BT_RADAR", "Started (BLE=${cbBle.isChecked}, Classic=${cbClassic.isChecked}, ${envName[envIdx]})")
        refreshRadar()
    }

    private fun stopRadar(notify: Boolean) {
        scanner.stop()
        if (notify) onRadarFinished(L.t("Stopped", "已停止"))
    }

    private fun onRadarFinished(why: String) {
        radar.setScanning(false)
        radarStart.isEnabled = true; radarStop.isEnabled = false; cbBle.isEnabled = true; cbClassic.isEnabled = true
        refreshRadar()
        val s = scanner.summary()
        radarStatus.text = "$why · $s\n${scanner.raw()}" + (scanner.lastError?.let { "\n$it" } ?: "") +
            (if (scanner.devices.isEmpty()) L.t("\nNo devices found. Checklist:\n", "\n找不到裝置。請檢查：\n") + diagnostics() + L.t("\nMany phones and accessories do not advertise unless pairing/finding mode is on, and Classic discovery only lists devices set to “discoverable”. Try near earbuds, a watch, a TV remote or an AirTag-type tag.", "\n許多手機和配件除非開啟配對／尋找模式，否則不會廣播；傳統藍牙搜尋也只會列出設為「可被探索」的裝置。請試著靠近耳機、手錶、電視遙控器或 AirTag 類型的追蹤器。") else "")
        currentSnapshot.clear()
        scanner.devices.values.forEach { currentSnapshot.add((if (it.classic) "BT_CLASSIC" else "BLE") + "|${it.name ?: "Unknown"}|${it.address}") }
        deviceOutput.text = L.t("Bluetooth radar: $s\nUse Save snapshot to compare later (random BLE addresses change over time).", "藍牙雷達：$s\n之後可用「儲存快照」來比較（隨機 BLE 位址會隨時間改變）。")
        addHistory("BT_RADAR", s)
    }

    private fun knownLabel(addr: String): String {
        val labels = try { JSONObject(prefs.getString("device_labels", "{}")) } catch (_: Exception) { JSONObject() }
        val v = labels.optJSONObject(addr) ?: return ""
        val t = v.optString("label").ifBlank { v.optString("name") }
        return if (t.isBlank()) "" else " [$t]"
    }

    private val alertRed = Color.rgb(255, 77, 109)
    private val alerted = HashSet<String>()
    private fun known(): JSONObject {
        val raw = prefs.getString("device_labels", "{}") ?: "{}"
        if (raw != knownRaw) { knownRaw = raw; knownJson = try { JSONObject(raw) } catch (_: Exception) { JSONObject() } }
        return knownJson
    }
    private fun isKnown(id: String): Boolean { val k = known(); return k.has(id) || k.has(id.uppercase()) || k.has(id.lowercase()) }
    private fun markKnown(id: String, name: String) {
        val labels = JSONObject(known().toString())
        labels.put(id, JSONObject().put("name", name).put("label", "known").put("updated", nowStamp()))
        prefs.edit().putString("device_labels", labels.toString()).apply()
        addHistory("DEVICE_LABEL", "Marked known: $id")
    }
    /** Camera-like name, or (Classic only, public address) a camera-maker MAC prefix. Null for devices the user marked as known. */
    private fun suspectOf(d: BtScanner.Dev): String? {
        if (isKnown(d.address)) return null
        SuspectDetector.matchName(d.name)?.let { return it }
        return if (d.classic) OuiDb.vendorOf(d.address, false)?.let { L.t("maker ", "廠商 ") + it } else null
    }
    private fun suspectAp(a: WifiScanner.Ap): String? {
        if (isKnown(a.bssid)) return null
        SuspectDetector.matchName(a.ssid)?.let { return it }
        return OuiDb.vendorOf(a.bssid, true)?.let { L.t("maker ", "廠商 ") + it }
    }

    /** Red + bold for every list item whose first line starts with "⚠ " (continuation lines start with spaces). */
    private fun redizeLines(text: String): CharSequence {
        val sb = android.text.SpannableStringBuilder()
        var bad = false
        text.split("\n").forEachIndexed { i, line ->
            if (i > 0) sb.append("\n")
            if (line.startsWith("⚠ ")) bad = true else if (!line.startsWith(" ")) bad = false
            val s = sb.length
            sb.append(line)
            if (bad) {
                sb.setSpan(android.text.style.ForegroundColorSpan(alertRed), s, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), s, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }

    /** Red banner under a radar; also writes each newly seen suspicious device to the history once. */
    private fun updateAlert(tv: TextView, hits: List<String>) {
        if (hits.isEmpty()) { tv.visibility = View.GONE; return }
        tv.visibility = View.VISIBLE
        tv.text = L.t("⚠ ${hits.size} suspicious name(s)\nA name match is only a hint, check physically.", "⚠ 發現 ${hits.size} 個可疑名稱（疑似攝影機／錄音器）\n名稱符合只是線索，請現場確認。")
        for (h in hits) if (alerted.add(h)) addHistory("SUSPECT", "Camera/recorder-like name: $h")
    }

    private fun clusterRadios(list: List<WifiScanner.Ap>): List<List<WifiScanner.Ap>> = RadioCluster.cluster(list) { wifiScanner.distanceM(it) }
    private fun clusterKey(g: List<WifiScanner.Ap>): String = RadioCluster.key(g)

    /** Well-mixed 0..359 angle from an id (String.hashCode alone clusters, which made blips pile up on one side). */
    private fun angleOf(id: String): Float {
        var h = id.hashCode()
        h = h xor (h ushr 16); h *= -0x7a143595
        h = h xor (h ushr 13); h *= -0x3d4d51cb
        h = h xor (h ushr 16)
        return ((h and 0x7fffffff) % 360).toFloat()
    }

    /** Number of a device: given on first sight, never changes during a scan, so '#7' on the radar is row '#7' in the list. */
    private fun numOf(m: HashMap<String, Int>, id: String): Int = m.getOrPut(id) { m.size + 1 }

    private fun refreshRadar() {
        if (!::radar.isInitialized) return
        val list = scanner.list(6).sortedByDescending { suspectOf(it) != null }    // at most 6 anonymous random-address LE devices (mostly Apple); named / Classic devices always shown
        radar.setBlips(list.mapNotNull { d ->
            scanner.distanceM(d)?.let { RadarView.Blip(d.address, angleOf(d.address), it, if (suspectOf(d) != null) 5 else if (d.le && d.classic) 2 else if (d.classic) 1 else 0, false, scanner.ageFrac(d), numOf(btNums, d.address)) }
        })
        radarAge.text = scanner.updatedText()
        updateAlert(radarAlert, list.filter { suspectOf(it) != null }.map { "${it.name} ${it.address}" })
        if (scanner.scanning) radarStatus.text = "Scanning (${scanner.phase()})… ${scanner.remainingSec()} s · ${scanner.summary()}\n${scanner.raw()}" + (if (scanner.hiddenAnon > 0) L.t("\n+${scanner.hiddenAnon} anonymous random-address devices not listed", "\n另有 ${scanner.hiddenAnon} 個匿名隨機位址裝置未列出") else "") + (scanner.lastError?.let { "\n$it" } ?: "")
        radarList.text = if (list.isEmpty()) "" else redizeLines((if (::cbSortBt.isInitialized && cbSortBt.isChecked) list.sortedBy { numOf(btNums, it.address) } else list).take(30).joinToString("\n") { d ->
            val sus = suspectOf(d) != null
            val kind = if (d.le && d.classic) "LE+BR" else if (d.le) "LE   " else "BR   "
            val dist = scanner.distanceM(d)?.let { if (it > rangeM[rangeIdx]) ">${rangeM[rangeIdx].toInt()} m " else String.format(Locale.US, "%4.1f m", it) } ?: "  ?   "
            val rssi = if (d.hasRssi()) "${d.rssi()} dBm" else "-- dBm"
            val extra = listOfNotNull(d.vendor, d.cls).joinToString("/")
            "${if (sus) "⚠ " else ""}${"#${numOf(btNums, d.address)}".padEnd(4)}$kind $dist ${rssi.padStart(8)}  ${d.name ?: "Unknown"}${knownLabel(d.address)}\n         ${d.address}${if (extra.isNotEmpty()) "  ($extra)" else ""}${if (sus) L.t("\n         ⚠ looks like a camera/recorder: ${suspectOf(d)}", "\n         ⚠ 疑似攝影機／錄音器：${suspectOf(d)}") else ""}"
        })
    }

    private fun nowStamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun loadHistory() {
        historyItems.clear()
        val a = try { JSONArray(prefs.getString("history", "[]")) } catch (_: Exception) { JSONArray() }
        for (i in 0 until a.length()) historyItems.add(a.optString(i))
    }

    private fun persistHistory() {
        val a = JSONArray()
        historyItems.takeLast(500).forEach { a.put(it) }
        prefs.edit().putString("history", a.toString()).apply()
    }

    private fun addHistory(type: String, detail: String) {
        val line = "${nowStamp()} | ${type.replace('|','/')} | ${detail.replace('\n',' ').replace('|','/')}"
        historyItems.add(line)
        while (historyItems.size > 500) historyItems.removeAt(0)
        persistHistory()
        if (::historyOutput.isInitialized) refreshHistory()
    }

    private fun refreshHistory() {
        if (!::historyOutput.isInitialized) return
        val q = if (::historySearch.isInitialized) historySearch.text.toString().trim().lowercase(Locale.ROOT) else ""
        val filtered = historyItems.asReversed().filter { q.isBlank() || it.lowercase(Locale.ROOT).contains(q) }.take(80)
        historyOutput.text = if (filtered.isEmpty()) L.t("No matching events.", "沒有符合的事件。") else filtered.joinToString("\n")
    }

    private fun scanWifiNetworks() {
        currentSnapshot.clear()
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val enabled = wm.isWifiEnabled
            currentSnapshot.add("WIFI_STATE|${if (enabled) "enabled" else "disabled"}")
            if (!enabled) {
                deviceOutput.text = L.t("Wi-Fi is disabled. Enable it in Android settings, then scan again.", "Wi-Fi 已關閉。請先在 Android 設定開啟，再重新掃描。")
                addHistory("WIFI", "Wi-Fi disabled")
                return
            }
            @Suppress("DEPRECATION")
            val started = try { wm.startScan() } catch (_: SecurityException) { false }
            @Suppress("DEPRECATION")
            val results = wm.scanResults
            if (results.isNullOrEmpty()) {
                deviceOutput.text = L.t("No Wi-Fi scan results available. Android may require location/nearby Wi-Fi permissions, Location services, or a fresh scan. This is not evidence that no devices are present.", "沒有 Wi-Fi 掃描結果。Android 可能需要定位／附近 Wi-Fi 權限、定位服務，或需要重新掃描。這不代表附近沒有裝置。")
                addHistory("WIFI", "Scan returned no results; startScan=$started")
                return
            }
            results.forEach { r ->
                val row = "WIFI|${r.SSID.ifBlank { "<hidden SSID>" }}|${r.BSSID ?: "unknown"}|${r.frequency}MHz|RSSI ${r.level}dBm"
                currentSnapshot.add(row)
            }
            deviceOutput.text = L.t("Visible Wi-Fi networks: ${results.size}\n", "可見的 Wi-Fi 網路：${results.size}\n") + results.take(30).withIndex().joinToString("\n") { (i, r) -> "${(i + 1).toString().padStart(2)}. ${r.SSID.ifBlank { "<hidden>" }}  ${r.BSSID ?: ""}  ${r.level} dBm  ${r.frequency} MHz" }
            addHistory("WIFI_SCAN", "${results.size} visible networks; startScan=$started")
        } catch (e: SecurityException) {
            deviceOutput.text = L.t("Wi-Fi scan permission unavailable. Grant the permission in Android settings (App info → Permissions) and try again.", "無法使用 Wi-Fi 掃描權限。請到 Android 設定（應用程式資訊 → 權限）允許後再試一次。")
            addHistory("WIFI_ERROR", "Permission unavailable")
        } catch (e: Exception) {
            deviceOutput.text = L.t("Wi-Fi scan unavailable: ${e.message ?: e.javaClass.simpleName}", "無法使用 Wi-Fi 掃描：${e.message ?: e.javaClass.simpleName}")
            addHistory("WIFI_ERROR", e.message ?: e.javaClass.simpleName)
        }
    }

    private fun showPairedBluetooth() {
        currentSnapshot.clear()
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) { deviceOutput.text = L.t("Bluetooth is not supported on this device.", "此裝置不支援藍牙。"); addHistory("BT", "Not supported"); return }
            if (!adapter.isEnabled) { deviceOutput.text = L.t("Bluetooth is disabled. Enable it in Android settings, then try again.", "藍牙已關閉。請先在 Android 設定開啟，再試一次。"); addHistory("BT", "Bluetooth disabled"); return }
            val bonded = adapter.bondedDevices ?: emptySet()
            if (bonded.isEmpty()) {
                deviceOutput.text = L.t("No paired Bluetooth devices are available to this app. This list is paired devices only, not a scan of every nearby device.", "此 App 沒有可用的已配對藍牙裝置。這個清單只列出已配對的裝置，不是掃描所有附近裝置。")
                addHistory("BT_SCAN", "0 paired devices")
                return
            }
            bonded.forEach { d -> currentSnapshot.add("BT_PAIRED|${d.name ?: "Unknown"}|${d.address}") }
            deviceOutput.text = L.t("Paired Bluetooth devices: ${bonded.size}\n", "已配對的藍牙裝置：${bonded.size}\n") + bonded.withIndex().joinToString("\n") { (i, b) -> "${(i + 1).toString().padStart(2)}. ${b.name ?: "Unknown"}  ${b.address}" } + L.t("\n\nNote: paired devices are not the same as all nearby devices.", "\n\n注意：已配對裝置不等於所有附近裝置。")
            addHistory("BT_SCAN", "${bonded.size} paired devices listed")
        } catch (e: SecurityException) {
            deviceOutput.text = L.t("Bluetooth permission unavailable. Grant the Nearby devices permission in Android settings and try again.", "無法使用藍牙權限。請在 Android 設定允許「附近裝置」權限後再試一次。")
            addHistory("BT_ERROR", "Permission unavailable")
        } catch (e: Exception) {
            deviceOutput.text = L.t("Bluetooth listing unavailable: ${e.message ?: e.javaClass.simpleName}", "無法列出藍牙裝置：${e.message ?: e.javaClass.simpleName}")
            addHistory("BT_ERROR", e.message ?: e.javaClass.simpleName)
        }
    }

    private fun saveSnapshot() {
        if (currentSnapshot.isEmpty()) { Toast.makeText(this, L.t("Run a Wi-Fi or Bluetooth check first", "請先執行 Wi-Fi 或藍牙檢查"), Toast.LENGTH_SHORT).show(); return }
        val snapshots = try { JSONArray(prefs.getString("snapshots", "[]")) } catch (_: Exception) { JSONArray() }
        snapshots.put(JSONObject().put("time", nowStamp()).put("items", JSONArray(currentSnapshot)))
        val trimmed = JSONArray()
        val from = (snapshots.length() - 30).coerceAtLeast(0)
        for (i in from until snapshots.length()) trimmed.put(snapshots.get(i))
        prefs.edit().putString("snapshots", trimmed.toString()).apply()
        snapshotCountRefresh(trimmed.length())
        addHistory("SNAPSHOT", "Saved snapshot with ${currentSnapshot.size} entries")
        Toast.makeText(this, L.t("Snapshot saved", "快照已儲存"), Toast.LENGTH_SHORT).show()
    }

    private fun snapshotCountRefresh(n: Int) {
        prefs.edit().putInt("snapshot_count", n).apply()
        if (::snapshotSummary.isInitialized) snapshotSummary.text = L.t("Snapshots saved: $n (maximum 30)", "已儲存快照：$n（上限 30）")
    }

    private fun compareSnapshots() {
        val a = try { JSONArray(prefs.getString("snapshots", "[]")) } catch (_: Exception) { JSONArray() }
        if (a.length() < 2) { snapshotSummary.text = L.t("Save at least two snapshots to compare.", "至少儲存兩份快照才能比較。"); return }
        val old = a.getJSONObject(a.length() - 2)
        val newer = a.getJSONObject(a.length() - 1)
        val oldSet = (0 until old.getJSONArray("items").length()).map { old.getJSONArray("items").getString(it) }.toSet()
        val newSet = (0 until newer.getJSONArray("items").length()).map { newer.getJSONArray("items").getString(it) }.toSet()
        val added = newSet - oldSet
        val removed = oldSet - newSet
        snapshotSummary.text = L.t("Compared ${old.optString("time")} → ${newer.optString("time")}\nNew entries: ${added.size}; no longer visible: ${removed.size}\n", "比較 ${old.optString("time")} → ${newer.optString("time")}\n新增項目：${added.size}；不再可見：${removed.size}\n") + (if (added.isEmpty()) L.t("No new entries.", "沒有新增項目。") else L.t("New:\n", "新增：\n") + added.take(15).joinToString("\n")) + (if (removed.isEmpty()) L.t("\nNo entries disappeared.", "\n沒有項目消失。") else L.t("\nNo longer visible:\n", "\n不再可見：\n") + removed.take(15).joinToString("\n")) + L.t("\nChanges may reflect normal radio/network conditions; not proof of a threat.", "\n變化可能只是正常的無線電／網路狀況，不代表有威脅。")
        addHistory("COMPARE", "${added.size} new; ${removed.size} no longer visible")
    }

    private fun saveDeviceLabel() {
        val id = deviceIdInput.text.toString().trim()
        val name = deviceNameInput.text.toString().trim()
        val tag = deviceLabelInput.text.toString().trim()
        if (id.isBlank() || (name.isBlank() && tag.isBlank())) { Toast.makeText(this, L.t("Enter a device ID and a name or label", "請輸入裝置 ID 與名稱或標籤"), Toast.LENGTH_SHORT).show(); return }
        val labels = try { JSONObject(prefs.getString("device_labels", "{}")) } catch (_: Exception) { JSONObject() }
        labels.put(id, JSONObject().put("name", name).put("label", tag).put("updated", nowStamp()))
        prefs.edit().putString("device_labels", labels.toString()).apply()
        deviceIdInput.text.clear(); deviceNameInput.text.clear(); deviceLabelInput.text.clear()
        refreshKnownDevices()
        addHistory("DEVICE_LABEL", "Updated label for $id")
        Toast.makeText(this, L.t("Device note saved locally", "裝置備註已儲存在本機"), Toast.LENGTH_SHORT).show()
    }

    private fun refreshKnownDevices() {
        if (!::knownDevicesView.isInitialized) return
        val labels = try { JSONObject(prefs.getString("device_labels", "{}")) } catch (_: Exception) { JSONObject() }
        val lines = labels.keys().asSequence().toList().sorted().take(50).map { id ->
            val v = labels.optJSONObject(id) ?: JSONObject()
            "• $id — ${v.optString("name")} [${v.optString("label")}]"
        }
        knownDevicesView.text = if (lines.isEmpty()) L.t("No known-device labels saved.", "尚未儲存已知裝置標籤。") else lines.joinToString("\n")
    }

    private fun buildCsv(): String {
        val out = StringBuilder("timestamp,type,detail\r\n")
        historyItems.forEach { line ->
            val parts = line.split(" | ", limit = 3)
            val cols = listOf(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
            out.append(cols.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" }).append("\r\n")
        }
        return out.toString()
    }

    private fun confirmClearHistory() {
        android.app.AlertDialog.Builder(this)
            .setTitle(L.t("Clear local event history?", "要清除本機事件紀錄嗎？"))
            .setMessage(L.t("This removes saved event history from this app. Device labels and scan snapshots are kept.", "這會移除此 App 儲存的事件紀錄。裝置標籤與掃描快照會保留。"))
            .setNegativeButton(L.t("Cancel", "取消"), null)
            .setPositiveButton(L.t("Clear", "清除")) { _, _ -> historyItems.clear(); persistHistory(); refreshHistory(); Toast.makeText(this, L.t("History cleared", "紀錄已清除"), Toast.LENGTH_SHORT).show() }
            .show()
    }

    private fun startShield() {
        if (running) return
        addHistory("AUDIO", "Acoustic monitor and masker starting; mode=${modeSpinner.selectedItemPosition}")
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10); return
        }
        val minRec = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minRec <= 0) { status.text = L.t("●  MICROPHONE UNAVAILABLE", "●  麥克風無法使用"); return }
        val recBuffer = max(minRec, fftSize * 2)
        recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recBuffer)
        mode = modeSpinner.selectedItemPosition
        ambientKind = ambientSpinner.selectedItemPosition
        if (mode == 3 && wavData == null) { status.text = L.t("●  CHOOSE A WAV FILE FIRST", "●  請先選擇 WAV 檔"); return }
        outRate = 48000
        val minOut = AudioTrack.getMinBufferSize(outRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minOut <= 0) { recorder?.release(); recorder = null; status.text = L.t("●  AUDIO OUTPUT UNAVAILABLE", "●  音訊輸出無法使用"); return }
        track = AudioTrack(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(), AudioFormat.Builder().setSampleRate(outRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(), max(minOut, fftSize * 4), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
        if (track?.state != AudioTrack.STATE_INITIALIZED) {
            recorder?.release(); recorder = null; track?.release(); track = null
            status.text = L.t("●  AUDIO OUTPUT UNAVAILABLE", "●  音訊輸出無法使用"); return
        }
        try { recorder!!.startRecording(); track!!.play() } catch (e: Exception) {
            status.text = L.t("●  AUDIO START FAILED", "●  音訊啟動失敗"); recorder?.release(); recorder = null; track?.release(); track = null; return
        }
        running = true; status.text = L.t("●  MONITORING ACTIVE", "●  監測中"); status.setTextColor(cyan)
        startButton.isEnabled = false; stopButton.isEnabled = true; modeSpinner.isEnabled = false; ambientSpinner.isEnabled = false
        worker = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val input = ShortArray(fftSize); val output = ShortArray(fftSize * outRate / sampleRate)
            val real = DoubleArray(fftSize); val imag = DoubleArray(fftSize)
            val masker = Masker(outRate)
            val babble = if (mode == 1) Babble(outRate) else null
            val ambient = if (mode == 2) Ambient(outRate, ambientKind) else null
            var fade = 0.0                    // 3 s fade-in so the masker never starts abruptly
            val wav = wavData; var wavIdx = 0
            var smoothSpeech = 0.0; var suspiciousFrames = 0; var quietFrames = 0
            var floorDb = Double.NaN          // slowly-tracked noise floor of the 300-3400 Hz band
            var boostDb = 0.0                 // adaptive extra masking, 0..+6 dB
            var levelDb = -36.0
            while (running) {
                val n = recorder?.read(input, 0, input.size, AudioRecord.READ_BLOCKING) ?: 0
                if (n <= 0) continue
                for (i in 0 until fftSize) { val x = if (i < n) input[i] / 32768.0 else 0.0; val w = 0.5 - 0.5 * cos(2.0 * PI * i / (fftSize - 1)); real[i] = x * w; imag[i] = 0.0 }
                fft(real, imag)
                var voice = 1e-12; var wide = 1e-12; var peakBin = 0; var peakPower = 0.0
                val bins = IntArray(24)
                for (k in 1 until fftSize / 2) {
                    val p = real[k] * real[k] + imag[k] * imag[k]
                    val hz = k.toDouble() * sampleRate / fftSize
                    if (hz in 80.0..8000.0) wide += p
                    if (hz in 300.0..3400.0) voice += p
                    if (hz in 1000.0..6000.0 && p > peakPower) { peakPower = p; peakBin = k }
                    val idx = ((ln(k.toDouble() + 1) / ln((fftSize / 2).toDouble())) * bins.size).toInt().coerceIn(0, bins.lastIndex)
                    bins[idx] = max(bins[idx], ((10 * log10(p + 1e-10) + 20) * 100.0 / 80.0).toInt().coerceIn(2, 100))
                }
                var rms = 0.0
                for (i in 0 until n.coerceAtMost(input.size)) { val v = input[i] / 32768.0; rms += v * v }
                rms = sqrt(rms / n.coerceAtLeast(1))

                // Voice-band level relative to the tracked floor. A steady masker (or fan, AC) is absorbed
                // into the floor, so the masker does not trigger itself (no feedback loop).
                val voiceDb = 10 * log10(voice)
                val ratio = (voice / wide).coerceIn(0.0, 1.0)
                floorDb = when {
                    floorDb.isNaN() -> voiceDb
                    voiceDb < floorDb -> 0.8 * floorDb + 0.2 * voiceDb   // fall fast
                    else -> floorDb + 0.02                                // rise ~1 dB/s
                }
                val excessDb = voiceDb - floorDb
                smoothSpeech = 0.85 * smoothSpeech + 0.15 * (excessDb / 20.0).coerceIn(0.0, 1.0)

                // Heuristic only: voice-band burst above floor + voice-like spectral shape + minimum level.
                val speechLikeFrame = excessDb >= 9.0 && ratio >= 0.45 && rms >= 0.008
                if (speechLikeFrame) { suspiciousFrames++; quietFrames = 0 }
                else { suspiciousFrames = max(0, suspiciousFrames - 1); quietFrames++ }
                val shouldAlert = suspiciousFrames >= 40 && !alertActive &&
                    (System.currentTimeMillis() - lastAlertAtMs > 15000L)
                if (shouldAlert) {
                    alertActive = true
                    lastAlertAtMs = System.currentTimeMillis()
                    runOnUiThread { showSuspiciousRecordingAlert(smoothSpeech) }
                } else if (alertActive && quietFrames >= 100) {
                    alertActive = false
                    runOnUiThread {
                        if (running) {
                            status.text = L.t("●  MONITORING ACTIVE", "●  監測中")
                            status.setTextColor(cyan)
                            alertDetails.text = L.t("Speech-like sound has dropped. Still cannot confirm whether a nearby device is recording.", "語音型聲音已降低。仍無法確認附近裝置是否正在錄音。")
                            alertDetails.setTextColor(muted)
                        }
                    }
                }

                // Masker: continuous speech-shaped broadband noise (NOT gated by detection),
                // user level + up to +6 dB while voice is present. Hard cap -6 dBFS.
                val baseDb = -36.0 + intensity.progress
                val amb = mode == 2                                   // ambient: gentler, slower boost
                val targetBoost = if (speechLikeFrame) (if (amb) 3.0 else 6.0) else 0.0
                val atk = if (amb) 0.08 else 1.0; val rel = if (amb) 0.02 else 0.2
                boostDb += if (targetBoost > boostDb) min(atk, targetBoost - boostDb) else -min(rel, boostDb - targetBoost)
                levelDb = (baseDb + boostDb).coerceAtMost(-6.0)
                val amp = if (maskingEnabled) 10.0.pow(levelDb / 20.0) else 0.0
                for (i in output.indices) {
                    val sig = when (mode) {
                        0 -> masker.next()
                        1 -> babble!!.next() * 0.85 + masker.next() * 0.35
                        2 -> ambient!!.next()
                        else -> { val v = wav!![wavIdx] / 32768.0 / wavRms; wavIdx = (wavIdx + 1) % wav.size; v }
                    }
                    if (fade < 1.0) fade = min(1.0, fade + 1.0 / (outRate * 3.0))
                    val s = tanh(sig * amp * fade) * Short.MAX_VALUE
                    output[i] = s.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                track?.write(output, 0, output.size, AudioTrack.WRITE_BLOCKING)

                val pct = (smoothSpeech * 100).roundToInt().coerceIn(0, 100)
                val mpct = if (maskingEnabled) (((levelDb + 36.0) / 30.0) * 100).roundToInt().coerceIn(0, 100) else 0
                val peakHz = (peakBin * sampleRate / fftSize.toDouble()).roundToInt()
                val lvl = levelDb.roundToInt()
                runOnUiThread {
                    speechEnergy.progress = pct
                    maskEnergy.progress = mpct
                    bandText.text = L.t("Voice +${excessDb.coerceAtLeast(0.0).roundToInt()} dB over floor     Mask: ${if (maskingEnabled) "$lvl dBFS" else "off"}     Peak: $peakHz Hz", "人聲高出底噪 +${excessDb.coerceAtLeast(0.0).roundToInt()} dB     遮蔽：${if (maskingEnabled) "$lvl dBFS" else "關"}     峰值：$peakHz Hz")
                    score.text = L.t("Acoustic activity index  $pct%  •  heuristic only", "聲學活動指數  $pct%  •  僅為推測")
                    spectrum.setLevels(bins)
                }
            }
        }.also { it.start() }
    }

    private fun showSuspiciousRecordingAlert(speechRatio: Double) {
        if (!running) return
        status.text = L.t("⚠  Possible recording environment", "⚠  疑似錄音環境")
        status.setTextColor(Color.rgb(255, 190, 72))
        alertDetails.text = L.t("Sustained speech-like sound detected (${(speechRatio * 100).roundToInt()}%). This is not evidence that a nearby phone is recording.", "偵測到持續語音型聲音（${(speechRatio * 100).roundToInt()}%）。這不是附近手機正在錄音的證據。")
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
        Toast.makeText(this, L.t("Alert: sustained speech-like sound; cannot confirm any device is recording", "警示：持續語音型聲音；無法確認是否有裝置錄音"), Toast.LENGTH_LONG).show()
    }

    private fun stopShield() {
        val wasRunning = running
        running = false
        if (wasRunning) addHistory("AUDIO", "Acoustic monitor and masker stopped")
        try { recorder?.stop() } catch (_: Exception) {}
        try { track?.stop() } catch (_: Exception) {}
        worker?.join(700); worker = null
        recorder?.release(); recorder = null; track?.release(); track = null
        alertActive = false
        if (::status.isInitialized) { status.text = L.t("●  MONITORING OFF", "●  監測已關閉"); status.setTextColor(Color.rgb(255, 111, 132)) }
        if (::alertDetails.isInitialized) { alertDetails.text = L.t("Monitoring stopped. This tool cannot confirm whether a nearby device is recording.", "監測已停止。此工具不能確認附近裝置是否正在錄音。"); alertDetails.setTextColor(muted) }
        if (::speechEnergy.isInitialized) speechEnergy.progress = 0
        if (::maskEnergy.isInitialized) maskEnergy.progress = 0
        if (::bandText.isInitialized) bandText.text = L.t("Speech band: —     Peak: — Hz", "語音頻帶：—     峰值：— Hz")
        if (::score.isInitialized) score.text = L.t("Acoustic activity score  —", "聲學活動分數  —")
        if (::spectrum.isInitialized) spectrum.setLevels(IntArray(24))
        if (::startButton.isInitialized) startButton.isEnabled = true
        if (::modeSpinner.isInitialized) modeSpinner.isEnabled = true
        if (::ambientSpinner.isInitialized) ambientSpinner.isEnabled = true
        if (::stopButton.isInitialized) stopButton.isEnabled = false
    }

    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size; var j = 0
        for (i in 1 until n) { var bit = n shr 1; while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }; j = j xor bit; if (i < j) { val tr = real[i]; real[i] = real[j]; real[j] = tr; val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti } }
        var len = 2
        while (len <= n) { val ang = -2.0 * PI / len; val wLenR = cos(ang); val wLenI = sin(ang); var i = 0
            while (i < n) { var wr = 1.0; var wi = 0.0; for (k in 0 until len / 2) { val uR = real[i+k]; val uI = imag[i+k]; val vR = real[i+k+len/2]*wr - imag[i+k+len/2]*wi; val vI = real[i+k+len/2]*wi + imag[i+k+len/2]*wr; real[i+k] = uR+vR; imag[i+k] = uI+vI; real[i+k+len/2] = uR-vR; imag[i+k+len/2] = uI-vI; val nr = wr*wLenR-wi*wLenI; wi = wr*wLenI+wi*wLenR; wr = nr }; i += len }; len = len shl 1 }
    }
    override fun onStop() { if (finder.running) stopFinder(); if (scanner.scanning) stopRadar(true); if (wifiScanner.scanning) stopWifiRadar(true); super.onStop() }
    override fun onDestroy() { finder.stop(); beeper.release(); room?.cancel(); lanScanner?.cancel(); scanner.stop(); wifiScanner.stop(); stopShield(); super.onDestroy() }


    /**
     * Speech-shaped broadband noise: white -> pink (Kellet) -> HPF 150 Hz -> LPF 6.5 kHz (RBJ biquads).
     * Output is normalised to ~unit RMS; multiply by the desired linear gain.
     * Replaces the old 2.2 kHz "sine stack" whose upper partials aliased into 4 fixed tones.
     */
    private class Masker(sr: Int) {
        private var seed = 0x2545F491L xor System.nanoTime()
        private var b0 = 0.0; private var b1 = 0.0; private var b2 = 0.0
        private val hp = Biquad(true, 150.0, sr); private val lp = Biquad(false, 6500.0, sr)
        private fun white(): Double {
            seed = seed xor (seed shl 13); seed = seed xor (seed ushr 7); seed = seed xor (seed shl 17)
            return ((seed ushr 11).toDouble() / (1L shl 53).toDouble()) * 2.0 - 1.0
        }
        fun next(): Double {
            val w = white()
            b0 = 0.99765 * b0 + w * 0.0990460; b1 = 0.96300 * b1 + w * 0.2965164; b2 = 0.57000 * b2 + w * 1.0526913
            return lp.process(hp.process(b0 + b1 + b2 + w * 0.1848)) / 1.126
        }
    }

    private class Biquad(highPass: Boolean, f0: Double, sr: Int) {
        private val b0: Double; private val b1: Double; private val b2: Double; private val a1: Double; private val a2: Double
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
        init {
            val w0 = 2.0 * PI * f0 / sr; val c = cos(w0); val al = sin(w0) / (2.0 * 0.7071); val a0 = 1.0 + al
            if (highPass) { b0 = (1 + c) / 2 / a0; b1 = -(1 + c) / a0; b2 = b0 } else { b0 = (1 - c) / 2 / a0; b1 = (1 - c) / a0; b2 = b0 }
            a1 = -2.0 * c / a0; a2 = (1.0 - al) / a0
        }
        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y; return y
        }
    }

    private fun loadWav(uri: android.net.Uri) {
        try {
            val b = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            fun u16(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
            fun u32(o: Int) = u16(o) or (u16(o + 2) shl 16)
            require(b.size > 44 && String(b, 0, 4) == "RIFF" && String(b, 8, 4) == "WAVE") { L.t("Not a WAV file", "不是 WAV 檔") }
            var o = 12; var ch = 0; var sr = 0; var bits = 0; var fmt = 0; var dataOff = -1; var dataLen = 0
            while (o + 8 <= b.size) {
                val id = String(b, o, 4); val len = u32(o + 4)
                if (id == "fmt ") { fmt = u16(o + 8); ch = u16(o + 10); sr = u32(o + 12); bits = u16(o + 22) }
                if (id == "data") { dataOff = o + 8; dataLen = min(len, b.size - dataOff); break }
                o += 8 + len + (len and 1)
            }
            require(fmt == 1 && ch == 1 && bits == 16 && sr == 48000 && dataOff > 0) { L.t("Need 48 kHz mono 16-bit PCM WAV", "需要 48 kHz 單聲道 16 位元 PCM WAV") }
            val n = dataLen / 2
            val s = ShortArray(n) { i -> u16(dataOff + 2 * i).toShort() }
            var ms = 0.0; for (v in s) { val x = v / 32768.0; ms += x * x }
            wavRms = sqrt(ms / n).coerceAtLeast(1e-4); wavData = s
            Toast.makeText(this, L.t("WAV loaded: ${n / 48000.0} s", "已載入 WAV：${n / 48000.0} 秒"), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            wavData = null
            Toast.makeText(this, e.message ?: L.t("Cannot read WAV", "無法讀取 WAV"), Toast.LENGTH_LONG).show()
        }
    }

    /** Running-RMS normaliser so generators have ~unit RMS regardless of sample rate. */
    private class Agc { private var ms = 0.05; fun norm(x: Double): Double { ms += 1e-4 * (x * x - ms); return x / sqrt(ms + 1e-9) } }

    private class Resonator(private val sr: Int) {
        private var a1 = 0.0; private var a2 = 0.0; private var g = 1.0; private var y1 = 0.0; private var y2 = 0.0
        fun set(fc: Double, bw: Double) { val r = exp(-PI * bw / sr); a1 = 2 * r * cos(2 * PI * fc / sr); a2 = -r * r; g = 1 - r }
        fun process(x: Double): Double { val y = g * x + a1 * y1 + a2 * y2; y2 = y1; y1 = y; return y }
    }

    /** One synthetic talker: pulse-train source + 3 parallel formants, random vowels, syllabic envelope. */
    private class BabbleVoice(private val sr: Int, private val rnd: Random) {
        private val vowels = arrayOf(doubleArrayOf(730.0, 1090.0, 2440.0), doubleArrayOf(270.0, 2290.0, 3010.0), doubleArrayOf(300.0, 870.0, 2240.0), doubleArrayOf(530.0, 1840.0, 2480.0), doubleArrayOf(570.0, 840.0, 2410.0))
        private val bw = doubleArrayOf(90.0, 110.0, 140.0); private val wt = doubleArrayOf(1.0, 0.8, 0.6)
        private val res = Array(3) { Resonator(sr) }
        private val f0 = 90.0 + rnd.nextDouble() * 160.0
        private var phase = 0.0; private var vib = rnd.nextDouble() * 6.28
        private var seg = 0; private var pos = 0; private var gap = 0
        fun next(): Double {
            if (pos >= seg + gap) {
                seg = (sr * (0.12 + 0.10 * rnd.nextDouble())).toInt(); pos = 0
                gap = if (rnd.nextDouble() < 0.35) (sr * 0.12 * rnd.nextDouble()).toInt() else 0
                val v = vowels[rnd.nextInt(vowels.size)]; for (k in 0 until 3) res[k].set(v[k], bw[k])
            }
            val inSeg = pos < seg
            vib += 2 * PI * 5.0 / sr
            phase += f0 * (1 + 0.05 * sin(vib)) / sr
            var pulse = 0.0; if (phase >= 1.0) { phase -= 1.0; pulse = 1.0 }
            val src = pulse + 0.03 * (rnd.nextDouble() * 2 - 1)
            var y = 0.0; for (k in 0 until 3) y += wt[k] * res[k].process(src)
            val env = if (inSeg) { val s = sin(PI * pos / seg); s * s } else 0.0
            pos++
            return y * env
        }
    }

    private class Babble(sr: Int) {
        private val rnd = Random(System.nanoTime())
        private val voices = Array(8) { BabbleVoice(sr, rnd) }
        private val agc = Agc()
        init { repeat(sr / 2) { var s = 0.0; for (v in voices) s += v.next(); agc.norm(s) } }  // warm up AGC
        fun next(): Double { var s = 0.0; for (v in voices) s += v.next(); return agc.norm(s) }
    }

    /** Raw Kellet pink noise (about -3 dB/oct). */
    private class PinkNoise { 
        private val rnd = Random(System.nanoTime()); private var b0 = 0.0; private var b1 = 0.0; private var b2 = 0.0
        fun next(): Double { val w = rnd.nextDouble() * 2 - 1
            b0 = 0.99765 * b0 + w * 0.0990460; b1 = 0.96300 * b1 + w * 0.2965164; b2 = 0.57000 * b2 + w * 1.0526913
            return b0 + b1 + b2 + w * 0.1848 }
    }

    /** Smooth random drift in [-1, 1] that picks a new target ~rateHz times per second. */
    private class SlowRandom(sr: Int, rateHz: Double) {
        private val rnd = Random(System.nanoTime()); private val period = (sr / rateHz).toInt().coerceAtLeast(1)
        private var cnt = 0; private var a = rnd.nextDouble() * 2 - 1; private var b = rnd.nextDouble() * 2 - 1
        fun next(): Double {
            if (cnt >= period) { cnt = 0; a = b; b = rnd.nextDouble() * 2 - 1 }
            val t = cnt.toDouble() / period; cnt++; val s = t * t * (3 - 2 * t); return a + (b - a) * s
        }
    }

    /**
     * Low-profile room tone. kind 0 = desk fan (dark broadband + blade-pass tones that wander ~1.5 %),
     * 1 = air-conditioner (rumble + airy whoosh with slow swells), 2 = café murmur (quiet babble + shaped noise).
     * AGC runs BEFORE the slow level drift so the drift is not cancelled.
     */
    private class Ambient(private val sr: Int, private val kind: Int) {
        private val pink = PinkNoise(); private val agc = Agc()
        private val hp = Biquad(true, if (kind == 1) 45.0 else if (kind == 2) 120.0 else 70.0, sr)
        private val lpA = Biquad(false, if (kind == 1) 1800.0 else if (kind == 2) 4500.0 else 2800.0, sr)
        private val lpB = Biquad(false, if (kind == 1) 1800.0 else if (kind == 2) 4500.0 else 2800.0, sr)
        private val rumble = Biquad(false, 120.0, sr)
        private val babble = if (kind == 2) Babble(sr) else null
        private val driftSlow = SlowRandom(sr, if (kind == 1) 0.07 else 0.15); private val driftFast = SlowRandom(sr, 0.4)
        private val rpmDrift = SlowRandom(sr, 0.1)
        private var ph1 = 0.0
        private val bladeHz = 94.0
        init { repeat(sr / 2) { next() } }
        fun next(): Double {
            val p = pink.next()
            var x = lpB.process(lpA.process(hp.process(p)))
            if (kind == 1) x += 0.8 * rumble.process(p)
            x = agc.norm(x)
            var level = 1.0
            when (kind) {
                0 -> {
                    ph1 += 2 * PI * bladeHz * (1 + 0.015 * rpmDrift.next()) / sr; if (ph1 > 2 * PI * 1000) ph1 -= 2 * PI * 1000
                    x += 0.25 * sin(ph1) + 0.12 * sin(2 * ph1 + 0.7) + 0.06 * sin(3 * ph1 + 1.9)
                    level = 1.0 + 0.06 * driftSlow.next()
                }
                1 -> level = 1.0 + 0.15 * driftSlow.next() + 0.05 * driftFast.next()
                else -> { x = 0.5 * x + 0.7 * babble!!.next(); level = 1.0 + 0.10 * driftSlow.next() }
            }
            return x * level
        }
    }

    private inner class SpectrumView : View(this@MainActivity) {
        private val bars = IntArray(24) { 8 }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun setLevels(values: IntArray) { for (i in bars.indices) bars[i] = values.getOrElse(i) { 0 }.coerceIn(2,100); postInvalidate() }
        override fun onDraw(canvas: Canvas) { super.onDraw(canvas); val gap = width * 0.012f; val bw = (width - gap * (bars.size + 1)) / bars.size; val maxH = height * 0.86f
            for (i in bars.indices) { val h = max(dp(3).toFloat(), maxH * bars[i] / 100f); val left = gap + i * (bw + gap); paint.color = when { i < 6 -> Color.rgb(34, 139, 255); i < 12 -> Color.rgb(42, 219, 170); i < 18 -> Color.rgb(255, 180, 67); else -> Color.rgb(255, 79, 111) }; canvas.drawRoundRect(RectF(left, height - h, left + bw, height.toFloat()), bw / 2, bw / 2, paint) }
        }
    }
}
