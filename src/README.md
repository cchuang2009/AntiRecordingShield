# Hush Shield（原 Anti-Recording Shield）v1.1.4

Android 原生專案（Kotlin、單一 Activity、程式碼建立 UI）。
- package / applicationId：`com.antirecording.shield`
- minSdk 26，compileSdk / targetSdk 36（Google Play 自 2026-08-31 起要求新 App 與更新以 API 36 為目標）
- Gradle 8.13（含 wrapper）、Android Gradle Plugin 8.13.2、Kotlin 2.2.21、JDK 17

## 建置
```bash
# 需要 JDK 17 與 Android SDK（Android Studio 會自動處理）
echo "sdk.dir=$HOME/Android/Sdk" > local.properties      # 路徑依你的系統調整
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # 手機以 USB 連線並開啟 USB 偵錯
```
或直接用 Android Studio：File → Open → 選本資料夾 → Sync → Run。

## 上架（Google Play）
1. 複製 `keystore.properties.example` 為 `keystore.properties` 並填入上傳金鑰（`keytool -genkeypair -v -keystore upload-keystore.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000`）。
2. `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`。
3. Play Console 建立 App，啟用 Play App Signing，上傳 AAB。
4. 素材：`store/icons/png/play_store_icon_512.png`、`feature_graphic_1024x500.png`；文案見 `store/STORE_LISTING.md`；隱私權政策範本見 `store/PRIVACY_POLICY.md`（需放到公開網址）。
5. 每次發版把 `versionCode` +1。

## 畫面（v1.1.1 起分頁）
頂端有四個分頁：**Sound Shield**（遮蔽與即時監測）、**Radar**（BLE + Classic 藍牙雷達）、**Wi-Fi · Notes**（Wi-Fi 掃描、已配對藍牙、快照、已知裝置標籤、事件紀錄）、**Tests**（實機測試清單）。

## 功能與權限
| 功能 | 權限 |
|---|---|
| 麥克風監測、遮蔽 | `RECORD_AUDIO` |
| 偵測到語音時震動提示 | `VIBRATE` |
| Wi-Fi 掃描／Wi-Fi 雷達（選用） | **所有 Android 版本都需要精確定位 `ACCESS_FINE_LOCATION`（含 `ACCESS_COARSE_LOCATION`）並開啟系統「位置」開關**——官方文件指出 `getScanResults()`／`startScan()` 即使在 Android 13+ 仍需此權限；Android 13+ 另加 `NEARBY_WIFI_DEVICES`（RTT 用）；另需 `ACCESS_WIFI_STATE`、`CHANGE_WIFI_STATE` |
| 已配對藍牙清單（選用） | Android 12+：`BLUETOOTH_CONNECT` |
| 藍牙雷達：BLE 掃描 + Classic 探索（選用） | Android 12+：`BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`；所有版本都需 `ACCESS_FINE_LOCATION` 並開啟「位置」開關（不宣告 `neverForLocation`，避免系統濾掉信標類廣播）；Android 11 以下另需 `BLUETOOTH`、`BLUETOOTH_ADMIN` |

Wi-Fi／藍牙權限在按下按鈕時才請求。Play Console 的「資料安全性」需如實填寫：音訊只在本機處理；Wi-Fi 掃描結果與歷史紀錄只存在本機。

## 藍牙雷達（v1.1）
- `BtScanner.kt`：BLE `ScanCallback`（低延遲、含延伸廣播）＋ Classic `startDiscovery()`。**兩者同時開啟時以 12 秒為一段輪流執行**（BLE → Classic → BLE → Classic），因為許多手機在 Classic 探索進行時 BLE 掃描會被餓死；單一模式掃 30 秒，雙模式掃 48 秒。狀態列會顯示原始計數（BLE hits / Classic hits / inquiries）以便診斷。
- `RadarView.kt`：旋轉掃描線與漸層拖尾、掃過時亮點發光、新裝置出現時擴散環；半徑＝估計距離（對數刻度，環線 1 / 3 / 7 / 15 m）。預覽動畫：`store/radar_preview.gif`。
- 距離公式：`d = 10^((RSSI@1m − RSSI) / (10·n))`；`RSSI@1m` 取廣播的 TX power − 41 dB，沒有則用 −59 dBm；`n` 可在 App 內切換（開闊 2.0 / 室內 2.5 / 有阻擋 3.2）；RSSI 取最近 7 次的中位數。
- 只有距離有意義；**方向無法由藍牙取得**，雷達上的角度由位址雜湊決定。許多裝置使用隨機位址或不廣播，附近有裝置不代表它在錄音。
- 因宣告 `neverForLocation`，部分信標（beacon）類廣播可能被系統過濾；若需要完整信標掃描，需移除該旗標並改為請求定位權限。

## Wi-Fi 雷達（v1.1.4，`WifiScanner.kt`）
- 在 **Wi-Fi · Notes** 分頁按「Start Wi-Fi radar (60 s)」：掃描範圍內的 Wi-Fi 存取點／熱點並畫在雷達上（2.4 GHz 綠色、5/6 GHz 黃色）。**只能看到存取點，Android 無法列出連在上面的其他手機或攝影機。**
- 距離優先順序：① **Wi-Fi RTT（802.11mc）實測**，雷達上標 `✓` 並加白圈，列表顯示 `RTT x±σ m`（需手機有 RTT 硬體、AP 支援 802.11mc、Wi-Fi 開啟；Android 12 以下還需開啟定位服務）；② 否則用 RSSI 估算，標 `~`：`d = 10^((RSSI@1m − RSSI)/(10·n))`，`RSSI@1m` 為 −40（2.4G）/ −47（5G）/ −50（6G）dBm，`n` 同藍牙雷達的 Env 設定。
- Android 對前景 Wi-Fi 掃描限流（約 2 分鐘 4 次），因此每 20 秒要求一次新掃描、每 4 秒讀取快取結果；RTT 測距每 3 秒一批（最多 `RangingRequest.getMaxPeers()` 個 AP）。
- 列表顯示頻段、頻道、距離、RSSI、SSID、BSSID、加密方式（`OPEN` 表示未加密）與是否支援 802.11mc。
- 快照格式：`WIFI|SSID|BSSID|頻率MHz`（不含 RSSI，方便前後比對）。

## 雷達範圍與「找不到裝置」疑難排解
- 雷達的 **Range（15 / 20 / 40 / 50 m）只改變畫面比例**，不改變無線電能聽到多遠；超出範圍的裝置會畫在邊緣並標 `>N m`。藍牙／Wi-Fi 的 RSSI 距離在約 15 m 以外誤差極大，只能當作粗略距離帶。
- 掃不到任何東西時，App 會在狀態列列出檢查表：精確定位權限、定位開關、附近裝置權限、Wi-Fi、藍牙是否開啟。常見原因：
  1. **Wi-Fi：** 沒有精確定位權限（在彈窗選了「大概位置」），或系統定位開關關閉。v1.1.4 的 manifest 已移除先前對定位權限的 `maxSdkVersion="32"` 限制。
  2. **藍牙：** 未允許「附近的裝置」；Android 11 以下需開定位。BLE 只會看到「正在廣播」的裝置（耳機、手錶、電視、標籤等，手機多半不廣播）；Classic 只會列出「可被探索」的裝置。
  3. 權限被拒絕時，v1.1.4 會彈出對話框並提供「Open settings」。

## 已知限制
- 遮蔽模式不保證阻止錄音或語音辨識；App 也無法偵測附近裝置是否正在錄音。
- 目前以 Activity 執行：螢幕關閉或 App 切到背景時，Android 會限制麥克風擷取。若要背景持續運作，需改為前景服務（`foregroundServiceType="microphone"`）並顯示常駐通知，且須在 Play Console 說明用途。
- Wi-Fi 掃描受 Android 節流限制（前景約 2 分鐘 4 次），且在 Android 12 以下需開啟「定位服務」。
- 專案未包含單元測試；`MainActivity.kt` 內有一張「PIXEL 6a / REAL-DEVICE TEST LOG」開發用卡片，正式版建議移除。
