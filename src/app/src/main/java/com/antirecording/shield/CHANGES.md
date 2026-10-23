# v1.2.1 (on top of 1.2.0)
RadarView.Blip.num + '#n' labels/tags on blips (labels no longer overlap: numbers drawn for up to 24 blips). MainActivity: stable btNums/wifiNums (reset on scan start), '#n' in list rows, lists up to 30 rows, sort-by-number checkboxes, numbered one-off scan lists and LAN hits. Bilingual 1.2.1 entry.
NOT compiled or run on a device.

# v1.2.0 (on top of 1.1.10) - roadmap items 1-7
NEW files: RoomCheck.kt (orchestrator + ReportPdf), LanScanner.kt, LensFinder.kt, OuiDb.kt, RadioCluster.kt, Beeper.kt, Pro.kt, PlayBilling.kt.txt (disabled), MANIFEST_ADDITIONS.md
MainActivity: 5th tab 'Room check' (scrollable tab row), mark-as-known buttons, known-aware suspect checks (name OR camera-maker prefix), proximity beeps, cluster code moved to RadioCluster.
Needs the manifest additions (CAMERA, INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, CHANGE_WIFI_MULTICAST_STATE) - see MANIFEST_ADDITIONS.md.
NOT compiled or run on a device.

# v1.1.10 (on top of 1.1.9)
Full translation pass: ~180 UI strings in MainActivity + 23 in BtScanner + 9 in WifiScanner wrapped in L.t(en, zh). envName became a getter (not a class-level initializer, so the language setting applies). History details/tags/data records intentionally left English. Wi-Fi help text: 20 s -> 30 s.

# v1.1.9 (on top of 1.1.8)
NEW L.kt (Auto/English/中文, L.t(en, zh)). Converted: finder, suspect alerts, updatedText (Bt/Wi-Fi), DirectionFinder errors, RadarView/FinderView labels, Features card (active language only + language button), the 6 Chinese sound-shield strings (now bilingual). ~285 older English strings in MainActivity are NOT yet translated.

# v1.1.8 (on top of 1.1.7)
MainActivity: physKey replaced by clusterRadios()/macBits()/clusterKey(): hidden/guest BSSIDs merged by band+channel, <=6 dB, <=8 differing MAC bits (locally-administered bit ignored); named SSID first; status shows 'radios N'. FeatureNotes/FEATURES.md: bilingual 1.1.8.

# v1.1.7 (on top of 1.1.6)
NEW SuspectDetector.kt (keyword match on BT / Wi-Fi names). RadarView: type 5 = red suspect blip + legend. MainActivity: red/bold list rows with ⚠, suspects sorted first, red banner under each radar, history entry once per device. FeatureNotes/FEATURES.md: bilingual 1.1.7 entry.

# v1.1.6 (on top of 1.1.5)
BtScanner: MAC normalised (trim + upper-case) as the map key; raw() shows unique MACs + merged repeats.
WifiScanner: BSSID key lower-cased.
MainActivity: Wi-Fi text list grouped per physical radio with '+N more on this radio'.
FeatureNotes.kt / FEATURES.md: bilingual 1.1.6 entry (header version now 1.1.6).

# Hush Shield v1.1.4 -> v1.1.5 (radar: less clutter, live movement)
Drop-in replacements (same package): BtScanner.kt, WifiScanner.kt, RadarView.kt, MainActivity.kt

BtScanner    - per-advertisement UI refresh coalesced to <= 1 per 250 ms (was one refresh per packet -> lag)
             - list(maxAnon): nameless random-address LE devices (mostly Apple) capped; hiddenAnon counts the rest; 10 s expiry for them
             - ageFrac(d) for fading
WifiScanner  - ageFrac(ap)
RadarView    - blips glide to new distance (~250 ms), fade when stale, max 40 near + 8 beyond-range drawn, "+N more not drawn"
             - non-overlapping labels, smaller halos, ping rings only for the 10 nearest
MainActivity - Wi-Fi radar: one blip per SSID (nearest BSSID); better angle hash; anonymous-device note in status; version 1.1.5

Last-updated label - small grey line under each radar. BLE/Classic: seconds since the last packet of each radio (+ which radio is listening); Wi-Fi: age of the newest scan result, seconds to next scan, Android-throttle count.

v1.1.5b - Wi-Fi radar: one blip per physical radio (BSSID minus last byte + channel) instead of per SSID; list below still shows every BSSID
        - "Fast scan" checkbox (Wi-Fi): scan every 6 s / read cache every 2 s; Normal is now 30 s (the most Android allows without throttling)
        - Wi-Fi RSSI median 5 -> 3 samples, BLE 7 -> 5: reacts faster, a little more jumpy

v1.1.5c - NEW: Direction finder (BLE) in the Radar tab: DirectionFinder.kt (compass + address-filtered BLE scan -> signal strength per
          10-degree heading), FinderView.kt (heading-up dial + arrow), MainActivity wiring (target picker, status, lifecycle).
          Add the two new files to the same package; no new permissions (rotation sensor needs none).

v1.1.5d - Bilingual (EN + 中文) Features card in the Wi-Fi · Notes tab, fed by FeatureNotes.kt (also drives the version in the header);
          FEATURES.md keeps the same text. Every new version adds a bilingual entry at the top of both.

Unchanged: the angle is still arbitrary (RSSI has no direction); Wi-Fi scan cadence is limited by Android (~4 scans / 2 min).
NOT compiled or run on a device - build and test before release.
