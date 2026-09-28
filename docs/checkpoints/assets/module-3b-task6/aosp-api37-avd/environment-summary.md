# Task 6B 脱敏设备摘要

- alias: `aosp-api37-avd`
- manufacturer/model: Google / `sdk_gphone64_x86_64`
- Android/API: 17 / 37
- emulator build: `sdk_gphone64_x86_64-userdebug 17 CE2A.260420.019`
- Mirra implementation SHA: `c058f2e46e04739307d37e96e5fc16f9e677ee7d`
- ADB: 37.0.1-15733141
- Usage Access: not granted during Task 6B manual preflight
- Notification app-op: ignored by default during preflight
- DND access: not exercised
- Physical device: no
- OEM coverage: not run
- Task 6C class reruns: `ModuleTwoAFlowTest` PASS; `PhaseOneCorrectionTest` PASS
- Task 6C full-suite retry: environment failure after 2 tests (`Transport endpoint is not connected`)
- Task 6C cold boot retry: emulator did not establish an ADB device within 4 minutes and exited
- Diagnostic classification: AVD / connected test-environment instability; no confirmed production-code defect

No serial number, full logcat, bugreport, real notes, or complete installed-app list is stored.

## Task 6B final evidence completion (2026-09-28)

- App was absent from the initial package list. A dedicated Learning Item, Note, normal Session, and enabled DND preference were created after the first install. A second `adb install -r` and manual cold start retained the data and preference.
- The installed `mirra.db` returned `PRAGMA user_version = 4`.
- Wi-Fi and mobile data were disabled together; `Active default network: none`. Mirra cold-started and displayed local data. Both network toggles were restored to enabled.
- Usage Access was granted (`GET_USAGE_STATS: allow`). A live session snapshot reported `monitoringState=FULL`; ActivityManager showed the app-owned FocusMonitoringService in the foreground.
- Authorized Force Stop recovery: before stop, Room reported `monitoringStatus=FULL` and ActivityManager showed the FGS in foreground. Process/FGS disappeared and did not return during an 8-second observation. Manual cold start recovered the session as `ABNORMAL`, downgraded monitoring to `PARTIAL`, released the active slot, and persisted an `UNMONITORED` segment; local item/note data remained.
- DND Modes access was granted and the Profile status became `READY`. During the live monitored session, Room nevertheless recorded `APPLY_FAILED` while DND access at start was granted. The Mirra-owned rule was enabled but `STATE_FALSE`; after abnormal recovery Room recorded `RELEASED` and the owned rule was disabled / remained `STATE_FALSE`. No retry, override, or second session was attempted. Flag the observed discrepancy for Sol High review; normal finish/release is `NOT RUN`.
- Risk-app thresholds, lock/screen-off, Usage Access revocation, Task Manager Stop, reboot, API 23/29/33/34/35, and physical/OEM devices remain `NOT RUN`.
- No relevant app/DND exception lines were found in the inspected logcat buffer. No full logcat, serial, UUID, or user-authored content is stored.
- Earlier `-wipe-data` mistake remains documented in the checkpoint and is not erased or reworded.
