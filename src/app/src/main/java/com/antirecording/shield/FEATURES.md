# Hush Shield — Features / 功能

> Rule: every new version adds a bilingual (English + 中文) entry at the top, here and in `FeatureNotes.kt`.
> 規則：每個新版本都要在此檔與 `FeatureNotes.kt` 最上方新增英中對照說明。

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
