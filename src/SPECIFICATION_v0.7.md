# Product specification — Privacy Environment Scanner v1.1

This specification supersedes the old v0.7 masking prototype scope.

## Product goal
Provide Android users with transparent, local-first observations about acoustic input level and visible Wi-Fi/BLE activity. It does not detect recording state or block recording.

## Modules
1. Acoustic analysis: microphone RMS-derived dBFS level and peak sample level.
2. Wi-Fi awareness: Android-provided nearby access point scan results, where available.
3. Bluetooth awareness: visible BLE advertisements, where available.
4. Local reference log: timestamped observations, persisted locally, exportable to CSV.

## Language rules
Use “visible”, “observed”, “scan result”, and “unrecognized”. Never claim “recorder detected”, “hidden camera detected”, “safe room”, or guaranteed prevention based only on these observations.

## Non-goals
Audio masking, ultrasonic countermeasures, RF jamming, unauthorized network access, and recording-state inference.
