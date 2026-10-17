# Local reference data schema

Each event is stored in app-private SharedPreferences under `ars_local/history` as a JSON array. Maximum 500 rows; oldest rows are removed when new events exceed the limit.

| Field | Type | Meaning |
|---|---|---|
| `time` | ISO-8601 string | Local device time with UTC offset |
| `type` | string | Event category, e.g. `wifi_scan`, `bluetooth_scan_result`, `acoustic_monitor_started` |
| `detail` | string | Human-readable observation; may include scan rows |

CSV export columns: `time,type,detail`. Quotes are doubled and line breaks normalized before export.

## Important privacy notes
- History remains in app-private local storage unless the user exports it.
- A user-selected CSV destination is managed by Android's document picker.
- Clear history before sharing a phone with another person if the log contains sensitive environmental details.
- This is reference data, not forensic evidence: timestamps, scan visibility and device identifiers can be incomplete or change between scans.
