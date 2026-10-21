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
