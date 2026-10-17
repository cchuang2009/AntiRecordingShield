# Anti-Recording Shield — Privacy Environment Scanner

Cumulative development package, version 1.1.0 (Android). This package implements a practical baseline for the v0.8 → v0.9 → v1.0 → v1.1 roadmap. It is source code, not a verified release APK.

## Implemented in this source snapshot
- Acoustic input-level analysis (RMS-derived dBFS and peak sample value).
- Wi-Fi access-point scan results when Android returns them and permissions/settings allow it.
- Bluetooth Low Energy scan for visible broadcasters (10-second scan).
- Local-only event history stored in app SharedPreferences (up to 500 entries).
- Export history as CSV through Android's document picker.
- Scan snapshots include timestamps and are intended as user reference data.

## Known limitations
- Acoustic levels do not identify speech reliably and cannot detect whether another device is recording.
- Wi-Fi scan results are access points, not a guaranteed inventory of clients on the connected network. Android may throttle scans or return cached/incomplete results.
- BLE scanning only sees devices advertising discoverable BLE packets. Many devices will not appear. Classic Bluetooth discovery is not implemented in this snapshot.
- Unknown/new device is not proof of a camera, microphone, or recording activity.
- No masking, ultrasound, RF countermeasure, or recording-blocking claim is included.
- No data upload is implemented by this source. History is local to app storage; CSV export is user initiated.

## Build
Open the `AntiRecordingShield_v0.3` directory in Android Studio and sync Gradle. The project uses Android Gradle Plugin 8.10.1, Kotlin 2.0.21, JDK 17 toolchain compatibility, compile SDK 36, min SDK 26 and target SDK 36. Use a compatible installed Android SDK and Android Studio version.

The included `gradlew` in the inherited project is a legacy placeholder, not a complete standard Gradle Wrapper (wrapper JAR is absent). Therefore `./gradlew assembleDebug` is not expected to work until a standard wrapper is generated. From a machine with Gradle 8.13 installed, run `gradle wrapper --gradle-version 8.13`, or use Android Studio's Gradle sync/build UI. Do not treat source ZIP integrity as proof of compilation.

## Milestones
See `ROADMAP.md`, `DATA_SCHEMA.md`, and `REFERENCES.md`.
