# Hush Shield — Features / 功能

> Rule: every new version adds a bilingual (English + 中文) entry at the top, here and in `FeatureNotes.kt`.
> 規則：每個新版本都要在此檔與 `FeatureNotes.kt` 最上方新增英中對照說明。

## v1.2.0

**English**
- New 'Room check' tab: one tap scans Bluetooth and Wi-Fi (optionally your LAN), gives a verdict (needs attention / worth checking / no obvious signs) and exports a PDF report with a physical-inspection checklist.
- LAN camera scan: finds devices on the Wi-Fi you are connected to that behave like IP cameras or recorders (RTSP, ONVIF, camera web pages, service ports). It never logs in or reads video, scans one /24 at most and asks for confirmation first.
- Camera-maker database: downloads the public IEEE list on request and flags Wi-Fi and Bluetooth (Classic) addresses made by camera companies.
- Lens & IR finder: uses the camera as a visual aid - flash mode circles glints that appear only while the torch is on, IR mode shows infrared LEDs in a dim room. Nothing is saved or sent.
- Known devices: 'Mark as known' buttons under both radars; devices you mark are no longer flagged red.
- Direction finder: optional proximity beeps - the stronger the signal, the faster the beeps.
- Pro structure: free/paid split and paywall are ready (all features stay unlocked in this build); a Play Billing skeleton is included but disabled.
- Reminder: everything here gives hints only. A camera that is off, has no radio, or records to a memory card cannot be found by radio scanning.

**中文**
- 新增「房間檢查」分頁：一鍵掃描藍牙與 Wi-Fi（可選擇再掃描區網），給出結論（需要留意／值得檢查／沒有明顯跡象），並匯出附實地檢查清單的 PDF 報告。
- 區網攝影機掃描：找出您目前連線的 Wi-Fi 上、行為像網路攝影機或錄影機的裝置（RTSP、ONVIF、攝影機網頁、服務埠）。不會登入也不會讀取影像，最多掃描一個 /24 網段，且會先請您確認。
- 攝影機廠商資料庫：依需求從公開的 IEEE 清單下載，標出由攝影機製造商生產的 Wi-Fi 與藍牙（傳統）位址。
- 鏡頭與紅外線輔助：用相機做視覺輔助——閃光模式圈出只在手電筒亮時才出現的反光，紅外線模式在昏暗房間顯示紅外線燈。不會儲存或傳送任何影像。
- 已知裝置：兩個雷達下方新增「標記為已知」按鈕；您標記的裝置不會再以紅色標示。
- 找方向：新增可選的接近提示音——訊號越強，嗶聲越快。
- Pro 架構：免費／付費分級與付費牆已就緒（此版本所有功能仍全部解鎖）；附上預設停用的 Google Play 付款範本。
- 提醒：以上都只是線索。已關閉、沒有無線電、或只錄到記憶卡的攝影機，無法用無線電掃描找出。

## v1.1.10

**English**
- Full Chinese translation: all screens, buttons, hints, permission messages, scan notes, error messages and the sound-shield texts now follow the language setting (Auto / English / 中文).
- Wi-Fi radar help text corrected: normal mode refreshes about every 30 s (it said 20 s).
- Kept in English on purpose: the saved event history / CSV records, technical tags (WIFI, BT_RADAR ...), snapshot data, and standard terms such as BLE, RSSI, RTT, WPA2.

**中文**
- 全面中文化：所有畫面、按鈕、提示、權限訊息、掃描說明、錯誤訊息與聲音護盾文字，現在都會依語言設定（自動／English／中文）顯示。
- 修正 Wi-Fi 雷達說明：一般模式約每 30 秒更新一次（之前寫 20 秒）。
- 刻意保留英文：已儲存的事件紀錄／CSV、技術標籤（WIFI、BT_RADAR 等）、快照資料，以及 BLE、RSSI、RTT、WPA2 等通用術語。

## v1.1.9

**English**
- Language: the new texts (suspicious-device alerts, direction finder, "last updated" lines, radar legend, Features card) now follow the phone's language - Chinese phones show Chinese, others English.
- Language button in the Features card: Auto / English / 中文 (the screen reloads).
- Still to translate: the older English-only screens and buttons; they stay in English for now.

**中文**
- 語言：新增的文字（可疑裝置警示、找方向、「最後更新」、雷達圖例、功能卡）現在會依手機語言顯示 — 中文手機顯示中文，其他顯示英文。
- 功能卡內新增語言按鈕：自動／English／中文（切換後畫面會重新載入）。
- 尚未翻譯：較早期的純英文畫面與按鈕，目前仍維持英文。

## v1.1.8

**English**
- Better duplicate removal for Wi-Fi: a hidden / guest / virtual BSSID of the same radio is now merged with its named network even when the first byte differs (locally-administered bit), as long as band + channel match, signals are within 6 dB and the MACs are nearly identical.
- Each Wi-Fi row shows the named SSID first with "+N more on this radio"; the status line adds "radios N" next to the BSSID count.
- Heuristic: two different access points of the same make on the same channel with similar signal could occasionally be merged.

**中文**
- Wi-Fi 重複去除更準：同一個無線電的隱藏／訪客／虛擬 BSSID，即使第一個位元組不同（本地管理位元），只要頻段與頻道相同、訊號差在 6 dB 內、MAC 幾乎相同，就會和有名稱的網路合併成一行。
- 每一行優先顯示有名稱的 SSID，其餘列為「+N more on this radio」；狀態列在 BSSID 數量旁新增「radios N」。
- 這是推測式合併：同品牌、同頻道、訊號相近的兩台不同基地台，偶爾可能被誤合併。

## v1.1.7

**English**
- Suspicious recorder names: Bluetooth and Wi-Fi names that look like a camera or recorder (webcam, IPC, CAM, DVR/NVR, GoPro, dash/body/spy cam, voice recorder ...) are marked ⚠ and listed in RED, first in the list.
- Radar: suspicious devices get a red pulsing blip (always drawn and labelled), a "Suspect" legend entry and a red banner under the radar; each new one is also written to the history.
- Important: a name match is only a hint. Many hidden cameras advertise no name or no Bluetooth at all, and ordinary devices can have such names. Always check physically.

**中文**
- 可疑錄影／錄音名稱：藍牙與 Wi-Fi 名稱像攝影機或錄音器（webcam、IPC、CAM、DVR/NVR、GoPro、行車／隨身／針孔攝影機、錄音筆等）會加上 ⚠ 並以紅色列在清單最前面。
- 雷達：可疑裝置顯示紅色脈動圖點（一定會畫出並標示）、圖例新增「Suspect」，雷達下方有紅色提示；每個新發現的也會寫入歷史紀錄。
- 重要：名稱符合只是線索。許多針孔攝影機不廣播名稱，甚至完全沒有藍牙；一般裝置也可能取到類似名稱。請務必現場實際檢查。

## v1.1.6

**English**
- Duplicate control: device addresses (MAC) are normalised, so the same MAC is always counted once; the Bluetooth status shows unique MACs and how many repeated packets were merged.
- Wi-Fi list now shows one row per physical radio; the other SSIDs of that radio are listed underneath instead of as extra rows.
- Note: devices that rotate their random Bluetooth address (e.g. many Apple devices) use a different MAC each time and cannot be merged reliably.

**中文**
- 重複控制：裝置位址（MAC）統一格式，相同 MAC 一律只算一個；藍牙狀態列顯示不重複 MAC 數與已合併的重複封包數。
- Wi-Fi 清單改為每個實體無線電只列一行，同一無線電的其他 SSID 列在下方，不再各佔一行。
- 注意：會輪換隨機藍牙位址的裝置（例如許多 Apple 裝置）每次使用不同 MAC，無法可靠地合併。

## v1.1.5

**English**
- Bluetooth radar: BLE scan + Classic discovery (alternating 12 s slots) with estimated distance from signal strength.
- Cleaner radar: blips glide to their new distance, fade when stale, labels never overlap, "+N more" counter; at most 6 anonymous random-address BLE devices are listed.
- Wi-Fi radar: one blip per physical access-point radio (virtual SSIDs merged); RTT-measured distance (✓) on supported access points.
- Fast scan option for Wi-Fi (needs the Developer option "Wi-Fi scan throttling" turned off); normal mode scans every 30 s, the most Android allows.
- "Last updated Ns ago" line under each radar: BLE / Classic packet age and which radio is listening; Wi-Fi data age and time to the next scan.
- **New — Direction finder (BLE):** pick a device, turn slowly on the spot for ~20 s; compass + signal strength show where the signal is strongest (about ±30–45°).
- Reminder: radio presence is not proof of recording; distance and direction are rough estimates.

**中文**
- 藍牙雷達：BLE 掃描＋傳統藍牙搜尋（每 12 秒輪流），依訊號強度估計距離。
- 雷達更清爽：圖點平滑移到新距離、久未聽到會淡出、標籤不重疊、顯示「+N 個未繪出」；匿名隨機位址 BLE 裝置最多列出 6 個。
- Wi-Fi 雷達：同一個實體基地台無線電只畫一個點（合併虛擬 SSID）；支援的基地台可用 RTT 實測距離（✓）。
- Wi-Fi「快速掃描」選項（需在開發者選項關閉「Wi-Fi 掃描調節」）；一般模式每 30 秒掃描一次，是 Android 允許的最快頻率。
- 每個雷達下方新增「最後更新 N 秒前」：顯示 BLE／Classic 封包時間與目前聆聽的無線電；Wi-Fi 顯示資料時間與下次掃描時間。
- **新增「找方向」（BLE）：** 選一個裝置，在原地慢慢轉約 20 秒；以羅盤加訊號強度找出訊號最強的方位（約 ±30–45°）。
- 提醒：偵測到無線訊號不等於正在錄音；距離與方向都只是粗略估計。
