# Roadmap: v0.8 → v0.9 → v1.0 → v1.1

## v0.8 — working foundation
- Remove masking and ultrasound controls.
- Acoustic input-level analysis with clear non-detection disclaimer.
- Wi-Fi scan results and BLE scan, permission-aware UI.
- Timestamped local event log and CSV export.

## v0.9 — saved reference data
- Local event persistence (maximum 500 events in this baseline).
- Scan snapshots with timestamps; export as CSV.
- Manual user interpretation: unknown devices remain unclassified until the user labels them.
- Next improvement: normalized device records and user-managed known-device labels.

## v1.0 — unified privacy dashboard
- Combine acoustic, Wi-Fi and BLE status in one screen.
- Distinguish status, observation and interpretation.
- Add clear permission-denied, radio-off, throttled-scan and empty-results states.
- Next improvement: searchable history and per-scan session IDs.

## v1.1 — reliability and release readiness
- Add conservative messaging, local-first data handling and CSV export.
- Document Android limitations and avoid false claims of hidden recorder detection.
- Before release: test Pixel 6a and at least one other Android version; test denied permissions, Wi-Fi off, location services off, BLE off, scan throttling, empty scan, rotate/relaunch, export and history clearing.
- Run Gradle build, lint, and permission-policy review; not yet certified by this source package.

## Acceptance criteria
1. No UI states that a device is recording merely because it is visible over Wi-Fi/Bluetooth.
2. A zero-result scan is described as inconclusive, never as “safe”.
3. Saved event rows have timestamp, type, and detail.
4. CSV export opens through Android's Storage Access Framework and contains escaped CSV fields.
5. Microphone monitoring stops when the Activity is destroyed.
6. Build and device testing are recorded separately from source implementation.
