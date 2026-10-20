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
            Toast.makeText(this, "CSV exported", Toast.LENGTH_SHORT).show()
            addHistory("EXPORT", "CSV export completed")
        } catch (e: Exception) { Toast.makeText(this, "CSV export failed: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
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
                showSettingsDialog("Permission needed", msg, android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                addHistory("PERMISSION", "Denied: " + res.filterValues { !it }.keys.joinToString { it.substringAfterLast('.') })
            }
        }
    }

    /** Runs [action] once the permissions (and, if [needLocation], the system Location switch) are in place; otherwise explains what is missing. */
    private fun withPermission(perms: Array<String>, status: TextView? = null, needLocation: Boolean = false, action: () -> Unit) {
        val go = {
            if (needLocation && !locationOn()) {
                val m = "Location is turned OFF. Android only returns Wi-Fi scan results (and, on Android 11 and below, Bluetooth scan results) while Location is on. The app does not read or store your position."
                status?.text = m
                showSettingsDialog("Turn on Location", m, android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
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
        if (denied.any { it.endsWith("NEARBY_WIFI_DEVICES") }) parts.add("“Nearby devices” (Wi-Fi)")
        if (denied.any { it.endsWith("BLUETOOTH_SCAN") || it.endsWith("BLUETOOTH_CONNECT") }) parts.add("“Nearby devices” (Bluetooth)")
        if (denied.any { it.endsWith("FINE_LOCATION") }) parts.add(if (res["android.permission.ACCESS_COARSE_LOCATION"] == true) "Location set to PRECISE (it is on Approximate)" else "Location (precise)")
        return "Permission denied: " + parts.joinToString(", ").ifEmpty { "required permission" } + ". Open App settings → Permissions, allow it, then try again."
    }

    private fun showSettingsDialog(title: String, msg: String, action: String) {
        try {
            android.app.AlertDialog.Builder(this).setTitle(title).setMessage(msg)
                .setPositiveButton("Open settings") { _, _ ->
                    try { startActivity(android.content.Intent(action).apply { if (action == android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS) data = android.net.Uri.parse("package:$packageName") }) } catch (_: Exception) {}
                }.setNegativeButton("Cancel", null).show()
        } catch (_: Exception) {}
    }

    /** Plain-language checklist shown when a scan finds nothing. */
    private fun diagnostics(): String {
        fun g(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        fun ok(b: Boolean) = if (b) "OK" else "MISSING"
        val l = ArrayList<String>()
        l.add("Precise location permission: ${ok(g(Manifest.permission.ACCESS_FINE_LOCATION))}")
        l.add("Location switch: ${if (locationOn()) "OK (on)" else "OFF"}")
        if (Build.VERSION.SDK_INT >= 33) l.add("Nearby Wi-Fi devices permission: ${ok(g(Manifest.permission.NEARBY_WIFI_DEVICES))}")
        if (Build.VERSION.SDK_INT >= 31) l.add("Nearby Bluetooth permission: ${ok(g(Manifest.permission.BLUETOOTH_SCAN) && g(Manifest.permission.BLUETOOTH_CONNECT))}")
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        l.add("Wi-Fi: ${if (wm?.isWifiEnabled == true) "OK (on)" else "OFF"}")
        val bt = try { getSystemService(BluetoothManager::class.java)?.adapter } catch (_: Exception) { null }
        l.add("Bluetooth: ${if (bt?.isEnabled == true) "OK (on)" else "OFF"}")
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
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val logo = ImageView(this).apply { setImageResource(R.mipmap.ic_launcher); contentDescription = "Hush Shield icon" }
        header.addView(logo, LinearLayout.LayoutParams(dp(48), dp(48)))
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        titleBox.addView(label("Hush Shield", 20f, white, true))
        titleBox.addView(label("VOICE PRIVACY + NEARBY RADAR  •  v1.1.4", 10f, cyan, true))
        header.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(header)
        addGap(page, 12)
        // ---- tab bar: every feature is one tap away instead of far below the mask controls ----
        val tabNames = arrayOf("Sound Shield", "Radar", "Wi-Fi · Notes", "Tests")
        val boxes = arrayOf<View>(root, radarBox, wifiBox, testBox)
        val tabBtns = ArrayList<Button>()
        fun selectTab(i: Int) {
            boxes.forEachIndexed { k, b -> b.visibility = if (k == i) View.VISIBLE else View.GONE }
            tabBtns.forEachIndexed { k, t -> t.alpha = if (k == i) 1f else 0.55f; t.setTextColor(if (k == i) cyan else white) }
            scroll.scrollTo(0, 0)
        }
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabNames.forEachIndexed { i, n ->
            val b = Button(this).apply { text = n; isAllCaps = false; textSize = 12f; minWidth = 0; minimumWidth = 0; setPadding(dp(2), 0, dp(2), 0); setOnClickListener { selectTab(i) } }
            tabBtns.add(b); tabRow.addView(b, LinearLayout.LayoutParams(0, -2, if (i == 0) 1.5f else 1f).apply { if (i > 0) marginStart = dp(4) })
        }
        page.addView(tabRow)
        addGap(page, 12)
        boxes.forEach { page.addView(it) }

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
        control.addView(label("SOUND SHIELD  •  ADAPTIVE MASKING", 12f, muted, true))
        maskSwitch = Switch(this).apply { text = "Enable low-level masking"; textSize = 15f; setTextColor(white); isChecked = true; buttonTintList = android.content.res.ColorStateList.valueOf(cyan); setOnCheckedChangeListener { _, checked -> maskingEnabled = checked } }
        control.addView(maskSwitch)
        addGap(control, 8)
        control.addView(label("Masking mode", 13f, white))
        val modes = arrayOf("1  Speech-shaped noise", "2  Babble (8 synthetic voices)", "3  Ambient room tone (low-profile)", "4  Custom WAV (adversarial)")
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
                        0 -> "Broadband noise shaped like the long-term speech spectrum (150 Hz–6.5 kHz)."
                        1 -> "Overlapping synthetic syllables from 8 voices; harder for denoisers to separate than steady noise."
                        2 -> "Masks with a believable room sound (fan / air-conditioner / café murmur) that drifts slowly, fades in over 3 s and rises at most 3 dB when voices are detected. Less aggressive than modes 1-2 but far less likely to draw attention."
                        else -> "Plays a looping 48 kHz mono 16-bit WAV (e.g. perturbation from make_adversarial.py). Effective only against the model it was optimised for."
                    }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        control.addView(modeSpinner)
        ambientSpinner = Spinner(this).apply {
            visibility = View.GONE
            val kinds = arrayOf("Desk fan", "Air-conditioner / HVAC", "Café murmur")
            adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_item, kinds) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                    (super.getView(position, convertView, parent) as TextView).apply { setTextColor(white) }
                override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                    (super.getDropDownView(position, convertView, parent) as TextView).apply { setTextColor(white); setBackgroundColor(card); setPadding(dp(12), dp(10), dp(12), dp(10)) }
            }
            setPopupBackgroundDrawable(android.graphics.drawable.ColorDrawable(card))
        }
        control.addView(ambientSpinner)
        wavButton = Button(this).apply { text = "Choose WAV…"; visibility = View.GONE; setOnClickListener { pickWav.launch(arrayOf("audio/*")) } }
        control.addView(wavButton)
        modeNote = label("Broadband noise shaped like the long-term speech spectrum (150 Hz–6.5 kHz).", 11f, muted)
        control.addView(modeNote)
        addGap(control, 6)
        control.addView(label("Masking level  (-36 … -6 dBFS, start low)", 13f, white))
        intensity = SeekBar(this).apply { max = 30; progress = 12; progressTintList = android.content.res.ColorStateList.valueOf(blue); thumbTintList = android.content.res.ColorStateList.valueOf(cyan) }
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
        disclaimer.addView(label("This app analyzes sound captured by this phone's microphone and can play audible masking sound. It cannot confirm whether another phone is recording, detect the recording state from RF/Wi-Fi/Bluetooth, or guarantee prevention of recording or speech recognition. Use at a comfortable volume and with consent.", 12f, muted))
        root.addView(disclaimer)
        addGap(root, 14)
        val radioCard = cardLayout()
        radioCard.addView(label("NEARBY DEVICE AWARENESS", 12f, cyan, true))
        addGap(radioCard, 6)
        radioCard.addView(label("RF / Wi-Fi / Bluetooth presence is not proof of recording. Android apps cannot determine whether a nearby device is recording from its radio signals. Use these shortcuts only to inspect ordinary nearby connectivity; unknown devices may be absent and legitimate devices may appear.", 12f, muted))
        addGap(radioCard, 8)
        val radioButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val wifiButton = Button(this).apply { text = "Wi-Fi settings"; setOnClickListener { try { startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)) } catch (_: Exception) { Toast.makeText(this@MainActivity, "Wi-Fi settings unavailable", Toast.LENGTH_SHORT).show() } } }
        val btButton = Button(this).apply { text = "Bluetooth settings"; setOnClickListener { try { startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (_: Exception) { Toast.makeText(this@MainActivity, "Bluetooth settings unavailable", Toast.LENGTH_SHORT).show() } } }
        radioButtons.addView(wifiButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        radioButtons.addView(btButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        radioCard.addView(radioButtons)
        wifiBox.addView(radioCard)
        addGap(wifiBox, 14)
        buildRadarCard(radarBox)
        buildWifiRadarCard(wifiBox)
        addGap(wifiBox, 14)
        buildEnvironmentNotebook(wifiBox)
        buildDeviceTestChecklist(testBox)
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
        notebook.addView(label("DEVICE AWARENESS & LOCAL RECORDS", 12f, cyan, true))
        addGap(notebook, 6)
        notebook.addView(label("Scan visibility depends on Android version, permissions, Wi-Fi settings and device discoverability. Unknown devices are not proof of recording.", 11f, muted))
        addGap(notebook, 8)
        val scanButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scanButtons.addView(Button(this).apply { text = "Scan Wi-Fi"; setOnClickListener { withPermission(wifiPermissions(), deviceOutput, true) { scanWifiNetworks() } } }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        scanButtons.addView(Button(this).apply { text = "Paired Bluetooth"; setOnClickListener { withPermission(btPermissions(), deviceOutput) { showPairedBluetooth() } } }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        notebook.addView(scanButtons)
        deviceOutput = label("No scan performed in this session.", 12f, white)
        notebook.addView(deviceOutput)
        addGap(notebook, 6)
        val snapButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        snapButtons.addView(Button(this).apply { text = "Save snapshot"; setOnClickListener { saveSnapshot() } }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        snapButtons.addView(Button(this).apply { text = "Compare snapshots"; setOnClickListener { compareSnapshots() } }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        notebook.addView(snapButtons)
        snapshotSummary = label("Snapshots: ${prefs.getInt("snapshot_count", 0)}", 11f, muted)
        notebook.addView(snapshotSummary)
        addGap(notebook, 10)
        notebook.addView(label("KNOWN DEVICE LABELS", 12f, muted, true))
        deviceIdInput = EditText(this).apply { hint = "Device ID / BSSID / Bluetooth address"; setSingleLine(true); setTextColor(white); setHintTextColor(muted) }
        deviceNameInput = EditText(this).apply { hint = "Device name (e.g. living-room TV)"; setSingleLine(true); setTextColor(white); setHintTextColor(muted) }
        deviceLabelInput = EditText(this).apply { hint = "Label (Router / TV / Laptop / known)"; setSingleLine(true); setTextColor(white); setHintTextColor(muted) }
        notebook.addView(deviceIdInput); notebook.addView(deviceNameInput); notebook.addView(deviceLabelInput)
        notebook.addView(Button(this).apply { text = "Save / update known device"; setOnClickListener { saveDeviceLabel() } })
        val knownText = label("Known devices are local notes only; labels do not certify a device is safe.", 11f, muted)
        notebook.addView(knownText)
        val knownOutput = label("", 11f, white)
        notebook.addView(knownOutput)
        // Keep the known-device list on the card and refresh it after edits.
        knownDevicesView = knownOutput
        addGap(notebook, 10)
        notebook.addView(label("EVENT HISTORY", 12f, muted, true))
        historySearch = EditText(this).apply { hint = "Search history (e.g. WIFI, WIFI_RADAR, BT_RADAR, AUDIO)"; setSingleLine(true); setTextColor(white); setHintTextColor(muted) }
        notebook.addView(historySearch)
        notebook.addView(Button(this).apply { text = "Search history"; setOnClickListener { refreshHistory() } })
        historyOutput = label("No saved events yet.", 11f, white)
        notebook.addView(historyOutput)
        val historyButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        historyButtons.addView(Button(this).apply { text = "Export CSV"; setOnClickListener { exportCsv.launch("anti_recording_shield_history.csv") } }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        historyButtons.addView(Button(this).apply { text = "Clear history"; setOnClickListener { confirmClearHistory() } }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        notebook.addView(historyButtons)
        root.addView(notebook)
    }

    private fun buildDeviceTestChecklist(root: LinearLayout) {
        val testCard = cardLayout()
        testCard.addView(label("PIXEL 6a / REAL-DEVICE TEST LOG", 12f, cyan, true))
        addGap(testCard, 6)
        testCard.addView(label("Run these checks on the actual phone. Each button records a manual test result; it does not automatically verify the hardware.", 11f, muted))
        val tests = listOf(
            "Microphone input + audible masking output" to "TEST_AUDIO",
            "Wi-Fi scan permissions and results" to "TEST_WIFI",
            "Bluetooth radar: BLE + Classic scan, distances" to "TEST_BT_RADAR",
            "Wi-Fi radar: AP plot, RSSI distance, Wi-Fi RTT" to "TEST_WIFI_RADAR",
            "Bluetooth paired-device access" to "TEST_BLUETOOTH",
            "Save snapshot, search history and export CSV" to "TEST_DATA"
        )
        tests.forEach { (description, key) ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(label(description, 12f, white), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Button(this).apply { text = "PASS"; setOnClickListener { addHistory(key, "PASS — $description (manual user confirmation)"); Toast.makeText(this@MainActivity, "Pass recorded", Toast.LENGTH_SHORT).show() } })
            row.addView(Button(this).apply { text = "ISSUE"; setOnClickListener { addHistory(key, "ISSUE — $description (manual user report)"); Toast.makeText(this@MainActivity, "Issue recorded", Toast.LENGTH_SHORT).show() } })
            testCard.addView(row)
        }
        root.addView(testCard)
    }

    private lateinit var knownDevicesView: TextView

    // ------------------------------------------------------------------------------------
    // Bluetooth radar: BLE scan + Classic discovery, animated sweep, RSSI-based distance
    // ------------------------------------------------------------------------------------
    private lateinit var radar: RadarView
    private lateinit var radarStatus: TextView
    private lateinit var radarList: TextView
    private lateinit var radarStart: Button
    private lateinit var radarStop: Button
    private lateinit var radarEnv: Button
    private lateinit var radarRange: Button
    private lateinit var wifiRange: Button
    private lateinit var cbBle: CheckBox
    private lateinit var cbClassic: CheckBox
    private var envIdx = 1
    private var rangeIdx = 1
    private val rangeM = doubleArrayOf(15.0, 20.0, 40.0, 50.0)
    private val envN = doubleArrayOf(2.0, 2.5, 3.2)
    private val envName = arrayOf("Open space", "Indoor", "Obstructed")
    private val scanner by lazy { BtScanner(this, onChange = { refreshRadar() }, onFinished = { msg -> onRadarFinished(msg) }) }


    // ------------------------------------------------------------------------------------
    // Wi-Fi radar: access points in range, animated sweep, Wi-Fi RTT distance when available
    // ------------------------------------------------------------------------------------
    private lateinit var wifiRadar: RadarView
    private lateinit var wifiStatus: TextView
    private lateinit var wifiList: TextView
    private lateinit var wifiStart: Button
    private lateinit var wifiStop: Button
    private lateinit var wifiEnv: Button
    private lateinit var cbRtt: CheckBox
    private val wifiScanner by lazy { WifiScanner(this, onChange = { refreshWifiRadar() }, onFinished = { msg -> onWifiRadarFinished(msg) }) }

    private fun cycleRange() {
        rangeIdx = (rangeIdx + 1) % rangeM.size
        val t = "Range: ${rangeM[rangeIdx].toInt()} m"
        if (::radarRange.isInitialized) radarRange.text = t
        if (::wifiRange.isInitialized) wifiRange.text = t
        if (::radar.isInitialized) radar.setRange(rangeM[rangeIdx])
        if (::wifiRadar.isInitialized) wifiRadar.setRange(rangeM[rangeIdx])
    }

    private fun refreshEnvButtons() {
        val t = "Env: ${envName[envIdx]}"
        if (::radarEnv.isInitialized) radarEnv.text = t
        if (::wifiEnv.isInitialized) wifiEnv.text = t
    }

    private fun buildWifiRadarCard(parent: LinearLayout) {
        val c = cardLayout()
        c.addView(label("WI-FI RADAR  •  ACCESS POINTS + RTT", 12f, cyan, true))
        addGap(c, 4)
        wifiRadar = RadarView(this).apply { wifiMode = true }
        c.addView(wifiRadar, LinearLayout.LayoutParams(-1, dp(310)))
        val opts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        cbRtt = CheckBox(this).apply { text = "Use Wi-Fi RTT"; isChecked = true; setTextColor(white) }
        wifiEnv = Button(this).apply {
            text = "Env: ${envName[envIdx]}"
            setOnClickListener { envIdx = (envIdx + 1) % envName.size; refreshEnvButtons(); if (scanner.scanning || wifiScanner.scanning) Toast.makeText(this@MainActivity, "Applies to the next scan", Toast.LENGTH_SHORT).show() else { refreshRadar(); refreshWifiRadar() } }
        }
        opts.addView(cbRtt)
        c.addView(opts)
        wifiRange = Button(this).apply { text = "Range: ${rangeM[rangeIdx].toInt()} m"; setOnClickListener { cycleRange() } }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(wifiEnv, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        row2.addView(wifiRange, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        c.addView(row2)
        wifiRadar.setRange(rangeM[rangeIdx])
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        wifiStart = Button(this).apply { text = "Start Wi-Fi radar (60 s)"; setOnClickListener { withPermission(wifiPermissions(), wifiStatus, true) { startWifiRadar() } } }
        wifiStop = Button(this).apply { text = "Stop"; isEnabled = false; setOnClickListener { stopWifiRadar(true) } }
        btns.addView(wifiStart, LinearLayout.LayoutParams(0, -2, 1.6f).apply { marginEnd = dp(4) })
        btns.addView(wifiStop, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        c.addView(btns)
        wifiStatus = label("Idle.", 12f, white, true)
        c.addView(wifiStatus)
        wifiList = label("", 11f, white).apply { typeface = Typeface.MONOSPACE }
        c.addView(wifiList)
        addGap(c, 6)
        c.addView(label("Shows Wi-Fi access points and hotspots in range, not every phone or camera connected to them (Android cannot list other clients). ✓ = measured by Wi-Fi RTT (802.11mc access points only, on phones with RTT hardware, Wi-Fi on; Location on for Android 12 and below). ~ = estimated from signal strength, rough. Android limits foreground Wi-Fi scans to about 4 per 2 minutes, so the plot refreshes every 20 s or so. Direction is arbitrary. Range only changes how the plot is scaled, not how far the radio hears; beyond about 15 m an RSSI distance is a very rough band. A nearby access point is not evidence of recording.", 10f, muted))
        parent.addView(c)
    }

    private fun startWifiRadar() {
        val err = wifiScanner.start(60_000L, cbRtt.isChecked, envN[envIdx])
        if (err != null) { wifiStatus.text = err; addHistory("WIFI_RADAR", err); return }
        wifiRadar.setScanning(true); wifiRadar.setBlips(emptyList())
        wifiStart.isEnabled = false; wifiStop.isEnabled = true; cbRtt.isEnabled = false
        addHistory("WIFI_RADAR", "Started (RTT=${cbRtt.isChecked}, ${envName[envIdx]})")
        refreshWifiRadar()
    }

    private fun stopWifiRadar(notify: Boolean) {
        wifiScanner.stop()
        if (notify) onWifiRadarFinished("Stopped")
    }

    private fun onWifiRadarFinished(why: String) {
        wifiRadar.setScanning(false)
        wifiStart.isEnabled = true; wifiStop.isEnabled = false; cbRtt.isEnabled = true
        refreshWifiRadar()
        val s = wifiScanner.summary()
        val notes = ArrayList<String>()
        if (wifiScanner.scansThrottled > 0) notes.add("${wifiScanner.scansThrottled} of ${wifiScanner.scansRequested} scan requests were throttled by Android (cached results used)")
        if (cbRtt.isChecked && wifiScanner.rttAvailable == false) notes.add("Wi-Fi RTT is not available (no RTT hardware, Wi-Fi/Location off, or disabled by the system)")
        if (cbRtt.isChecked && wifiScanner.rttAvailable == true && wifiScanner.aps.values.none { it.mc }) notes.add("No 802.11mc access point in range, so all distances are RSSI estimates")
        wifiScanner.lastError?.let { notes.add(it) }
        if (wifiScanner.aps.isEmpty()) notes.add("No access points found. Checklist:\n" + diagnostics())
        wifiStatus.text = "$why · $s" + (if (notes.isEmpty()) "" else "\n" + notes.joinToString("\n"))
        currentSnapshot.clear()
        wifiScanner.aps.values.forEach { currentSnapshot.add("WIFI|${it.ssid.ifBlank { "<hidden SSID>" }}|${it.bssid}|${it.freq}MHz") }
        deviceOutput.text = "Wi-Fi radar: $s\nUse Save snapshot to compare later."
        addHistory("WIFI_RADAR", s)
    }

    private fun refreshWifiRadar() {
        if (!::wifiRadar.isInitialized) return
        val list = wifiScanner.list()
        wifiRadar.setBlips(list.mapNotNull { a ->
            wifiScanner.distanceM(a)?.let { RadarView.Blip(a.bssid, ((a.bssid.hashCode() and 0x7fffffff) % 360).toFloat(), it, if (a.band() == 24) 3 else 4, wifiScanner.isMeasured(a)) }
        })
        if (wifiScanner.scanning) wifiStatus.text = "Scanning… ${wifiScanner.remainingSec()} s · ${wifiScanner.summary()}" + (wifiScanner.lastError?.let { " · $it" } ?: "")
        wifiList.text = if (list.isEmpty()) "" else list.take(15).joinToString("\n") { a ->
            val band = when (a.band()) { 24 -> "2.4G"; 5 -> "5G  "; else -> "6G  " }
            val m = wifiScanner.isMeasured(a)
            val d = wifiScanner.distanceM(a)
            val dist = if (d == null) "   ?   " else if (d > rangeM[rangeIdx]) " >${rangeM[rangeIdx].toInt()} m " else (if (m) " " else "~") + String.format(Locale.US, "%4.1f m", d)
            val rssi = if (a.hasRssi()) "${a.rssi()} dBm" else "-- dBm"
            val extra = listOfNotNull(a.security, if (a.mc) "802.11mc" else null, if (m) String.format(Locale.US, "RTT %.1f±%.1f m", a.rttM, a.rttStdM ?: 0.0) else null).joinToString("  ")
            "$band ch${a.channel().toString().padEnd(3)} $dist${if (m) "✓" else " "} ${rssi.padStart(8)}  ${a.ssid.ifBlank { "<hidden>" }}\n          ${a.bssid}  $extra"
        }
    }

    private fun scanPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun buildRadarCard(root: LinearLayout) {
        val c = cardLayout()
        c.addView(label("BLUETOOTH RADAR  •  BLE + CLASSIC", 12f, cyan, true))
        addGap(c, 4)
        radar = RadarView(this)
        c.addView(radar, LinearLayout.LayoutParams(-1, dp(310)))
        val opts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        cbBle = CheckBox(this).apply { text = "BLE"; isChecked = true; setTextColor(white) }
        cbClassic = CheckBox(this).apply { text = "Classic"; isChecked = true; setTextColor(white) }
        radarEnv = Button(this).apply {
            text = "Env: ${envName[envIdx]}"
            setOnClickListener { envIdx = (envIdx + 1) % envName.size; refreshEnvButtons(); if (scanner.scanning || wifiScanner.scanning) Toast.makeText(this@MainActivity, "Applies to the next scan", Toast.LENGTH_SHORT).show() else { refreshRadar(); refreshWifiRadar() } }
        }
        opts.addView(cbBle); opts.addView(cbClassic)
        c.addView(opts)
        radarRange = Button(this).apply { text = "Range: ${rangeM[rangeIdx].toInt()} m"; setOnClickListener { cycleRange() } }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(radarEnv, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        row2.addView(radarRange, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        c.addView(row2)
        radar.setRange(rangeM[rangeIdx])
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        radarStart = Button(this).apply { text = "Start scan"; setOnClickListener { withPermission(scanPermissions(), radarStatus, true) { startRadar() } } }
        radarStop = Button(this).apply { text = "Stop"; isEnabled = false; setOnClickListener { stopRadar(true) } }
        btns.addView(radarStart, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        btns.addView(radarStop, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        c.addView(btns)
        radarStatus = label("Idle.", 12f, white, true)
        c.addView(radarStatus)
        radarList = label("", 11f, white).apply { typeface = Typeface.MONOSPACE }
        c.addView(radarList)
        addGap(c, 6)
        c.addView(label("Distance is estimated from signal strength (RSSI) and is only rough: bodies, walls and phone orientation can shift it by several metres. Bluetooth gives no direction, so the angle on the radar is arbitrary. Many devices use random addresses and some never advertise. A nearby device is not evidence that it is recording. Range only changes how the plot is scaled, not how far the radio hears; beyond about 15 m an RSSI distance is a very rough band.", 10f, muted))
        root.addView(c)
    }

    private fun startRadar() {
        if (!cbBle.isChecked && !cbClassic.isChecked) { Toast.makeText(this, "Select BLE and/or Classic", Toast.LENGTH_SHORT).show(); return }
        val err = scanner.start(if (cbBle.isChecked && cbClassic.isChecked) 48_000L else 30_000L, cbBle.isChecked, cbClassic.isChecked, envN[envIdx])
        if (err != null) { radarStatus.text = err; addHistory("BT_RADAR", err); return }
        radar.setScanning(true); radar.setBlips(emptyList())
        radarStart.isEnabled = false; radarStop.isEnabled = true; cbBle.isEnabled = false; cbClassic.isEnabled = false
        addHistory("BT_RADAR", "Started (BLE=${cbBle.isChecked}, Classic=${cbClassic.isChecked}, ${envName[envIdx]})")
        refreshRadar()
    }

    private fun stopRadar(notify: Boolean) {
        scanner.stop()
        if (notify) onRadarFinished("Stopped")
    }

    private fun onRadarFinished(why: String) {
        radar.setScanning(false)
        radarStart.isEnabled = true; radarStop.isEnabled = false; cbBle.isEnabled = true; cbClassic.isEnabled = true
        refreshRadar()
        val s = scanner.summary()
        radarStatus.text = "$why · $s\n${scanner.raw()}" + (scanner.lastError?.let { "\n$it" } ?: "") +
            (if (scanner.devices.isEmpty()) "\nNo devices found. Checklist:\n" + diagnostics() + "\nMany phones and accessories do not advertise unless pairing/finding mode is on, and Classic discovery only lists devices set to “discoverable”. Try near earbuds, a watch, a TV remote or an AirTag-type tag." else "")
        currentSnapshot.clear()
        scanner.devices.values.forEach { currentSnapshot.add((if (it.classic) "BT_CLASSIC" else "BLE") + "|${it.name ?: "Unknown"}|${it.address}") }
        deviceOutput.text = "Bluetooth radar: $s\nUse Save snapshot to compare later (random BLE addresses change over time)."
        addHistory("BT_RADAR", s)
    }

    private fun knownLabel(addr: String): String {
        val labels = try { JSONObject(prefs.getString("device_labels", "{}")) } catch (_: Exception) { JSONObject() }
        val v = labels.optJSONObject(addr) ?: return ""
        val t = v.optString("label").ifBlank { v.optString("name") }
        return if (t.isBlank()) "" else " [$t]"
    }

    private fun refreshRadar() {
        if (!::radar.isInitialized) return
        val list = scanner.list()
        radar.setBlips(list.mapNotNull { d ->
            scanner.distanceM(d)?.let { RadarView.Blip(d.address, ((d.address.hashCode() and 0x7fffffff) % 360).toFloat(), it, if (d.le && d.classic) 2 else if (d.classic) 1 else 0) }
        })
        if (scanner.scanning) radarStatus.text = "Scanning (${scanner.phase()})… ${scanner.remainingSec()} s · ${scanner.summary()}\n${scanner.raw()}" + (scanner.lastError?.let { "\n$it" } ?: "")
        radarList.text = if (list.isEmpty()) "" else list.take(15).joinToString("\n") { d ->
            val kind = if (d.le && d.classic) "LE+BR" else if (d.le) "LE   " else "BR   "
            val dist = scanner.distanceM(d)?.let { if (it > rangeM[rangeIdx]) ">${rangeM[rangeIdx].toInt()} m " else String.format(Locale.US, "%4.1f m", it) } ?: "  ?   "
            val rssi = if (d.hasRssi()) "${d.rssi()} dBm" else "-- dBm"
            val extra = listOfNotNull(d.vendor, d.cls).joinToString("/")
            "$kind $dist ${rssi.padStart(8)}  ${d.name ?: "Unknown"}${knownLabel(d.address)}\n         ${d.address}${if (extra.isNotEmpty()) "  ($extra)" else ""}"
        }
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
        historyOutput.text = if (filtered.isEmpty()) "No matching events." else filtered.joinToString("\n")
    }

    private fun scanWifiNetworks() {
        currentSnapshot.clear()
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val enabled = wm.isWifiEnabled
            currentSnapshot.add("WIFI_STATE|${if (enabled) "enabled" else "disabled"}")
            if (!enabled) {
                deviceOutput.text = "Wi-Fi is disabled. Enable it in Android settings, then scan again."
                addHistory("WIFI", "Wi-Fi disabled")
                return
            }
            @Suppress("DEPRECATION")
            val started = try { wm.startScan() } catch (_: SecurityException) { false }
            @Suppress("DEPRECATION")
            val results = wm.scanResults
            if (results.isNullOrEmpty()) {
                deviceOutput.text = "No Wi-Fi scan results available. Android may require location/nearby Wi-Fi permissions, Location services, or a fresh scan. This is not evidence that no devices are present."
                addHistory("WIFI", "Scan returned no results; startScan=$started")
                return
            }
            results.forEach { r ->
                val row = "WIFI|${r.SSID.ifBlank { "<hidden SSID>" }}|${r.BSSID ?: "unknown"}|${r.frequency}MHz|RSSI ${r.level}dBm"
                currentSnapshot.add(row)
            }
            deviceOutput.text = "Visible Wi-Fi networks: ${results.size}\n" + results.take(30).joinToString("\n") { "• ${it.SSID.ifBlank { "<hidden>" }}  ${it.BSSID ?: ""}  ${it.level} dBm  ${it.frequency} MHz" }
            addHistory("WIFI_SCAN", "${results.size} visible networks; startScan=$started")
        } catch (e: SecurityException) {
            deviceOutput.text = "Wi-Fi scan permission unavailable. Grant the permission in Android settings (App info → Permissions) and try again."
            addHistory("WIFI_ERROR", "Permission unavailable")
        } catch (e: Exception) {
            deviceOutput.text = "Wi-Fi scan unavailable: ${e.message ?: e.javaClass.simpleName}"
            addHistory("WIFI_ERROR", e.message ?: e.javaClass.simpleName)
        }
    }

    private fun showPairedBluetooth() {
        currentSnapshot.clear()
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) { deviceOutput.text = "Bluetooth is not supported on this device."; addHistory("BT", "Not supported"); return }
            if (!adapter.isEnabled) { deviceOutput.text = "Bluetooth is disabled. Enable it in Android settings, then try again."; addHistory("BT", "Bluetooth disabled"); return }
            val bonded = adapter.bondedDevices ?: emptySet()
            if (bonded.isEmpty()) {
                deviceOutput.text = "No paired Bluetooth devices are available to this app. This list is paired devices only, not a scan of every nearby device."
                addHistory("BT_SCAN", "0 paired devices")
                return
            }
            bonded.forEach { d -> currentSnapshot.add("BT_PAIRED|${d.name ?: "Unknown"}|${d.address}") }
            deviceOutput.text = "Paired Bluetooth devices: ${bonded.size}\n" + bonded.joinToString("\n") { "• ${it.name ?: "Unknown"}  ${it.address}" } + "\n\nNote: paired devices are not the same as all nearby devices."
            addHistory("BT_SCAN", "${bonded.size} paired devices listed")
        } catch (e: SecurityException) {
            deviceOutput.text = "Bluetooth permission unavailable. Grant the Nearby devices permission in Android settings and try again."
            addHistory("BT_ERROR", "Permission unavailable")
        } catch (e: Exception) {
            deviceOutput.text = "Bluetooth listing unavailable: ${e.message ?: e.javaClass.simpleName}"
            addHistory("BT_ERROR", e.message ?: e.javaClass.simpleName)
        }
    }

    private fun saveSnapshot() {
        if (currentSnapshot.isEmpty()) { Toast.makeText(this, "Run a Wi-Fi or Bluetooth check first", Toast.LENGTH_SHORT).show(); return }
        val snapshots = try { JSONArray(prefs.getString("snapshots", "[]")) } catch (_: Exception) { JSONArray() }
        snapshots.put(JSONObject().put("time", nowStamp()).put("items", JSONArray(currentSnapshot)))
        val trimmed = JSONArray()
        val from = (snapshots.length() - 30).coerceAtLeast(0)
        for (i in from until snapshots.length()) trimmed.put(snapshots.get(i))
        prefs.edit().putString("snapshots", trimmed.toString()).apply()
        snapshotCountRefresh(trimmed.length())
        addHistory("SNAPSHOT", "Saved snapshot with ${currentSnapshot.size} entries")
        Toast.makeText(this, "Snapshot saved", Toast.LENGTH_SHORT).show()
    }

    private fun snapshotCountRefresh(n: Int) {
        prefs.edit().putInt("snapshot_count", n).apply()
        if (::snapshotSummary.isInitialized) snapshotSummary.text = "Snapshots saved: $n (maximum 30)"
    }

    private fun compareSnapshots() {
        val a = try { JSONArray(prefs.getString("snapshots", "[]")) } catch (_: Exception) { JSONArray() }
        if (a.length() < 2) { snapshotSummary.text = "Save at least two snapshots to compare."; return }
        val old = a.getJSONObject(a.length() - 2)
        val newer = a.getJSONObject(a.length() - 1)
        val oldSet = (0 until old.getJSONArray("items").length()).map { old.getJSONArray("items").getString(it) }.toSet()
        val newSet = (0 until newer.getJSONArray("items").length()).map { newer.getJSONArray("items").getString(it) }.toSet()
        val added = newSet - oldSet
        val removed = oldSet - newSet
        snapshotSummary.text = "Compared ${old.optString("time")} → ${newer.optString("time")}\nNew entries: ${added.size}; no longer visible: ${removed.size}\n" + (if (added.isEmpty()) "No new entries." else "New:\n" + added.take(15).joinToString("\n")) + (if (removed.isEmpty()) "\nNo entries disappeared." else "\nNo longer visible:\n" + removed.take(15).joinToString("\n")) + "\nChanges may reflect normal radio/network conditions; not proof of a threat."
        addHistory("COMPARE", "${added.size} new; ${removed.size} no longer visible")
    }

    private fun saveDeviceLabel() {
        val id = deviceIdInput.text.toString().trim()
        val name = deviceNameInput.text.toString().trim()
        val tag = deviceLabelInput.text.toString().trim()
        if (id.isBlank() || (name.isBlank() && tag.isBlank())) { Toast.makeText(this, "Enter a device ID and a name or label", Toast.LENGTH_SHORT).show(); return }
        val labels = try { JSONObject(prefs.getString("device_labels", "{}")) } catch (_: Exception) { JSONObject() }
        labels.put(id, JSONObject().put("name", name).put("label", tag).put("updated", nowStamp()))
        prefs.edit().putString("device_labels", labels.toString()).apply()
        deviceIdInput.text.clear(); deviceNameInput.text.clear(); deviceLabelInput.text.clear()
        refreshKnownDevices()
        addHistory("DEVICE_LABEL", "Updated label for $id")
        Toast.makeText(this, "Device note saved locally", Toast.LENGTH_SHORT).show()
    }

    private fun refreshKnownDevices() {
        if (!::knownDevicesView.isInitialized) return
        val labels = try { JSONObject(prefs.getString("device_labels", "{}")) } catch (_: Exception) { JSONObject() }
        val lines = labels.keys().asSequence().toList().sorted().take(50).map { id ->
            val v = labels.optJSONObject(id) ?: JSONObject()
            "• $id — ${v.optString("name")} [${v.optString("label")}]"
        }
        knownDevicesView.text = if (lines.isEmpty()) "No known-device labels saved." else lines.joinToString("\n")
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
            .setTitle("Clear local event history?")
            .setMessage("This removes saved event history from this app. Device labels and scan snapshots are kept.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ -> historyItems.clear(); persistHistory(); refreshHistory(); Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show() }
            .show()
    }

    private fun startShield() {
        if (running) return
        addHistory("AUDIO", "Acoustic monitor and masker starting; mode=${modeSpinner.selectedItemPosition}")
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10); return
        }
        val minRec = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minRec <= 0) { status.text = "●  MICROPHONE UNAVAILABLE"; return }
        val recBuffer = max(minRec, fftSize * 2)
        recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recBuffer)
        mode = modeSpinner.selectedItemPosition
        ambientKind = ambientSpinner.selectedItemPosition
        if (mode == 3 && wavData == null) { status.text = "●  CHOOSE A WAV FILE FIRST"; return }
        outRate = 48000
        val minOut = AudioTrack.getMinBufferSize(outRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minOut <= 0) { recorder?.release(); recorder = null; status.text = "●  AUDIO OUTPUT UNAVAILABLE"; return }
        track = AudioTrack(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(), AudioFormat.Builder().setSampleRate(outRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(), max(minOut, fftSize * 4), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
        if (track?.state != AudioTrack.STATE_INITIALIZED) {
            recorder?.release(); recorder = null; track?.release(); track = null
            status.text = "●  AUDIO OUTPUT UNAVAILABLE"; return
        }
        try { recorder!!.startRecording(); track!!.play() } catch (e: Exception) {
            status.text = "●  AUDIO START FAILED"; recorder?.release(); recorder = null; track?.release(); track = null; return
        }
        running = true; status.text = "●  MONITORING ACTIVE"; status.setTextColor(cyan)
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
                            status.text = "●  MONITORING ACTIVE"
                            status.setTextColor(cyan)
                            alertDetails.text = "語音型聲音已降低。仍無法確認附近裝置是否正在錄音。"
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
                    bandText.text = "Voice +${excessDb.coerceAtLeast(0.0).roundToInt()} dB over floor     Mask: ${if (maskingEnabled) "$lvl dBFS" else "off"}     Peak: $peakHz Hz"
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
        val wasRunning = running
        running = false
        if (wasRunning) addHistory("AUDIO", "Acoustic monitor and masker stopped")
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
    override fun onStop() { if (scanner.scanning) stopRadar(true); if (wifiScanner.scanning) stopWifiRadar(true); super.onStop() }
    override fun onDestroy() { scanner.stop(); wifiScanner.stop(); stopShield(); super.onDestroy() }


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
            require(b.size > 44 && String(b, 0, 4) == "RIFF" && String(b, 8, 4) == "WAVE") { "Not a WAV file" }
            var o = 12; var ch = 0; var sr = 0; var bits = 0; var fmt = 0; var dataOff = -1; var dataLen = 0
            while (o + 8 <= b.size) {
                val id = String(b, o, 4); val len = u32(o + 4)
                if (id == "fmt ") { fmt = u16(o + 8); ch = u16(o + 10); sr = u32(o + 12); bits = u16(o + 22) }
                if (id == "data") { dataOff = o + 8; dataLen = min(len, b.size - dataOff); break }
                o += 8 + len + (len and 1)
            }
            require(fmt == 1 && ch == 1 && bits == 16 && sr == 48000 && dataOff > 0) { "Need 48 kHz mono 16-bit PCM WAV" }
            val n = dataLen / 2
            val s = ShortArray(n) { i -> u16(dataOff + 2 * i).toShort() }
            var ms = 0.0; for (v in s) { val x = v / 32768.0; ms += x * x }
            wavRms = sqrt(ms / n).coerceAtLeast(1e-4); wavData = s
            Toast.makeText(this, "WAV loaded: ${n / 48000.0} s", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            wavData = null
            Toast.makeText(this, e.message ?: "Cannot read WAV", Toast.LENGTH_LONG).show()
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
