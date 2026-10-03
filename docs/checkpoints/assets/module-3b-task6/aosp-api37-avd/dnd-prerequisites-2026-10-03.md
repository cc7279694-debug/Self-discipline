# DND prerequisites — minimal runner evidence

Date: 2026-10-03 (Asia/Shanghai)
Source baseline: 1725e6a0e0f9f03fb868efe315d842f243ebcb0a
Device: Mirra_API_37 AOSP AVD, API 37; not a physical/OEM device.
Runner: com.guanyi.mirra.test/androidx.test.runner.AndroidJUnitRunner
Class filter: com.guanyi.mirra.platform.focus.AndroidDndSystemTest

The test package was rebuilt with `:app:assembleDebugAndroidTest --no-daemon` (BUILD SUCCESSFUL; Kotlin test compilation executed). Both APKs were installed using `adb install -r`. No wipe, clear or uninstall command was issued. Initially no Mirra package was installed; after installation no database existed and no reading Session was active.

## Actual completion events (condensed from runner output)

| Test | Initially denied | Explicitly granted | Restored denied |
|---|---|---|---|
| accessibleNonMirraRuleIsIgnored | status -4 / assumption | status 0 / passed | status -4 / assumption |
| controlledPolicyChangeIsRejected | status -4 / assumption | status 0 / passed | status -4 / assumption |
| ownedRuleIsReusableAndNeverChangesGlobalPolicy | status -4 / assumption | status 0 / passed | status -4 / assumption |

All denied events included:

```text
org.junit.AssumptionViolatedException: DND platform verification requires explicitly granted policy access
INSTRUMENTATION_STATUS_CODE: -4
```

Granted completion events each included `INSTRUMENTATION_STATUS_CODE: 0`; no assumption or skip events occurred.

| Run | discovered | executed assertions | passed | failed | skipped | runner time |
|---|---:|---:|---:|---:|---:|---|
| Initial denied | 3 | 0 | 0 | 0 | 3 | 0.129s |
| Granted | 3 | 3 | 3 | 0 | 0 | 0.191s |
| Restored denied | 3 | 0 | 0 | 0 | 3 | 0.141s |

Each runner summary printed `OK (3 tests)` and `INSTRUMENTATION_CODE: -1`, including denied runs. Only per-test status events establish pass versus assumption skip. No XML report was produced by this direct runner invocation.

## Explicit authorization and restoration

Initial denied access was proven by the three access assumptions. Outside the tests, the authorized granted scenario used `cmd notification allow_dnd com.guanyi.mirra`. After the granted run, `cmd notification disallow_dnd com.guanyi.mirra` restored access; the third run proved the original denied state was restored. No global Notification Policy was changed by these commands or the tests.

The legacy secure-settings key returned `null` even during granted access on API 37; it was not used as proof of permission. The actual access check and test completion events are authoritative for these runs.

API <29 / physical Android 13+ / OEM evidence: NOT RUN. Module 3B final freeze: not approved.
